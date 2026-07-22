package ai.darshj.djproxy.wireguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.TunConfig
import ovpnsocks.Ovpnsocks

/**
 * The WireGuard lane's DIRECT-TUN [VpnService]. Distinct from the frozen `DjVpnService` (which is the
 * SOCKS5/hev proxy model) and from the SOCKS-mode WG path in [WgEngineController]: here WireGuard-go
 * drives the app's VpnService tun fd DIRECTLY (no hev tun2socks, no local SOCKS5, no gVisor netstack),
 * for a 3-10x faster data path. Adapted from `Ovpn3VpnService` (the other own-VpnService lane).
 *
 * Data path (direct): apps -> tun fd -> wireguard-go -> WARP.   (vs SOCKS mode:
 *   apps -> hev -> local SOCKS5 -> wireguard-go gVisor netstack -> WARP.)
 *
 * Ownership within this service:
 *  - the ONE `VpnService.Builder` that carries the WG interface Address / DNS / routes;
 *  - the tun fd, handed to WireGuard-go via [Ovpnsocks.startWireguardTun] which TAKES OWNERSHIP of it
 *    (reads/writes and closes it on teardown, and closes it itself on a failed start) — so we NEVER
 *    close the ParcelFileDescriptor after `detachFd()`.
 *
 * Leak posture matches DjProxy's TunBuilder: exclude our OWN package from the tun
 * ([addDisallowedApplication]) so WireGuard-go's UDP socket to the endpoint egresses OUTSIDE the tun
 * (there is no `protect()` seam for a socket Go owns natively); every OTHER app is fully captured.
 *
 * Only ONE VpnService is active at a time; the ViewModel tears down the proxy/engine path before
 * starting this, and the OS implicitly revokes any prior VpnService when [establish] runs.
 */
class WgDirectVpnService : VpnService() {

    @Volatile private var connectThread: Thread? = null

    /** True once WireGuard-go owns the fd (after a successful startWireguardTun) — governs teardown. */
    @Volatile private var wgOwnsFd = false

