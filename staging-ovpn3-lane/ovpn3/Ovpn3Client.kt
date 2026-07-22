package ai.darshj.djproxy.ovpn3

import ai.darshj.djproxy.vpn.LogBus
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient

/**
 * DJProxy's subclass of the OpenVPN3 core client (`net.openvpn.ovpn3.ClientAPI_OpenVPNClient`, the
 * SWIG director generated from `client/ovpncli.hpp`). This is the seam ics-openvpn implements in
 * `OpenVPNThreadv3`; we implement the same virtual methods but forward the tun_builder + socket_protect
 * callbacks straight onto DJProxy's [Ovpn3VpnService] (a real VpnService) — so the OpenVPN3 core
 * establishes the app's tun directly, with no local-SOCKS5 indirection.
 *
 * CRITICAL INVARIANT: [socket_protect] MUST return [Ovpn3VpnService.protectSocket] (= VpnService.protect).
 * The OpenVPN3 transport UDP/TCP socket to the VPN Gate server is opened INSIDE this app; without a
 * successful protect it would be captured by our own tun and the tunnel self-loops (the exact
 * "vpn connect: EOF"-class dead end). DjProxy also excludes its own package from the tun as a second
 * belt-and-braces guard (see [Ovpn3VpnService]).
 *
 * The tun_builder_* calls arrive in a strict order per connection: `tun_builder_new()` → a run of
 * add_address / add_route / add_dns_server / set_mtu / set_session_name / reroute_gw → a single
 * `tun_builder_establish()` that must return the OS tun fd. We accumulate them onto a
 * VpnService.Builder held by the service and call `establish()` at the end.
 *
 * NOTE ON SWIG SIGNATURES: the exact Java signatures below MUST match the SWIG output of the vendored
 * core (they are the stable OpenVPN3 ClientAPI ABI). If the regenerated wrapper differs (e.g. an older
 * `socket_protect(int)` 1-arg form), align these overrides to the generated `ClientAPI_OpenVPNClient`
 * — the runbook calls this out. All names/arities here match current ics-openvpn `master`.
 */
class Ovpn3Client(private val host: Ovpn3TunHost) : ClientAPI_OpenVPNClient() {

    // ---- lifecycle events -----------------------------------------------------------------------

    override fun event(ev: ClientAPI_Event) {
        val name = ev.name ?: ""
        val info = ev.info ?: ""
        if (ev.fatal) {
            LogBus.w(TAG, "FATAL event $name: $info")
            host.onFatal(distill(name, info))
            return
        }
        when (name) {
            "CONNECTED" -> {
                LogBus.i(TAG, "OpenVPN3 CONNECTED ($info)")
                host.onConnected()
            }
            "RECONNECTING" -> LogBus.i(TAG, "OpenVPN3 reconnecting: $info")
            "AUTH_FAILED" -> host.onFatal("authentication failed (the server rejected the credentials)")
            "CONNECTING", "RESOLVE", "WAIT", "GET_CONFIG", "ASSIGN_IP" ->
                LogBus.i(TAG, "OpenVPN3 $name $info")
            "DISCONNECTED" -> LogBus.i(TAG, "OpenVPN3 disconnected")
            else -> LogBus.i(TAG, "OpenVPN3 event $name $info")
        }
    }

    override fun log(log: ClientAPI_LogInfo) {
        // OpenVPN3 core logs are verbose; keep them at debug so release builds stay quiet.
        log.text?.trimEnd()?.let { if (it.isNotEmpty()) LogBus.d(TAG, it) }
    }

    /** Called by the core on an idle/handshake timeout. Returning false = give up (don't pause). */
    override fun pause_on_connection_timeout(): Boolean = false

    // ---- tun builder (translate OpenVPN3 pushed config onto VpnService.Builder) ------------------

    override fun tun_builder_new(): Boolean = host.tunNew()

    override fun tun_builder_set_session_name(name: String?): Boolean {
        host.tunSetSession(name ?: "DJProxy VPN Gate")
        return true
    }

    override fun tun_builder_set_mtu(mtu: Int): Boolean {
        host.tunSetMtu(mtu)
        return true
    }

