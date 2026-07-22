package ai.darshj.djproxy.wireguard

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.vpn.LogBus

/**
 * The WireGuard lane's single wiring point — an androidx.startup [Initializer] (clone of the
 * ovpnengine/tor registrars). The platform lane references it from the manifest's
 * InitializationProvider `<meta-data>`; androidx.startup constructs it once per process before any
 * Activity/Service, builds the [WgEngineController], and publishes it into [WgEngineGateway].
 *
 * GUARDED: runs in InitializationProvider BEFORE Application.onCreate, so a fault here must not crash
 * the process cold (see the cold-init hardening across all lane registrars). Controller construction
 * is pure state; the native gomobile library loads lazily on the first connect.
 */
class WgEngineRegistrar : Initializer<WgEngineController> {

    override fun create(context: Context): WgEngineController {
        val controller = WgEngineController(context.applicationContext)
        runCatching {
            WgEngineGateway.controller = controller
            LogBus.i("WgEngine", "WireGuard engine lane registered (WARP + custom WG → local SOCKS5)")
        }.onFailure { LogBus.w("WgEngine", "lane publish skipped: ${it.message}") }
        return controller
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
