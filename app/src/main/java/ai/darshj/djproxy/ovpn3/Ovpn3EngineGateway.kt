package ai.darshj.djproxy.ovpn3

/**
 * Process-global holder for the ovpn3 lane (same divergence the tor / vpngate / ovpnengine lanes take,
 * since core's `FeatureRegistry` may not be edited to add a slot). The ui/ViewModel read [controller] to
 * start/stop the OpenVPN3 tunnel for a VPN Gate "Connect in DJProxy" action. Null = the lane is absent
 * (e.g. the native `libovpn3.so` / SWIG classes were stripped from this build), and the in-app OpenVPN3
 * Connect path is simply unavailable — the ui then falls back to the "open in an external OpenVPN app"
 * hand-off.
 *
 * Set once by [Ovpn3EngineRegistrar] before any Activity/Service; a plain `@Volatile` write suffices.
 */
object Ovpn3EngineGateway {
    @Volatile
    var controller: Ovpn3EngineController? = null
}
