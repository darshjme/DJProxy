package ai.darshj.djproxy.location

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.ui.LocationPreference
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.seams.LocationController

/**
 * The location lane's single wiring point (§9.1). An androidx.startup [Initializer] that the compat
 * lane references from the manifest's `InitializationProvider` `<meta-data>`; androidx.startup then
 * constructs it once per process, before any Activity/Service, and we register our
 * [LocationController] + settings panel into the process-global [FeatureRegistry]. No core file is
 * edited — this is the ONLY point where the lane attaches.
 *
 * Idempotent: [FeatureRegistry.addSettingsPanel] de-dups by id, and setting the controller holder is
 * a plain volatile write, so a re-run (or a second process) cannot double-register.
 */
class LocationRegistrar : Initializer<LocationController> {

    override fun create(context: Context): LocationController = attach(context.applicationContext)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        @Volatile
        private var installed: LocationControllerImpl? = null

        /** Idempotently attaches the location controller + settings panel to the FeatureRegistry. */
        @Synchronized
        fun attach(appContext: Context): LocationController {
            installed?.let { return it }
            val engine = MockLocationEngine(appContext)
            val controller = LocationControllerImpl(
                engine = engine,
                optedIn = { LocationPreference.isEnabled(appContext) },
                capabilityProvider = { LocationCapabilityDetector.detect(appContext) },
            )
            FeatureRegistry.locationController = controller
            FeatureRegistry.addSettingsPanel(LocationSettingsPanel(controller, appContext))
            // Publish an honest capability snapshot immediately so settings is correct on first open.
            // GUARDED: this queries LocationManager / Settings.Secure, which some emulators (LDPlayer &
            // co.) stub or back with a modified provider that can throw. Since this Initializer runs in
            // InitializationProvider BEFORE Application.onCreate, an unguarded throw here crashes the
            // process cold (before any UI). A capability read failing is non-fatal — the snapshot just
            // refreshes on first settings open instead.
            runCatching { controller.refreshCapability(appContext) }
                .onFailure { LogBus.w("Location", "initial capability probe skipped: ${it.message}") }
            installed = controller
            LogBus.i("Location", "Location lane registered (exit-geo + mock providers)")
            return controller
        }
    }
}