    override fun tun_builder_set_remote_address(address: String?, ipv6: Boolean): Boolean {
        // Informational: the concrete server endpoint. Kept so the notification can show it; the
        // transport socket protect (below) is what actually keeps this endpoint reachable.
        if (address != null) host.tunSetRemote(address, ipv6)
        return true
    }

    override fun tun_builder_add_address(
        address: String?,
        prefixLength: Int,
        gateway: String?,
        ipv6: Boolean,
        net30: Boolean,
    ): Boolean {
        if (address == null) return false
        return host.tunAddAddress(address, prefixLength, ipv6)
    }

    override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
        // redirect-gateway: capture the default route(s). VpnService has no separate "default gw"
        // concept — a 0.0.0.0/0 (and ::/0) route IS the capture, so translate directly.
        host.tunRerouteGw(ipv4, ipv6)
        return true
    }

    override fun tun_builder_add_route(
        address: String?,
        prefixLength: Int,
        metric: Int,
        ipv6: Boolean,
    ): Boolean {
        if (address == null) return false
        return host.tunAddRoute(address, prefixLength, ipv6)
    }

    override fun tun_builder_exclude_route(
        address: String?,
        prefixLength: Int,
        metric: Int,
        ipv6: Boolean,
    ): Boolean {
        if (address == null) return true
        return host.tunExcludeRoute(address, prefixLength, ipv6)
    }

    override fun tun_builder_add_dns_server(address: String?, ipv6: Boolean): Boolean {
        if (address == null) return false
        return host.tunAddDns(address)
    }

    override fun tun_builder_add_search_domain(domain: String?): Boolean {
        if (domain != null) host.tunAddSearchDomain(domain)
        return true
    }

    /** The core asks us to open the tun and hand it the OS fd. Return -1 on failure (core aborts). */
    override fun tun_builder_establish(): Int = host.tunEstablish()

    override fun tun_builder_persist(): Boolean = false

    override fun tun_builder_teardown(disconnect: Boolean) {
        host.tunTeardown()
    }

    /**
     * THE invariant. The core opened a transport socket to the VPN Gate server and asks us to keep it
     * OUT of the tun. Delegate to VpnService.protect(); a false return here means a self-loop, so the
     * core (correctly) treats it as fatal.
     */
    override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean): Boolean =
        host.protectSocket(socket)

    private fun distill(name: String, info: String): String {
        val base = if (info.isNotBlank()) "$name: $info" else name
        return when {
            name.contains("AUTH_FAILED", true) -> "authentication failed (server rejected credentials)"
            name.contains("CONNECTION_TIMEOUT", true) -> "connection timed out (server unreachable / UDP blocked)"
            name.contains("TUN_", true) -> "could not build the local tunnel interface ($base)"
            name.contains("CLIENT_HALT", true) -> "the server halted the session ($info)"
            name.contains("TLS", true) -> "TLS handshake failed ($info)"
            else -> base
        }
    }

    companion object {
        private const val TAG = "Ovpn3"
    }
}

/**
 * The callback surface [Ovpn3Client] drives — implemented by [Ovpn3VpnService]. Split into an
 * interface so the client stays free of Android types and unit-testable, and so the service owns the
 * single VpnService.Builder / establish() / protect() call sites.
 */
interface Ovpn3TunHost {
    fun tunNew(): Boolean
    fun tunSetSession(name: String)
    fun tunSetMtu(mtu: Int)
    fun tunSetRemote(address: String, ipv6: Boolean)
    fun tunAddAddress(address: String, prefix: Int, ipv6: Boolean): Boolean
    fun tunRerouteGw(ipv4: Boolean, ipv6: Boolean)
    fun tunAddRoute(address: String, prefix: Int, ipv6: Boolean): Boolean
    fun tunExcludeRoute(address: String, prefix: Int, ipv6: Boolean): Boolean
    fun tunAddDns(address: String)
    fun tunAddSearchDomain(domain: String)
    fun tunEstablish(): Int
    fun tunTeardown()
    fun protectSocket(socket: Int): Boolean
    fun onConnected()
    fun onFatal(reason: String)
}
