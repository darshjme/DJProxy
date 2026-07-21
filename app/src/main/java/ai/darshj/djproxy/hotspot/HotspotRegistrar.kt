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
        // Cold-init hardening: this runs in InitializationProvider BEFORE Application.onCreate, so a
        // throw crashes the process before any UI (a "crash instantly on tapping the icon" with no
        // report). HotspotControllerImpl builds its capability snapshot at construction, which queries
        // system services that can be absent/stubbed on some emulators — so the whole attach is
        // guarded. On failure the hotspot lane is simply absent this run (its Gateway stays null → the
        // feature is hidden) and the app still launches.
        runCatching { attach(context.applicationContext) }
            .onFailure { LogBus.e("Hotspot", "lane init skipped (device init fault): ${it.message}") }
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
            // Reconcile any stale root redirect left by a crashed/force-stopped prior session (no-op on
            // unrooted devices; runs off-thread and never throws).
            runCatching { RootRedirector().reconcileStaleRules() }
            LogBus.i("Hotspot", "Hotspot lane registered (LAN proxy + root transparent)")
        }
    }
}
