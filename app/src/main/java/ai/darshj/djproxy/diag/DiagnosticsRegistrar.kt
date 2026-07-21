package ai.darshj.djproxy.diag

import ai.darshj.djproxy.vpn.FeatureRegistry
import android.content.Context
import androidx.startup.Initializer

/**
 * The single wiring point (§9.1): attaches this lane's [DiagnosticsSink] and settings panel into the
 * process-global [FeatureRegistry] — WITHOUT editing any core/ui/compat file. Registration runs via
 * androidx.startup; the compat lane contributes the `<meta-data>` under `InitializationProvider` in
 * the manifest, and this class does the actual attach. Runs once, in the main process, before any
 * Activity/Service — so a critical failure raised during early bring-up already has a sink.
 *
 * Idempotent: [FeatureRegistry.addSettingsPanel] de-dups by id and setting the sink is a plain
 * assignment, so a re-run Initializer cannot double-register.
 */
class DiagnosticsRegistrar : Initializer<Unit> {

    override fun create(context: Context) {
        // Runs in InitializationProvider before Application.onCreate — guard so a sink/panel wiring
        // fault cannot crash the process cold.
        runCatching { register(context.applicationContext) }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        /**
         * The real attach, exposed so it can also be invoked directly (e.g. from a test or a compat
         * bootstrap) independently of androidx.startup.
         */
        fun register(context: Context) {
            val app = context.applicationContext
            FeatureRegistry.criticalFailureSink = DiagnosticsSink(app)
            FeatureRegistry.addSettingsPanel(DiagnosticsSettingsPanel())
        }
    }
}
