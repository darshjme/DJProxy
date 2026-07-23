package ai.darshj.djproxy.ovpn3

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
import ai.darshj.djproxy.vpn.LogBus
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_EvalConfig
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import net.openvpn.ovpn3.ClientAPI_Status

/**
 * The OpenVPN3 lane's OWN [VpnService]. Distinct from the frozen `DjVpnService` (which is built for the
 * SOCKS5/hev proxy model): here the OpenVPN3 C++ core establishes the app's tun DIRECTLY via its
 * tun_builder callbacks — no local-SOCKS5 hop. One VpnService is active at a time; the ViewModel stops
 * whichever lane is running before starting the other, and both share the single per-app VPN consent.
 *
 * Ownership within this service:
 *  - the ONE `protect()` call site for the OpenVPN3 transport socket (via [protectSocket] → [Ovpn3Client.socket_protect]);
 *  - the ONE `VpnService.Builder` that OpenVPN3's pushed addresses/routes/DNS are translated onto;
 *  - the OpenVPN3 client thread (`ClientAPI_OpenVPNClient.connect()` blocks for the whole session).
 *
 * Leak posture matches DjProxy's TunBuilder: exclude our own package from the tun (so the transport
 * socket egresses direct even on OEMs where per-socket protect is flaky), and let the server's pushed
 * redirect-gateway install the 0.0.0.0/0 capture.
 */
class Ovpn3VpnService : VpnService(), Ovpn3TunHost {

    @Volatile private var client: Ovpn3Client? = null
    @Volatile private var connectThread: Thread? = null