    /** Set on user/OS teardown so the worker thread does not re-report an ERROR for an intentional stop. */
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the FGS obligation IMMEDIATELY. startForegroundService() (used for CONNECT) REQUIRES
        // startForeground() within ~5s or the OS throws ForegroundServiceDidNotStartInTimeException — and
        // a CONNECT can race a STOP (the controller stops siblings before connecting). Calling this first
        // for EVERY action guarantees the obligation is met before any teardown. Guarded so it never
        // itself crashes the service.
        runCatching {
            enterForeground(
                if (intent?.action == ACTION_STOP) "Disconnecting…"
                else "Connecting to ${WgDirectRuntime.pendingLabel}…",
            )
        }
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel("stopped by user"); return START_NOT_STICKY }
            else -> {
                val profile = WgDirectRuntime.pendingProfile
                if (profile == null) {
                    LogBus.e(TAG, "connect with no staged profile"); stopSelf(); return START_NOT_STICKY
                }
                startTunnel(profile)
            }
        }
        // Not sticky: a WG direct tunnel must be re-driven by the controller (the profile is not
        // persisted here), never silently resurrected by the OS with a cleared profile.
        return START_NOT_STICKY
    }

    // ---- bring-up (worker thread; establish + handshake poll must be off the main thread) ---------

    private fun startTunnel(p: WgProfile) {
        if (connectThread != null) { LogBus.w(TAG, "already bringing up"); return }
        stopping = false
        WgDirectRuntime.publish(WgDirectPhase.CONNECTING)
        publishConnecting()
        val t = Thread({ runTunnel(p) }, "wg-direct")
        t.isDaemon = true
        connectThread = t
        t.start()
    }

    private fun runTunnel(p: WgProfile) {
        try {
            val builder = Builder()

            // Interface addresses (v4 and/or v6, CIDR ok, comma list ok).
            var haveV6 = false
            val addrs = parseCidrs(p.address)
            for ((ip, prefix, v6) in addrs) {
                runCatching { builder.addAddress(ip, prefix); if (v6) haveV6 = true }
                    .onFailure { LogBus.w(TAG, "addAddress($ip/$prefix) rejected: ${it.message}") }
            }
            if (addrs.none()) { LogBus.w(TAG, "profile has no interface address; refusing establish"); fail("no interface address in the WireGuard profile"); return }

            // Routes = AllowedIPs. Default to full-tunnel if the profile pinned nothing.
            var routesV6 = false
            val routes = parseCidrs(p.allowedIps)
            if (routes.any()) {
                for ((ip, prefix, v6) in routes) {
                    runCatching { builder.addRoute(ip, prefix); if (v6) routesV6 = true }
                        .onFailure { LogBus.w(TAG, "addRoute($ip/$prefix) rejected: ${it.message}") }
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                routesV6 = true
            }

            // If a v6 route is present but the profile pinned no v6 interface address, add a ULA v6
            // address so establish() does not reject "route not covered by an address" — and so a
            // v4-only WARP profile still blackholes/captures v6 into the tun (no IPv6 leak). Identical
            // to Ovpn3VpnService / DjProxy's TunConfig.TUN_V6_ADDRESS invariant.
            if (routesV6 && !haveV6) {
                runCatching { builder.addAddress(TunConfig.TUN_V6_ADDRESS, TunConfig.TUN_V6_PREFIX) }
                    .onFailure { LogBus.w(TAG, "ULA v6 address rejected: ${it.message}") }
            }

            // DNS (comma list; WgProfile already defaults blank -> 1.1.1.1).
            for (dns in p.dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                runCatching { builder.addDnsServer(dns) }
                    .onFailure { LogBus.w(TAG, "addDnsServer($dns) rejected: ${it.message}") }
            }

            builder.setMtu(MTU)
            builder.setBlocking(true)
            builder.setSession(WgDirectRuntime.pendingLabel)
            // Keep OUR OWN sockets (WireGuard-go's UDP transport) OUT of the tun so it egresses direct.
            // This is the direct-tun equivalent of protect() — Go owns the UDP socket, so per-socket
            // protect() is unavailable; the self-package exclusion is what stops the tunnel self-loop.
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { LogBus.w(TAG, "addDisallowedApplication rejected: ${it.message}") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

            val pfd: ParcelFileDescriptor = builder.establish() ?: run {
                LogBus.e(TAG, "establish() returned null (VPN consent not granted?)")
                fail("VPN consent not granted"); return
            }

            // Hand the OS fd to WireGuard-go. detachFd() transfers ownership; the native engine reads/
            // writes and closes it on stopWireguardTun(), and closes it ITSELF if startWireguardTun
            // throws — so we must NEVER close this fd (no pfd.close(), no Os.close). detach right before
            // the native call so nothing between them can leak it.
            val fd = pfd.detachFd()
            LogBus.i(TAG, "tun established for WireGuard direct (fd=$fd, mtu=$MTU)")
            Ovpnsocks.startWireguardTun(
                fd.toLong(),
                p.privateKey,
                p.peerPublicKey,
                p.presharedKey,
                p.endpoint,
                p.allowedIps,
                KEEPALIVE,
            )
            wgOwnsFd = true // Go now owns fd; stopWireguardTun() will close it.

            if (awaitHandshake()) {
                onConnected()
            } else if (!stopping) {
                fail("handshake did not complete within ${HANDSHAKE_TIMEOUT_MS / 1000}s — the endpoint " +
                    "is unreachable, UDP is blocked, or the key was rejected")
            }
        } catch (t: Throwable) {
            // startWireguardTun throws AND closes the fd itself on failure — do NOT close fd here.
            if (!stopping) fail(t.message?.takeUnless { it.isBlank() } ?: t.javaClass.simpleName)
        } finally {
            connectThread = null
        }
    }

    /** Poll [Ovpnsocks.wireguardTunStatus] until `last_handshake_sec > 0` or the bound elapses. */
    private fun awaitHandshake(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stopping || Thread.currentThread().isInterrupted) return false
            if (handshakeAge(readStatus()) > 0L) return true
            try {
                Thread.sleep(HANDSHAKE_POLL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return handshakeAge(readStatus()) > 0L
    }

    private fun readStatus(): String = runCatching { Ovpnsocks.wireguardTunStatus() }.getOrDefault("")

    /** Parse the `last_handshake_sec=N` line of the status blob; 0 when absent / not yet handshaked. */
    private fun handshakeAge(status: String): Long {
        for (raw in status.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(HANDSHAKE_KEY)) {
                return line.substring(HANDSHAKE_KEY.length).trim().toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    // ---- terminal state --------------------------------------------------------------------------

    private fun onConnected() {
        WgDirectRuntime.lastFailure = null
        WgDirectRuntime.publish(WgDirectPhase.CONNECTED)
        publishConnected()
        updateNotification("Connected · ${WgDirectRuntime.pendingLabel} · WireGuard (direct)")
        LogBus.i(TAG, "CONNECTED — direct-tun WireGuard up (${WgDirectRuntime.pendingLabel})")
    }

    private fun fail(reason: String) {
        LogBus.w(TAG, "WireGuard direct failed: $reason")
        WgDirectRuntime.lastFailure = reason
        WgDirectRuntime.publish(WgDirectPhase.ERROR)
        publishError(reason)
        stopTunnel("failed: $reason")
    }

    private fun stopTunnel(reason: String) {
        LogBus.i(TAG, "teardown: $reason")
        stopping = true
        connectThread?.interrupt()
        connectThread = null
        // Idempotent even if startWireguardTun already tore itself down on a failed start.
        runCatching { Ovpnsocks.stopWireguardTun() }
        wgOwnsFd = false
        // Do not clobber a just-published ERROR with IDLE.
        if (WgDirectRuntime.phase.value != WgDirectPhase.ERROR) {
            WgDirectRuntime.publish(WgDirectPhase.IDLE)
            publishIdle()
        }
        runCatching { stopForegroundCompat() }
        stopSelf()
    }

    override fun onRevoke() { LogBus.w(TAG, "onRevoke"); stopTunnel("revoked") }

    override fun onDestroy() { stopTunnel("service destroyed"); super.onDestroy() }

    // ---- VpnRuntime mirror (so the existing status card renders the direct-tun tunnel) -----------
    // These call the PUBLIC VpnRuntime.update {} — core is read/called, never edited.

    private fun redacted() = "${WgDirectRuntime.pendingLabel} · WireGuard (direct)"

    private fun publishConnecting() = ai.darshj.djproxy.vpn.VpnRuntime.update {
        it.copy(
            stage = ai.darshj.djproxy.vpn.VpnStage.CONNECTING,
            proxyRedacted = redacted(),
            error = null,
        )
    }

    private fun publishConnected() = ai.darshj.djproxy.vpn.VpnRuntime.update {
        it.copy(
            stage = ai.darshj.djproxy.vpn.VpnStage.CONNECTED,
            proxyRedacted = redacted(),
            connectedSinceMs = System.currentTimeMillis(),
            error = null,
        )
    }

    private fun publishError(reason: String) = ai.darshj.djproxy.vpn.VpnRuntime.update {
        it.copy(
            stage = ai.darshj.djproxy.vpn.VpnStage.ERROR,
            error = ai.darshj.djproxy.proxy.ProxyError.Io(reason),
            connectedSinceMs = 0,
        )
    }

    private fun publishIdle() = ai.darshj.djproxy.vpn.VpnRuntime.update { ai.darshj.djproxy.vpn.VpnState.IDLE }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Parse a comma-separated list of `ip[/prefix]` entries into (ip, prefix, isV6) triples. Used for
     * both interface addresses and AllowedIPs/routes. Default prefix is /32 (v4) or /128 (v6).
     */
    private fun parseCidrs(spec: String): List<Triple<String, Int, Boolean>> =
        spec.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val slash = entry.indexOf('/')
                val ip = (if (slash >= 0) entry.substring(0, slash) else entry).trim()
                if (ip.isEmpty()) return@mapNotNull null
                val v6 = ip.contains(':')
                val prefix = if (slash >= 0) {
                    entry.substring(slash + 1).trim().toIntOrNull() ?: (if (v6) 128 else 32)
                } else {
                    if (v6) 128 else 32
                }
                Triple(ip, prefix, v6)
            }

    // ---- foreground notification -----------------------------------------------------------------

    private fun enterForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 3, Intent(this, WgDirectVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("DJProxy")
            .setContentText(text)
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(@Suppress("DEPRECATION") Notification.Action.Builder(0, "Disconnect", stop).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL_ID, "WireGuard (direct)", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shows the DJProxy WireGuard direct-tun tunnel while connected."; setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    companion object {
        private const val TAG = "WgDirectSvc"
        private const val NOTIF_ID = 0x0D12
        private const val CHANNEL_ID = "djproxy.wgdirect"

        /** WireGuard's standard tunnel MTU (accounts for the 60-80 byte WG/UDP/IP overhead). */
        private const val MTU = 1420
        private const val KEEPALIVE = 25L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val HANDSHAKE_POLL_MS = 500L
        private const val HANDSHAKE_KEY = "last_handshake_sec="

        const val ACTION_CONNECT = "ai.darshj.djproxy.WG_DIRECT_CONNECT"
        const val ACTION_STOP = "ai.darshj.djproxy.WG_DIRECT_STOP"
    }
}
