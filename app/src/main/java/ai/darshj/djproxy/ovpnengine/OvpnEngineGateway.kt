package ai.darshj.djproxy.ovpnengine

/**
 * Process-global holder for the ovpnengine lane (same divergence the tor/vpngate lanes take, since
 * core's `FeatureRegistry` may not be edited to add a slot). The ui/ViewModel read [controller] to
 * start/stop the userspace OpenVPN engine for a VPN Gate "Connect in DJProxy" action. Null = the lane
 * is absent (e.g. the `.aar` was stripped), and the in-app Connect path is simply unavailable.
 *
 * Set once by [OvpnEngineRegistrar] before any Activity/Service; a plain `@Volatile` write suffices.
 */
object OvpnEngineGateway {
    @Volatile
    var controller: OvpnEngineController? = null
}