    // Tun state accumulated across tun_builder_* callbacks between tun_builder_new and _establish.
    private var builder: Builder? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var mtu: Int = DEFAULT_MTU
    private var haveAddress = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel("stopped by user"); return START_NOT_STICKY }
            else -> {
                val ovpn = Ovpn3Runtime.pendingOvpn
                if (ovpn.isNullOrBlank()) {
                    LogBus.e(TAG, "connect with no staged profile"); stopSelf(); return START_NOT_STICKY
                }
                enterForeground("Connecting to ${Ovpn3Runtime.pendingLabel}…")
                startClient(ovpn)
            }
        }
        // Not sticky: an OpenVPN3 tunnel must be re-driven by the controller, not silently resurrected
        // by the OS with a profile we may have cleared (the .ovpn is not persisted here).
        return START_NOT_STICKY
    }

    // ---- OpenVPN3 client thread -----------------------------------------------------------------

    private fun startClient(ovpn: String) {
        if (connectThread != null) { LogBus.w(TAG, "client already running"); return }
        Ovpn3Runtime.publish(Ovpn3Phase.CONNECTING)
        publishConnecting()
        val t = Thread({ runClient(ovpn) }, "ovpn3-connect")
        t.isDaemon = true
        connectThread = t
        t.start()
    }

    private fun runClient(ovpn: String) {
        // This openvpn3 rev replaced the static init_process()/uninit_process() with the
        // OpenVPNClientHelper object lifecycle: constructing it initialises the OpenVPN3 process,
        // delete() tears it down. Keep it alive for the whole client session.
        var helper: net.openvpn.ovpn3.ClientAPI_OpenVPNClientHelper? = null
        try {
            System.loadLibrary(NATIVE_LIB)
            helper = net.openvpn.ovpn3.ClientAPI_OpenVPNClientHelper()

            val cfg = ClientAPI_Config().apply {
                content = ovpn                    // the VPN Gate .ovpn passed through UNCHANGED (inline <ca>/<cert>/<key>/tls-auth all honoured by core)
                guiVersion = "DJProxy ${appVersion()}"
                info = true
                tunPersist = false
                autologinSessions = true
                // Let the server negotiate its cipher (NCP) — this is precisely what minivpn lacked.
                // Leaving compressionMode default; VPN Gate profiles that push comp-lzo are handled by core.
            }

            val c = Ovpn3Client(this)
            client = c

            val eval: ClientAPI_EvalConfig = c.eval_config(cfg)
            if (eval.error) {
                fail("profile rejected by OpenVPN3 core: ${eval.message}")
                return
            }

            // VPN Gate public servers accept the well-known throwaway credentials "vpn"/"vpn"; supply
            // them whenever the profile is not fully autologin so an auth-user-pass server still connects.
            if (!eval.autologin) {
                val creds = ClientAPI_ProvideCreds().apply {
                    username = "vpn"
                    password = "vpn"
                }
                val cs: ClientAPI_Status = c.provide_creds(creds)
                if (cs.error) LogBus.w(TAG, "provide_creds warned: ${cs.message}")
            }

            LogBus.i(TAG, "OpenVPN3 connect() starting")
            val status: ClientAPI_Status = c.connect()   // BLOCKS for the whole session
            // connect() returns on disconnect. If it carried an error and we never reached CONNECTED,
            // surface it; a clean stop (user) leaves phase already handled by stopTunnel().
            if (status.error && Ovpn3Runtime.phase.value != Ovpn3Phase.CONNECTED) {
                fail(distillStatus(status))
            } else {
                LogBus.i(TAG, "OpenVPN3 session ended (${status.message})")
            }
        } catch (ule: UnsatisfiedLinkError) {
            fail("the OpenVPN3 native core isn't in this build (${ule.message})")
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { helper?.delete() }
            connectThread = null
        }
    }

    private fun fail(reason: String) {
        LogBus.w(TAG, "OpenVPN3 failed: $reason")
        Ovpn3Runtime.lastFailure = reason
        Ovpn3Runtime.publish(Ovpn3Phase.ERROR)
        publishError(reason)
        stopTunnel("failed: $reason")
    }

    // ---- Ovpn3TunHost (called from the client thread) -------------------------------------------

    override fun tunNew(): Boolean {
        builder = Builder()
        haveAddress = false
        mtu = DEFAULT_MTU
        return true
    }

    override fun tunSetSession(name: String) { builder?.setSession(name) }

    override fun tunSetMtu(mtu: Int) { this.mtu = if (mtu in 576..1500) mtu else DEFAULT_MTU }

    override fun tunSetRemote(address: String, ipv6: Boolean) {
        // Informational only; the endpoint reachability is guaranteed by protectSocket + the
        // self-package exclusion, not by a route (adding a host route here would be redundant).
    }

    override fun tunAddAddress(address: String, prefix: Int, ipv6: Boolean): Boolean = runCatching {
        builder?.addAddress(address, prefix); haveAddress = true; true
    }.getOrElse { LogBus.w(TAG, "addAddress($address/$prefix) rejected: ${it.message}"); false }

    override fun tunRerouteGw(ipv4: Boolean, ipv6: Boolean) {
        // redirect-gateway → full capture. Mirror DjProxy's leak model: also blackhole v6 if the
        // server did not push a v6 address (prevents a v6 leak past a v4-only tunnel).
        runCatching {
            if (ipv4) builder?.addRoute("0.0.0.0", 0)
            // Always install ::/0: if the server redirects v6 it rides the tun; if not, this blackholes
            // v6 so a v4-only tunnel can't leak IPv6 (DjProxy's leak model, TunBuilder.kt vector #2).
            builder?.addRoute("::", 0)
        }.onFailure { LogBus.w(TAG, "reroute_gw route rejected: ${it.message}") }
    }

    override fun tunAddRoute(address: String, prefix: Int, ipv6: Boolean): Boolean = runCatching {
        builder?.addRoute(address, prefix); true
    }.getOrElse { LogBus.w(TAG, "addRoute($address/$prefix) rejected: ${it.message}"); false }

    override fun tunExcludeRoute(address: String, prefix: Int, ipv6: Boolean): Boolean {
        // Builder.excludeRoute(IpPrefix) is API 33+. Below that, a pushed exclude is silently dropped
        // (rare for VPN Gate) — the tunnel still comes up; the excluded subnet just rides the tun.
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                builder?.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(address), prefix))
            }.onFailure { LogBus.w(TAG, "excludeRoute($address/$prefix) rejected: ${it.message}") }
        }
        return true
    }

    override fun tunAddDns(address: String) {
        runCatching { builder?.addDnsServer(address) }
            .onFailure { LogBus.w(TAG, "addDnsServer($address) rejected: ${it.message}") }
    }

    override fun tunAddSearchDomain(domain: String) {
        runCatching { builder?.addSearchDomain(domain) }.getOrNull()
    }

    override fun tunEstablish(): Int {
        val b = builder ?: return -1
        return runCatching {
            b.setMtu(mtu)
            b.setBlocking(true)
            // A ULA v6 address so the ::/0 blackhole route (added in reroute_gw) is VALID on every OEM
            // even when the v4-only VPN Gate server pushed no v6 address — otherwise establish() throws
            // "route not covered by an address". Identical to DjProxy's TunConfig.TUN_V6_ADDRESS.
            runCatching { b.addAddress(ai.darshj.djproxy.vpn.TunConfig.TUN_V6_ADDRESS, ai.darshj.djproxy.vpn.TunConfig.TUN_V6_PREFIX) }
            // Same robust exclusion DjProxy uses: keep OUR OWN sockets (the OpenVPN3 transport) out of
            // the tun regardless of protect() OEM quirks. Every OTHER app is fully captured — no leak.
            runCatching { b.addDisallowedApplication(packageName) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
            // Guard against a profile that pushed no address (would establish an unusable tun).
            if (!haveAddress) { LogBus.w(TAG, "no tun address pushed; refusing establish"); return -1 }
            val pfd = b.establish() ?: run {
                LogBus.e(TAG, "establish() returned null (VPN consent not granted?)")
                return -1
            }
            tunPfd = pfd
            // Hand the OS fd to the core. detachFd() transfers ownership: OpenVPN3 reads/writes and
            // closes it on tun_builder_teardown, so we null our ParcelFileDescriptor to avoid a
            // double-close (we keep only the raw fd path the core now owns).
            val fd = pfd.detachFd()
            tunPfd = null
            LogBus.i(TAG, "tun established for OpenVPN3 (fd=$fd, mtu=$mtu)")
            fd
        }.getOrElse { LogBus.e(TAG, "establish failed: ${it.message}"); -1 }
    }

    override fun tunTeardown() {
        // OpenVPN3 owns the detached fd; nothing to close here. Kept for symmetry / future persist.
        builder = null
    }

    /** THE protect() seam for the OpenVPN3 transport socket. The invariant that stops the self-loop. */
    override fun protectSocket(socket: Int): Boolean = runCatching { protect(socket) }.getOrDefault(false)

    override fun onConnected() {
        Ovpn3Runtime.lastFailure = null
        Ovpn3Runtime.publish(Ovpn3Phase.CONNECTED)
        publishConnected()
        updateNotification("Connected · ${Ovpn3Runtime.pendingLabel}")
    }

    override fun onFatal(reason: String) = fail(reason)

    // ---- teardown -------------------------------------------------------------------------------

    private fun stopTunnel(reason: String) {
        LogBus.i(TAG, "teardown: $reason")
        runCatching { client?.stop() }          // unblocks connect() → thread finishes, uninit_process runs
        client = null
        runCatching { tunPfd?.close() }; tunPfd = null
        builder = null
        if (Ovpn3Runtime.phase.value != Ovpn3Phase.ERROR) {
            Ovpn3Runtime.publish(Ovpn3Phase.IDLE)
            publishIdle()
        }
        runCatching { stopForegroundCompat() }
        stopSelf()
    }

    override fun onRevoke() { LogBus.w(TAG, "onRevoke"); stopTunnel("revoked") }

    override fun onDestroy() { stopTunnel("service destroyed"); super.onDestroy() }

    // ---- VpnRuntime mirror (so the existing status card renders the OpenVPN3 tunnel) -------------
    // These call the PUBLIC VpnRuntime.update {} — core is read/called, never edited.

    private fun publishConnecting() = ai.darshj.djproxy.vpn.VpnRuntime.update {
        it.copy(
            stage = ai.darshj.djproxy.vpn.VpnStage.CONNECTING,
            proxyRedacted = "VPN Gate · ${Ovpn3Runtime.pendingLabel} (OpenVPN)",
            error = null,
        )
    }

    private fun publishConnected() = ai.darshj.djproxy.vpn.VpnRuntime.update {
        it.copy(
            stage = ai.darshj.djproxy.vpn.VpnStage.CONNECTED,
            proxyRedacted = "VPN Gate · ${Ovpn3Runtime.pendingLabel} (OpenVPN)",
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

    private fun distillStatus(status: ClientAPI_Status): String {
        val msg = status.message ?: ""
        val err = status.status ?: ""
        return when {
            err.contains("AUTH_FAILED", true) -> "authentication failed (server rejected credentials)"
            msg.contains("EOF", true) -> "the server dropped the connection during handshake ($msg)"
            msg.isBlank() -> "the OpenVPN handshake failed"
            else -> msg
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")

    // ---- foreground notification ----------------------------------------------------------------

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
            this, 2, Intent(this, Ovpn3VpnService::class.java).setAction(ACTION_STOP),
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
        val ch = NotificationChannel(CHANNEL_ID, "VPN Gate (OpenVPN)", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shows the DJProxy OpenVPN3 tunnel while connected."; setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    companion object {
        private const val TAG = "Ovpn3Svc"
        private const val NOTIF_ID = 0x0D11
        private const val CHANNEL_ID = "djproxy.ovpn3"
        private const val DEFAULT_MTU = 1500

        /** JNI library that carries the SWIG-wrapped OpenVPN3 core. MUST match the built `.so` name
         *  (ics-openvpn's ovpn3 CMake target → `libovpn3.so`). Confirm in the runbook step 6. */
        private const val NATIVE_LIB = "ovpn3"

        const val ACTION_CONNECT = "ai.darshj.djproxy.OVPN3_CONNECT"
        const val ACTION_STOP = "ai.darshj.djproxy.OVPN3_STOP"
    }
}
