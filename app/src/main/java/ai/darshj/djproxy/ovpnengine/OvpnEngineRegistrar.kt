package ai.darshj.djproxy.ovpnengine

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.vpn.LogBus

/**
 * The ovpnengine lane's single wiring point — an androidx.startup [Initializer] (verbatim clone of the
 * tor/vpngate registrars). The platform lane references it from the manifest's InitializationProvider
 * `<meta-data>`; androidx.startup constructs it once per process before any Activity/Service, builds
 * the [OvpnEngineController], and publishes it into [OvpnEngineGateway]. No core file is edited.
 */
class OvpnEngineRegistrar : Initializer<OvpnEngineController> {

    override fun create(context: Context): OvpnEngineController {
        val controller = OvpnEngineController(context.applicationContext)
        OvpnEngineGateway.controller = controller
        LogBus.i("OvpnEngine", "OpenVPN engine lane registered (userspace minivpn → local SOCKS5; VPN Gate as a proxy)")
        return controller
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
