package ai.darshj.djproxy.ovpnengine

import android.content.Context
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ovpnsocks.Ovpnsocks

/**
 * The ovpnengine lane's controller (mirrors [ai.darshj.djproxy.tor.OnionProxyManager]'s role): drives
 * the gomobile-bound userspace OpenVPN client (`ovpnsocks`, = ooni/minivpn + wireguard netstack +
 * go-socks5). It brings ONE OpenVPN tunnel up from a `.ovpn` profile and exposes it as a loopback
 * SOCKS5 the EXISTING hev tunnel routes through — so a VPN Gate / OpenVPN server is used AS a proxy,
 * exactly like the embedded Tor lane's `127.0.0.1:9050`. No core file is touched.
 *
 * The engine's own socket to the VPN Gate server is created inside DJProxy's app process, which the tun
 * already excludes via `addDisallowedApplication(self)` — so it egresses directly (no routing loop),
 * while device traffic flows tun → hev → 127.0.0.1:<port> → minivpn → the VPN Gate exit.
 *
 * Contract: never throws (the native seam is wrapped); [start] suspends until the OpenVPN handshake
 * completes (or fails). Ciphers supported by minivpn: AES-128/256-CBC, AES-128/256-GCM + SHA1/256/512;
 * a server outside that set returns null (honest partial — surfaced to the ui as a connect failure).
 */
class OvpnEngineController(private val appContext: Context) {

    @Volatile
    private var running = false

    @Volatile
    private var port = 0

    /**
     * The real reason the last [start] failed (the underlying engine/handshake error, or a timeout),
     * or null after a success. The ui surfaces this verbatim so the user sees WHY a VPN Gate server
     * would not connect (unsupported cipher, unreachable server, bad profile) instead of a generic
     * "try another server" guess that was the same for every failure.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The loopback SOCKS5 port the engine settled on while running, else 0. */
    fun socksPort(): Int = port

    /**
     * Bring up the OpenVPN tunnel from [ovpn] profile text and return a `socks5://127.0.0.1:<port>`
     * [ProxyConfig] to feed the existing apply path, or null if the tunnel could not be established
     * (unsupported cipher / bad profile / no network / timeout). Suspends until connected. Never throws.
     *
     * Bounded by [CONNECT_TIMEOUT_MS]: `Ovpnsocks.start` blocks over the whole OpenVPN handshake and,
     * for an unreachable/UDP-blocked server, could otherwise block forever — leaving the ui spinner
     * stuck on "connecting" (the "Connect does nothing" report). On timeout we tear the half-started
     * engine down and fail with an honest reason instead of hanging.
     */
    suspend fun start(ovpn: String): ProxyConfig? = withContext(Dispatchers.IO) {
        if (running) stopInternal()
        lastFailure = null
        val cacheDir = appContext.cacheDir.absolutePath
        val p: Int? = try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                // runInterruptible so a timeout cancellation interrupts the blocking JNI call.
                runInterruptible { Ovpnsocks.start(ovpn, cacheDir).toInt() }
            }
        } catch (t: Throwable) {
            // The engine surfaces the real cause (e.g. "vpn connect: ...", "parse .ovpn: ...").
            lastFailure = engineReason(t)
            LogBus.w(TAG, "OpenVPN engine failed to connect: $lastFailure")
            runCatching { Ovpnsocks.stop() }
            null
        }
        if (p == null) {
            if (lastFailure == null) {
                // withTimeoutOrNull returned null with no exception = the handshake never completed.
                lastFailure = "timed out after ${CONNECT_TIMEOUT_MS / 1000}s — the server is unreachable " +
                    "or not answering (try a different VPN Gate server, ideally a nearer one)"
                LogBus.w(TAG, "OpenVPN engine $lastFailure")
                runCatching { Ovpnsocks.stop() }
            }
            running = false
            port = 0
            _active.value = false
            return@withContext null
        }
        port = p
        running = true
        _active.value = true
        lastFailure = null
        LogBus.i(TAG, "OpenVPN engine up — routing via local SOCKS5 127.0.0.1:$port")
        ProxyConfig(type = ProxyType.SOCKS5, host = LOOPBACK, port = port)
    }

    /** Distils a readable cause from the engine's Go-side error string (kept short for the ui). */
    private fun engineReason(t: Throwable): String {
        val raw = (t.message ?: t.javaClass.simpleName).trim()
        return when {
            raw.contains("parse .ovpn", ignoreCase = true) -> "the server's profile could not be parsed ($raw)"
            raw.contains("cipher", ignoreCase = true) -> "the server uses a cipher this engine can't do yet ($raw)"
            raw.contains("vpn connect", ignoreCase = true) -> "the OpenVPN handshake failed ($raw)"
            raw.isBlank() -> "the OpenVPN handshake failed"
            else -> raw
        }
    }

    /** Tear the OpenVPN tunnel down. Idempotent; never throws. */
    fun stop() {
        runCatching { stopInternal() }
    }

    private fun stopInternal() {
        if (running || port != 0) {
            runCatching { Ovpnsocks.stop() }
            LogBus.i(TAG, "OpenVPN engine stopped")
        }
        running = false
        port = 0
        _active.value = false
    }

    companion object {
        private const val TAG = "OvpnEngine"
        private const val LOOPBACK = "127.0.0.1"
        /** Upper bound on the whole OpenVPN bring-up. Generous enough for a real handshake (VPN Gate
         *  servers can take 5–15s), short enough that an unreachable server fails instead of hanging. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
