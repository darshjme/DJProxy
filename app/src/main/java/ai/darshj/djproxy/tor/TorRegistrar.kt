package ai.darshj.djproxy.tor

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogBus

/**
 * The tor lane's single wiring point (SSOT §8 / §0.2) — an androidx.startup [Initializer], a verbatim
 * clone of `LocationRegistrar`. The **platform** lane references it from the manifest's
 * `InitializationProvider` `<meta-data>`; androidx.startup constructs it once per process, before any
 * Activity/Service, and it:
 *   1. builds the real [GuardianOnionProxyManager] + [TorControllerImpl] (whose foreground hook drives
 *      the branded [TorService]),
 *   2. publishes the controller into the lane's own [TorGateway] holder (the ui reads it; null → the
 *      Tor toggle is hidden), and
 *   3. contributes [TorSettingsPanel] via the core `FeatureRegistry.addSettingsPanel` seam.
 *
 * No core file is edited — this is the ONLY point where the lane attaches. Idempotent:
 * [FeatureRegistry.addSettingsPanel] de-dups by id and the holder write is a plain volatile assignment,
 * so a re-run (or a second process) cannot double-register.
 */
class TorRegistrar : Initializer<TorController> {

    override fun create(context: Context): TorController = attach(context.applicationContext)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        @Volatile
        private var installed: TorController? = null

        @Synchronized
        fun attach(appContext: Context): TorController {
            installed?.let { return it }
            val manager = GuardianOnionProxyManager(appContext)
            val controller = TorControllerImpl(
                manager = manager,
                foreground = { on -> if (on) TorService.start(appContext) else TorService.stop(appContext) },
            )
            // GUARDED: attach runs in InitializationProvider before Application.onCreate; a holder/panel
            // wiring fault must not crash the process cold. Controller construction above is pure refs
            // (no native load — libtor.so loads only when the Tor service actually starts).
            runCatching {
                TorGateway.controller = controller
                FeatureRegistry.addSettingsPanel(TorSettingsPanel(controller))
            }.onFailure { LogBus.w("Tor", "lane publish skipped: ${it.message}") }
            installed = controller
            LogBus.i("Tor", "Tor lane registered (embedded tor-android, loopback SOCKS5 127.0.0.1:9050)")
            return controller
        }
    }
}
