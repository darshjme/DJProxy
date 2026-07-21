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
        // Runs in InitializationProvider BEFORE Application.onCreate — a throw here would crash the
        // process cold (before any UI), so the publish is guarded. OvpnEngineController construction is
        // pure state (flow holders); the native gomobile library (libgojni.so) is NOT loaded here — it
        // loads lazily on the first Ovpnsocks.start() when the user actually connects a VPN Gate server.
        val controller = OvpnEngineController(context.applicationContext)
        runCatching {
            OvpnEngineGateway.controller = controller
            LogBus.i("OvpnEngine", "OpenVPN engine lane registered (userspace minivpn → local SOCKS5; VPN Gate as a proxy)")
        }.onFailure { LogBus.w("OvpnEngine", "lane publish skipped: ${it.message}") }
        return controller
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
