package ai.darshj.djproxy.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService as GpTorService

/**
 * SPI that isolates the embedded-Tor engine from the lane's state machine. [TorControllerImpl] depends
 * ONLY on this interface, so the whole state machine + `127.0.0.1:9050` config production is unit-tested
 * against a fake, with zero Android / tor-android dependency in the test path.
 *
 * The single production implementation ([GuardianOnionProxyManager]) is the ONLY file in the app that
 * imports guardianproject symbols — so if the platform lane bumps the tor-android version, the blast
 * radius is exactly one file.
 *
 * Contract: no method throws. [bootstrap] suspends until Tor is ready or has failed.
 */
interface OnionProxyManager {
    /** The loopback SOCKS5 port Tor is (or will be) listening on. Meaningful only after a Ready result. */
    val socksPort: Int

    /**
     * Launch Tor and drive [onProgress] with the live bootstrap percentage (0..100). Suspends until Tor
     * finishes bootstrapping ([TorLaunchResult.Ready]) or fails ([TorLaunchResult.Failed]). MUST NOT throw.
     */
    suspend fun bootstrap(onProgress: (Int) -> Unit): TorLaunchResult

    /**
     * Revert to normal routing (Tor "disabled"). Implementations MAY keep the tor engine **warm** rather
     * than tearing it down — the embedded tor daemon runs `tor_run_main()` in-process and is not
     * re-entrant, so a live implementation keeps it running and lets [bootstrap] reuse it on the next
     * enable (the real engine is fully released only on process death). Idempotent. MUST NOT throw.
     */
    fun shutdown()

    companion object {
        /** Tor's conventional loopback SOCKS5 port. */
        const val DEFAULT_SOCKS_PORT: Int = 9050

        /** The loopback host the proxy config always points at. */
        const val LOOPBACK_HOST: String = "127.0.0.1"
    }
}

/** Terminal outcome of [OnionProxyManager.bootstrap]. */
sealed interface TorLaunchResult {
    /** Tor is bootstrapped and listening on [socksPort] (127.0.0.1). */
    data class Ready(val socksPort: Int) : TorLaunchResult

    /** Tor could not bootstrap (no network / timed out / the tor process exited). */
    data class Failed(val reason: String) : TorLaunchResult
}

/**
 * The real embedded-Tor engine, backed by guardianproject **tor-android**
 * (`org.torproject.jni.TorService`, which runs the tor daemon via JNI and ships the binary for all
 * four of DJProxy's ABIs — arm64-v8a / armeabi-v7a / x86 / x86_64) and its **jtorctl** control port.
 *
 * Flow:
 *  1. `startForegroundService` + `bindService(BIND_AUTO_CREATE)` the guardianproject `TorService`.
 *     Binding hands us the service instance (via its `LocalBinder`), which exposes the jtorctl
 *     [TorControlConnection] and the resolved SOCKS/HTTP ports.
 *  2. Poll `getInfo("status/bootstrap-phase")` on the control connection, parsing `PROGRESS=NN`, and
 *     forward each new percentage to [onProgress]. A `STATUS_OFF` broadcast short-circuits to Failed.
 *  3. At 100% (or a `STATUS_ON` broadcast) read the real SOCKS port (`getSocksPort()`, default 9050)
 *     and return [TorLaunchResult.Ready].
 *
 * Everything is wrapped defensively — the seam contract is "never throw".
 */
