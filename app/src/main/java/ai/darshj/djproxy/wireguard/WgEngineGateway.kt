package ai.darshj.djproxy.wireguard

/**
 * Process-global holder for the WireGuard lane's controller (mirror of
 * [ai.darshj.djproxy.ovpnengine.OvpnEngineGateway]). The ui/ViewModel read [controller] to drive a
 * WARP / WireGuard connect; null → the WireGuard routes are hidden. Set once by [WgEngineRegistrar]
 * (a core file is never edited to add a slot).
 */
object WgEngineGateway {
    @Volatile
    var controller: WgEngineController? = null
}
