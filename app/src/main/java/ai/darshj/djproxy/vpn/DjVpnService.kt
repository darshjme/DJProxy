package ai.darshj.djproxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.engine.EngineState
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.SocketProtector
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileDescriptor

/**
 * The heart of the product: a [VpnService] that routes ALL device traffic through one user-supplied
 * proxy with every leak closed by construction. It owns the tun, the routes, the single
 * VpnService.protect() call site, the loopback SOCKS front, and the native engine sidecar.
 *
 * v3 CONNECTED criterion (§2.1): the tunnel is declared CONNECTED as soon as the native engine is
 * Running AND a real in-tun TCP probe ([ConnectivityProbe]) succeeds — the honest end-to-end
 * data-path check. The former leak self-test is GONE from the critical path: IPv6/UDP/DNS leak
 * checks now run AFTER connect as advisory indicators ([HealthMonitor]) that never throw, never tear
 * down, and never block CONNECTED. Leaks are still closed by construction (routes + DNS sentinel);
 * what changed is that observing a residual leak is advisory, not a gate.
 */
class DjVpnService : VpnService() {

    // Crash-proof scope (§4.2/§4.3): SupervisorJob + a CoroutineExceptionHandler that logs, reports
    // to the diagnostics sink, and fails the tunnel CLOSED instead of propagating to the process.
    private val serviceScope = Coro.safeScope(
        name = "vpn-service",
        reportCategory = CriticalFailure.Category.UNCAUGHT,
        onFatal = { failClosedNow(ProxyError.Io("service fault: ${it.message ?: it.javaClass.simpleName}")) },
    )

    /**
     * THE single protect() seam. This is the only place `VpnService.protect(...)` is ever called;
     * a CI grep asserts it. Every off-device socket (live dial, pre-flight validation, DNS-over-TCP)
     * is excluded from the tun through here so the tunnel can never loop back into itself.
     */
    private val protector = SocketProtector { socket -> protect(socket) }

    private var tunPfd: ParcelFileDescriptor? = null
    private var routerEnd: FileDescriptor? = null

    private var loopback: LoopbackProxy? = null
    private var engine: RemoteEngine? = null
    private var router: TunRouter? = null
    private var watchdog: EngineWatchdog? = null
    private var stats: StatsCollector? = null
    private var healthMonitor: HealthMonitor? = null
    private var dnsInterceptor: DnsInterceptor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var upJob: Boolean = false

    /**
     * True only between a passed leak self-test and teardown. While false, [bringUp] is the sole
     * owner of the engine lifecycle, so an engine fault during bring-up is handled by bring-up's own
     * teardown — never by an unmanaged reconnect. Once true, the [watchdog] owns reconnect-vs-teardown.
     */
    @Volatile private var connected: Boolean = false
    private val plumbLock = Any()

    /**
     * Local binding surface for the UI. The host activity binds with a plain [Intent] (no action);
     * [VpnService.SERVICE_INTERFACE] is reserved for the system's own bind, so we hand back the
     * [LocalBinder] for anything else. The controller it exposes is the ONE control surface the UI
     * drives (validate-before-up); the UI never touches sockets/tun/engine directly.
     */
    inner class LocalBinder : Binder() {
        fun controller(): VpnController = this@DjVpnService.controller
    }

    private val binder = LocalBinder()

