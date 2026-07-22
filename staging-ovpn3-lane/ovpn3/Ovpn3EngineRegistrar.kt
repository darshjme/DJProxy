package ai.darshj.djproxy.ovpn3

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.vpn.LogBus

/**
 * The ovpn3 lane's single wiring point — an androidx.startup [Initializer] (verbatim clone of the
 * tor / vpngate / ovpnengine registrars). The platform references it from the manifest's
 * InitializationProvider `<meta-data>`; androidx.startup constructs it once per process before any
 * Activity/Service, builds the [Ovpn3EngineController], and publishes it into [Ovpn3EngineGateway].
 * No core file is edited.
 */
class Ovpn3EngineRegistrar : Initializer<Ovpn3EngineController> {

    override fun create(context: Context): Ovpn3EngineController {
        // Runs in InitializationProvider BEFORE Application.onCreate — a throw here would crash the
        // process cold (before any UI), so the publish is guarded. Controller construction is pure
        // state (flow holders); the native core (libovpn3.so) is NOT loaded here — it loads lazily on
        // the first connect, inside Ovpn3VpnService, so a build without the .so still boots and simply
        // reports the lane unavailable at connect time.
        val controller = Ovpn3EngineController(context.applicationContext)
        runCatching {
            Ovpn3EngineGateway.controller = controller
            LogBus.i("Ovpn3Engine", "OpenVPN3 engine lane registered (native core establishes the tun directly; VPN Gate device-wide)")
        }.onFailure { LogBus.w("Ovpn3Engine", "lane publish skipped: ${it.message}") }
        return controller
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
