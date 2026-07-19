package ai.darshj.djproxy.hotspot

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogBus

/**
 * The hotspot lane's single wiring point (§9.1). An androidx.startup [Initializer] that compat wires
 * via `<meta-data>` under `InitializationProvider`; on process start it attaches the concrete
 * [HotspotControllerImpl] to [FeatureRegistry] and contributes the lane's settings panel. Core reads
 * the holder wrapped in runCatching, so a fault here can never break VPN bring-up.
 *
 * The registration logic lives in [attach] so it is reusable/testable independent of the startup
 * framework.
 */
class HotspotRegistrar : Initializer<Unit> {

    override fun create(context: Context) {
        attach(context.applicationContext)
    }

    // No dependency on other Initializers; the DNS default etc. are already core-shipped.
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        @Volatile
        private var installed = false

        /** Idempotently attaches the hotspot controller + settings panel to the FeatureRegistry. */
        @Synchronized
        fun attach(appContext: Context) {
            if (installed) return
            val controller = HotspotControllerImpl(appContext)
            FeatureRegistry.hotspotController = controller
            FeatureRegistry.addSettingsPanel(HotspotSettingsPanel(controller))
            installed = true
            LogBus.i("Hotspot", "Hotspot lane registered (LAN proxy + root transparent)")
        }
    }
}