class GuardianOnionProxyManager(
    private val appContext: Context,
    private val bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    private val bootstrapTimeoutMs: Long = BOOTSTRAP_TIMEOUT_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : OnionProxyManager {

    @Volatile
    override var socksPort: Int = OnionProxyManager.DEFAULT_SOCKS_PORT
        private set

    @Volatile
    private var connection: ServiceConnection? = null

    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** Latest lifecycle status seen from the guardianproject STATUS broadcast (ON/OFF/STARTING/STOPPING). */
    @Volatile
    private var lastStatus: String = ""

    /**
     * The live, already-bootstrapped guardianproject service, kept **warm** for the whole process
     * lifetime once Tor first comes up. This is the crux of the re-enable fix: tor-android runs the real
     * tor daemon by calling the native `tor_run_main()` **in-process via JNI**, and tor is NOT re-entrant
     * — invoking `tor_run_main()` a second time in the same process aborts natively (observed on-device:
     * `SIGABRT` inside `libtor.so parse_rfc1123_time` right after a second `Acquired lock`). So we launch
     * tor exactly once per process: after the first successful bootstrap we cache the service here and
     * every later [bootstrap] reuses it instead of re-launching. The daemon is released only when the
     * process itself dies (which naturally gives the next launch a pristine process — see [shutdown]).
     */
    @Volatile
    private var warmService: GpTorService? = null

    /**
     * True once `tor_run_main()` has been invoked in THIS process (whether it went on to succeed or
     * fail). Guards the non-reentrancy invariant: if tor was started but is no longer usable (e.g. it
     * failed and exited, or the OS reaped the service without killing the process), we must NOT start it
     * again in-process — we fail cleanly and ask for an app restart rather than risk the native abort.
     */
    @Volatile
    private var everStarted: Boolean = false

    override suspend fun bootstrap(onProgress: (Int) -> Unit): TorLaunchResult = withContext(Dispatchers.IO) {
        runCatching {
            // ── Warm-reuse fast path ────────────────────────────────────────────────────────────────
            // Tor is already up from an earlier enable in this process: hand back the live SOCKS port
            // WITHOUT re-launching tor (tor_run_main is not re-entrant — a second call aborts natively).
            warmService?.let { svc ->
                // Confirm the warm daemon is still actually alive (control port answers). If the OS reaped
                // the service while the process lived on, its control connection is dead — we can't safely
                // relaunch tor in-process, so fail closed with a clear message instead of routing the whole
                // device into a dead loopback SOCKS (which would just surface as "connection refused").
                val alive = readBootstrapPercent(svc) != null || lastStatus == GpTorService.STATUS_ON
                if (alive) {
                    onProgress(100)
                    socksPort = readSocksPort(svc)
                    LogBus.i(TAG, "Tor already warm — reusing SOCKS5 127.0.0.1:$socksPort (no relaunch)")
                    return@runCatching TorLaunchResult.Ready(socksPort)
                }
                warmService = null
                LogBus.w(TAG, "Warm Tor engine is no longer responsive; an app restart is needed to reconnect.")
                return@runCatching TorLaunchResult.Failed(
                    "Tor stopped in the background. Fully close and reopen DJProxy to reconnect.",
                )
            }
            // Tor was launched once already but is no longer warm (failed/exited without process death).
            // Re-invoking tor_run_main() in this same process would SIGABRT, so fail closed and clearly.
            if (everStarted) {
                LogBus.w(TAG, "Tor already ran once this session and stopped; reopen DJProxy to use Tor again.")
                return@runCatching TorLaunchResult.Failed(
                    "Tor already ran once this session. Fully close and reopen DJProxy to use Tor again.",
                )
            }

            registerStatusReceiver()
            // Ensure the library broadcasts its STATUS to us (newer tor-android targets a package).
            runCatching { GpTorService.setBroadcastPackageName(appContext.packageName) }

            val bound = CompletableDeferred<GpTorService>()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val svc = runCatching { (binder as GpTorService.LocalBinder).service }.getOrNull()
                    if (svc != null && !bound.isCompleted) bound.complete(svc)
                }

                override fun onServiceDisconnected(name: ComponentName?) { /* re-bound on next start */ }
            }
            connection = conn

            val intent = Intent(appContext, GpTorService::class.java).setAction(GpTorService.ACTION_START)
            // Start with startService (NOT startForegroundService) + bind. tor-android 0.4.7.14's
            // TorService posts its own notification but does NOT call startForeground() itself, so
            // startForegroundService() would break its "must call startForeground within ~10s" promise
            // and ANR-kill the process (confirmed on-device: "startForegroundService() did not then call
            // startForeground()"). startService still delivers ACTION_START to onStartCommand (tor boots),
            // and the app stays alive via our own DjVpnService FGS + this active binding. Foreground-start
            // is allowed because the user is interacting (they just tapped Connect).
            // Mark BEFORE the launch: from here on tor_run_main has (or will have) run in this process,
            // so any later start must go through the warm-reuse / fail-closed guards above, not relaunch.
            everStarted = true
            runCatching { appContext.startService(intent) }
            val didBind = runCatching {
                appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!didBind) return@runCatching TorLaunchResult.Failed("Could not start the Tor service.")

            val svc = withTimeoutOrNull(bindTimeoutMs) { bound.await() }
                ?: return@runCatching TorLaunchResult.Failed("Tor service did not come up.")

            onProgress(0)
            val deadline = System.currentTimeMillis() + bootstrapTimeoutMs
            var last = -1
            while (System.currentTimeMillis() < deadline) {
                if (lastStatus == GpTorService.STATUS_OFF) {
                    return@runCatching TorLaunchResult.Failed("Tor stopped before it finished starting.")
                }
                val pct = readBootstrapPercent(svc)
                if (pct != null && pct != last) {
                    last = pct
                    onProgress(pct.coerceIn(0, 100))
                }
                val ready = (pct != null && pct >= 100) || lastStatus == GpTorService.STATUS_ON
                if (ready) {
                    socksPort = readSocksPort(svc)
                    // Keep this service warm for the rest of the process: subsequent enables reuse it
                    // instead of re-launching tor (which would abort natively — tor is not re-entrant).
                    warmService = svc
                    onProgress(100)
                    LogBus.i(TAG, "Tor bootstrapped 100% on SOCKS5 127.0.0.1:$socksPort")
                    return@runCatching TorLaunchResult.Ready(socksPort)
                }
                delay(pollIntervalMs)
            }
            TorLaunchResult.Failed("Tor timed out while building a circuit.")
        }.getOrElse { t ->
            LogBus.w(TAG, "Tor bootstrap error: ${t.message}")
            TorLaunchResult.Failed(t.message ?: "Tor failed to start.")
        }
    }

    /**
     * "Disable Tor" — reverts DJProxy to normal routing. Deliberately does **NOT** kill the tor daemon.
     *
     * tor runs via `tor_run_main()` in-process and is not re-entrant: stopping the service (its
     * `onDestroy` ends the tor thread) and then re-launching on the next enable makes the second
     * `tor_run_main()` abort the whole process (`SIGABRT` in `libtor.so`, confirmed on-device). So we
     * keep the daemon **warm** and simply let it idle — no traffic reaches it once [TorControllerImpl]
     * has switched the VPN routing away, so an idle loopback tor is harmless. The next enable takes the
     * warm-reuse fast path in [bootstrap] (instant, no relaunch).
     *
     * The daemon (and its binding/receiver) are released for real only when the process dies, which is
     * exactly what we want: a fresh process is the ONLY safe place to call `tor_run_main()` again. If the
     * OS reaps this bound service while the process lives on, [everStarted] makes the next [bootstrap]
     * fail closed (asking for an app restart) rather than risk the native abort. Idempotent, never throws.
     */
    override fun shutdown() {
        // Intentionally a no-op for the daemon — see KDoc. Kept as a named seam so callers (and future
        // maintainers) have one obvious place expressing the "we never tear tor down mid-process" rule.
    }

    /** Reads `status/bootstrap-phase` off the control port and extracts `PROGRESS=NN`, or null on any fault. */
    private fun readBootstrapPercent(svc: GpTorService): Int? = runCatching {
        val control: TorControlConnection = svc.torControlConnection ?: return null
        val phase = control.getInfo("status/bootstrap-phase") ?: return null
        PROGRESS_RE.find(phase)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }.getOrNull()

    /** The real SOCKS port the daemon settled on (9050, or an auto-assigned fallback). Never 0. */
    private fun readSocksPort(svc: GpTorService): Int =
        runCatching { svc.socksPort }.getOrNull()?.takeIf { it in 1..65535 }
            ?: OnionProxyManager.DEFAULT_SOCKS_PORT

    private fun registerStatusReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra(GpTorService.EXTRA_STATUS)?.let { lastStatus = it }
            }
        }
        receiver = r
        // Not-exported: this is an internal, app-scoped status signal (required flag on API 34+).
        ContextCompat.registerReceiver(
            appContext,
            r,
            IntentFilter(GpTorService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    companion object {
        private const val TAG = "Tor"
        private const val BIND_TIMEOUT_MS = 20_000L
        private const val BOOTSTRAP_TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_MS = 500L
        private val PROGRESS_RE = Regex("""PROGRESS=(\d+)""")
    }
}