    /** The single process-wide control surface; validate-before-up + fail-closed live in here. */
    private val controller: VpnController by lazy { VpnControllerImpl(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? {
        // The system binds the VPN with the reserved action; everything else is our local UI bind.
        return if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder
    }

    override fun onCreate() {
        super.onCreate()
        VpnRuntime.serviceProtector = protector
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The whole dispatch is guarded: a fault here (e.g. startForeground) must degrade, never crash.
        return runCatching { dispatch(intent, startId) }.getOrElse {
            LogBus.e(TAG, "onStartCommand fault: ${it.message}")
            FeatureRegistry.reportCritical(CriticalFailure(CriticalFailure.Category.BRINGUP_FAILED, "onStartCommand: ${it.message}"))
            runCatching { stopSelf() }
            START_NOT_STICKY
        }
    }

    private fun dispatch(intent: Intent?, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                enterForeground("Connecting…")
                // The secret never travels through system_server as an Intent extra: the controller
                // (same process) hands the validated config over the in-process holder, and always-on
                // restarts fall back to the encrypted persisted config.
                val config = VpnRuntime.currentConfig ?: loadPersistedConfig()
                if (config == null) {
                    LogBus.e(TAG, "connect with no config"); stopSelf(); return START_NOT_STICKY
                }
                serviceScope.launch { bringUp(config) }
            }

            ACTION_STOP -> {
                serviceScope.launch {
                    teardown("stopped by user")
                    VpnRuntime.update { VpnState.IDLE }
                    stopSelfResult(startId)
                }
            }

            else -> {
                // Null intent = system restart (START_STICKY / always-on). Re-establish last good config.
                val persisted = loadPersistedConfig()
                if (persisted != null) {
                    LogBus.i(TAG, "system restart — re-establishing tunnel")
                    enterForeground("Reconnecting…")
                    serviceScope.launch { bringUp(persisted) }
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    // ---- bring-up -------------------------------------------------------------------------------

    private suspend fun bringUp(config: ProxyConfig) {
        if (upJob) { LogBus.w(TAG, "bring-up already in progress"); return }
        upJob = true
        try {
            VpnRuntime.currentConfig = config
            persistConfig(config)
            VpnRuntime.counters.reset()
            VpnRuntime.update {
                it.copy(stage = VpnStage.CONNECTING, proxyRedacted = config.redacted(), error = null)
            }
            acquireWakeLock()

            // 1) Establish the tun with the full leak-proof route/DNS setup.
            val builder = Builder()
            TunBuilder.configure(builder, config, packageName)
            // establish() returns null ONLY when the OS has not granted VPN consent. Tag it as the
            // distinct, recognisable error so the UI re-requests the VPN permission and retries the
            // connect, instead of dead-ending on a raw "permission not granted" message.
            val pfd = builder.establish()
                ?: throw BringUpException(ProxyError.VpnPermissionRequired)
            tunPfd = pfd

            // 2) Start the loopback SOCKS front (policy) and the native engine (out-of-process),
            //    bridged via a socketpair.
            plumbEngine(config)

            // 3) Construct the watchdog BEFORE the router/self-test so the engine-fault handler always
            //    has a single owner to route into — but do NOT start it observing yet. While
            //    `connected` is false, bring-up owns the lifecycle and a fault fails the self-test.
            watchdog = EngineWatchdog(
                scope = serviceScope,
                engine = engine!!,
                reconnect = { reconnectEngine() },
                onGiveUp = {
                    FeatureRegistry.reportCritical(
                        CriticalFailure(CriticalFailure.Category.ENGINE_DEATH, "engine unrecoverable after retries"),
                    )
                    failClosedNow(ProxyError.Io("engine unrecoverable"))
                },
            )

            // 4) Start the router bridging real-tun <-> engine, enforcing the leak policy.
            startRouter(config)

            // 5) NEW CONNECTED criterion (§2.1): engine Running + a real in-tun TCP round-trip. This
            //    proves the WHOLE data path (tun→router→engine→loopback→dialer→proxy). Leak posture is
            //    NOT part of this decision — there is no LeakException hard-gate anymore.
            if (engine?.state?.value != EngineState.Running) {
                throw IllegalStateException("engine not running (${engine?.state?.value})")
            }
            LogBus.i(TAG, "running in-tun connectivity probe")
            val probe = ConnectivityProbe(knownExitIp = VpnRuntime.lastValidatedExitIp).run()
            // The engine is Running and the proxy was already proven by pre-flight validation, so a
            // probe miss is ADVISORY, not fatal (§2.1 revised): it almost always means the exit filters
            // our probe IPs, not that the tunnel is broken — the exact case where a valid proxy works
            // in Postern (which has no probe) but a hard gate here would refuse to connect. So we
            // CONNECT either way and surface reachability as a non-blocking health chip.
            val reachability: Health
            val exitIp: String?
            when (probe) {
                is ProbeOutcome.Ok -> {
                    reachability = Health.OK
                    exitIp = probe.exitIp
                    LogBus.i(TAG, "in-tun probe ok (latency=${probe.latencyMs}ms)")
                }
                is ProbeOutcome.Fail -> {
                    reachability = Health.DEGRADED
                    exitIp = VpnRuntime.lastValidatedExitIp
                    LogBus.w(TAG, "in-tun probe could not confirm reachability (${probe.error.message}); " +
                        "connecting anyway — proxy was pre-validated, likely the exit filters the probe IPs")
                }
            }

            // 6) Tunnel up: hand ownership to the watchdog, start supervision + stats, and only now
            //    begin treating engine faults as recoverable reconnects.
            connected = true
            watchdog?.start()
            stats = StatsCollector(serviceScope, VpnRuntime.counters, { engine }).also { it.start() }

            VpnRuntime.update {
                it.copy(
                    stage = VpnStage.CONNECTED,
                    proxyRedacted = config.redacted(),
                    connectedSinceMs = System.currentTimeMillis(),
                    health = HealthReport(reachability = reachability, checkedAtMs = System.currentTimeMillis()),
                    error = null,
                )
            }
            updateNotification("Connected · ${config.redacted()}")
            LogBus.i(TAG, "CONNECTED — device-wide tunnel up (reachability=$reachability)")

            // 7) ADVISORY health pass (§2.2): starts leak indicators + DNS-fallback. NEVER gates/tears down.
            dnsInterceptor?.let { healthMonitor = HealthMonitor(it).also { hm -> hm.start() } }

            // 8) Location seam (§10 step 9): hand the exit IP to the location lane if attached. Wrapped
            //    so a buggy feature lane can never break a successful bring-up (§9.7 invariant 1).
            runCatching {
                val controller = FeatureRegistry.locationController
                if (controller != null) serviceScope.launch { runCatching { controller.onProxyConnected(exitIp) } }
            }
        } catch (e: Throwable) {
            val err = mapError(e)
            LogBus.e(TAG, "bring-up failed: ${err.message}")
            FeatureRegistry.reportCritical(CriticalFailure(CriticalFailure.Category.BRINGUP_FAILED, err.message))
            teardown("bring-up failed")
            VpnRuntime.update {
                it.copy(stage = VpnStage.ERROR, error = err, connectedSinceMs = 0, health = VpnRuntime.lastHealthReport)
            }
            stopSelf()
        } finally {
            upJob = false
        }
    }

    /**
     * Creates the socketpair, starts the loopback SOCKS front, and starts the native engine in the
     * isolated `:engine` process. The MAIN process keeps only the router end of the socketpair and
     * the tun PFD; the engine end is handed across the Binder as a ParcelFileDescriptor and the
     * main process then closes its own copy, so an `:engine` crash fully closes that end (the router
     * read EOFs → fail-closed) and can never close the tun.
     */
    private fun plumbEngine(config: ProxyConfig) {
        synchronized(plumbLock) {
            val routerFd = FileDescriptor()
            val engineFd = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, routerFd, engineFd)
            routerEnd = routerFd
            val engineDup = ParcelFileDescriptor.dup(engineFd)
            runCatching { Os.close(engineFd) }

            val lp = VpnDependencies.loopbackProxyFactory(config, VpnRuntime.protector).also { it.start() }
            loopback = lp
            LogBus.i(TAG, "loopback SOCKS front on 127.0.0.1:${lp.listenPort}")

            val eng = engine ?: RemoteEngine(applicationContext).also { engine = it }
            val ok = eng.startRemote(engineDup, lp.listenPort, TunConfig.MTU, if (ai.darshj.djproxy.BuildConfig.DEBUG) "debug" else "warn")
            // The Binder has dup()'d the engine end into :engine; drop our copy so a crash there
            // closes the socketpair end and the router EOFs (fail-closed).
            runCatching { engineDup.close() }
            if (!ok) throw IllegalStateException("engine failed to start (${eng.state.value})")
        }
    }

    private fun startRouter(config: ProxyConfig) {
        val dialer = VpnDependencies.dialerFactory(config, VpnRuntime.protector)
        // Pluggable DNS (§3): default = composite DoH:443 ▸ DoT:853 ▸ TCP:53, all resolving at the
        // proxy exit. Built once and reused across reconnects so the sticky-fallback head survives.
        val dns = dnsInterceptor ?: DnsInterceptor.create(config, dialer).also { dnsInterceptor = it }
        router = TunRouter(
            tun = tunPfd!!,
            engineEnd = routerEnd!!,
            config = config,
            dns = dns,
            counters = VpnRuntime.counters,
            onTunFault = { serviceScope.launch { onTunLost() } },
            // Before CONNECTED, bring-up owns teardown; a fault here just fails the self-test.
            // After CONNECTED, the watchdog is the single owner of reconnect-vs-give-up.
            onEngineFault = {
                if (connected) {
                    watchdog?.reportFault()
                } else {
                    LogBus.w(TAG, "engine fault during bring-up (bring-up owns teardown)")
                }
            },
        ).also { it.start() }
    }

    // ---- reconnect (fail-closed) ----------------------------------------------------------------

    /**
     * Re-plumb the engine after a fault while HOLDING the tun + routes (nothing leaks in the gap).
     * Rebuilds the socketpair, restarts the out-of-process engine, and restarts the router pumps.
     * Returns true once the engine is Running again. The loopback front and the tun are preserved.
     */
    private suspend fun reconnectEngine(): Boolean {
        val config = VpnRuntime.currentConfig ?: return false
        synchronized(plumbLock) {
            router?.stop(); router = null
            closeQuietly(routerEnd); routerEnd = null

            val routerFd = FileDescriptor()
            val engineFd = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, routerFd, engineFd)
            routerEnd = routerFd
            val engineDup = ParcelFileDescriptor.dup(engineFd)
            runCatching { Os.close(engineFd) }

            val port = loopback?.listenPort ?: return false
            val eng = engine ?: RemoteEngine(applicationContext).also { engine = it }
            val ok = eng.startRemote(engineDup, port, TunConfig.MTU, if (ai.darshj.djproxy.BuildConfig.DEBUG) "debug" else "warn")
            runCatching { engineDup.close() }
            if (!ok) return false
        }
        startRouter(config)
        return engine?.state?.value == EngineState.Running
    }

    private suspend fun onTunLost() {
        // The tun itself is gone (revoke / OS teardown) — cannot hold routes; fail closed to IDLE.
        LogBus.w(TAG, "tun lost — tearing down")
        teardown("tun lost")
        VpnRuntime.update { VpnState.IDLE }
        stopSelf()
    }

    /** Synchronous fail-closed; safe to call from any thread (teardown is synchronized). */
    private fun failClosedNow(error: ProxyError) {
        teardown("fail-closed: ${error.message}")
        VpnRuntime.update {
            it.copy(stage = VpnStage.ERROR, error = error, connectedSinceMs = 0, health = VpnRuntime.lastHealthReport)
        }
        runCatching { stopSelf() }
    }

    private suspend fun failClosed(error: ProxyError) = failClosedNow(error)

    // ---- teardown ------------------------------------------------------------------------------

    private fun teardown(reason: String) {
        LogBus.i(TAG, "teardown: $reason")
        connected = false
        // Notify the location seam BEFORE we drop state; wrapped so a lane fault never breaks teardown.
        runCatching { FeatureRegistry.locationController?.onProxyDisconnected() }
        synchronized(plumbLock) {
            runCatching { healthMonitor?.stop() }; healthMonitor = null
            runCatching { stats?.stop() }; stats = null
            runCatching { watchdog?.stop() }; watchdog = null
            runCatching { router?.stop() }; router = null
            runCatching { engine?.stop() }; engine = null
            runCatching { loopback?.stop() }; loopback = null
            closeQuietly(routerEnd); routerEnd = null
            runCatching { tunPfd?.close() }; tunPfd = null
            dnsInterceptor = null
        }
        releaseWakeLock()
        runCatching { stopForegroundCompat() }
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked consent: drop everything, fail closed.
        LogBus.w(TAG, "onRevoke")
        serviceScope.launch {
            teardown("revoked")
            VpnRuntime.update { VpnState.IDLE }
            stopSelf()
        }
    }

    override fun onDestroy() {
        teardown("service destroyed")
        VpnRuntime.serviceProtector = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- errors --------------------------------------------------------------------------------

    private fun mapError(e: Throwable): ProxyError = when (e) {
        is BringUpException -> e.error
        else -> ProxyError.Io(e.message ?: e.javaClass.simpleName)
    }

    /** A data-path bring-up failure (e.g. the in-tun connectivity probe did not complete). */
    private class BringUpException(val error: ProxyError) : Exception(error.message)

    // ---- foreground + notification -------------------------------------------------------------

    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, DjVpnService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DJProxy")
            .setContentText(text)
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The text carries the proxy endpoint; keep it off the lockscreen / public surfaces.
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(
                @Suppress("DEPRECATION")
                Notification.Action.Builder(0, "Disconnect", stopPending).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Tunnel status", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the DJProxy tunnel while it is connected."
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    // ---- wakelock (keep the pump scheduled under doze / screen-off) -----------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "djproxy:tunnel").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---- config persistence (needed for always-on / process-restart re-establish) --------------

    private fun persistConfig(config: ProxyConfig) {
        val editor = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_TYPE, config.type.name)
            .putString(K_HOST, config.host)
            .putInt(K_PORT, config.port)
            .putString(K_USER, config.username)
            .putString(K_DNS, config.dnsServer)
        // Only the password is a secret: store it Keystore-encrypted, never in cleartext. If
        // encryption fails, drop it (fail-closed) rather than persist plaintext.
        if (config.password.isEmpty()) {
            editor.remove(K_PASS_ENC)
        } else {
            val enc = CredentialStore.encrypt(config.password)
            if (enc != null) editor.putString(K_PASS_ENC, enc) else editor.remove(K_PASS_ENC)
        }
        editor.apply()
    }

    private fun loadPersistedConfig(): ProxyConfig? {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(K_HOST, null) ?: return null
        val password = p.getString(K_PASS_ENC, null)?.let { CredentialStore.decrypt(it) } ?: ""
        return ProxyConfig(
            type = proxyTypeOf(p.getString(K_TYPE, null)),
            host = host,
            port = p.getInt(K_PORT, 0),
            username = p.getString(K_USER, "") ?: "",
            password = password,
            dnsServer = p.getString(K_DNS, "1.1.1.1") ?: "1.1.1.1",
        )
    }

    companion object {
        private const val TAG = "vpn"
        private const val NOTIF_ID = 0x0D10
        private const val CHANNEL_ID = "djproxy.tunnel"
        private const val WAKELOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000 // 24h ceiling; re-acquired per session

        const val ACTION_CONNECT = "ai.darshj.djproxy.CONNECT"
        const val ACTION_STOP = "ai.darshj.djproxy.STOP"

        private const val PREFS = "djproxy_vpn"
        private const val K_TYPE = "type"
        private const val K_HOST = "host"
        private const val K_PORT = "port"
        private const val K_USER = "user"
        /** Keystore-encrypted password blob (Base64(iv||ciphertext)); never cleartext. */
        private const val K_PASS_ENC = "pass_enc"
        private const val K_DNS = "dns"
    }
}
