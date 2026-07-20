package ai.darshj.djproxy.adblock

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogBus

/**
 * The adblock lane's single wiring point (mirror of `tor/TorRegistrar` / `location/LocationRegistrar`) —
 * an androidx.startup [Initializer] the **platform** lane references from the manifest's
 * `InitializationProvider` `<meta-data>`. androidx.startup constructs it once per process, before any
 * Activity/Service, and it:
 *   1. loads the shared [Blocklist] from `assets/adblock/hosts.txt` and builds the [AdblockController],
 *   2. installs the CONNECT-time sinkhole by SETTING the core seam `FeatureRegistry.blockedHostPredicate`
 *      to `{ controller.isBlockingEnabled() && blocklist.isBlocked(it) }` (core READS it in
 *      `LocalSocksServer`; null default = fail-open / byte-identical to today),
 *   3. wraps `FeatureRegistry.dnsResolverFactory` with [BlocklistDnsResolver] as belt-and-suspenders
 *      for the legacy MapDNS-off path (fires zero times in the default MapDNS-on build),
 *   4. publishes the controller into the lane's own [AdblockGateway] holder (the ui reads it; null →
 *      the Block-ads toggle is hidden), and
 *   5. contributes [AdblockSettingsPanel] via the core `FeatureRegistry.addSettingsPanel` seam.
 *
 * No core file is edited HERE — the lane only SETS the core holders. (The two-line frozen-core seam
 * this relies on — the `blockedHostPredicate` holder in `FeatureRegistry` and its consult in
 * `LocalSocksServer` — is a `core_seam_request` applied by the human lead.) Idempotent: `installed`
 * guards a re-run, `addSettingsPanel` de-dups by id, and every holder write is a plain volatile assign.
 */
class AdblockRegistrar : Initializer<AdblockController> {

    override fun create(context: Context): AdblockController = attach(context.applicationContext)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        @Volatile
        private var installed: AdblockController? = null

        @Synchronized
        fun attach(appContext: Context): AdblockController {
            installed?.let { return it }

            val blocklist = runCatching {
                appContext.assets.open("adblock/hosts.txt").use { Blocklist.load(it) }
            }.getOrElse {
                LogBus.w("Adblock", "hosts.txt failed to load (${it.message}); adblock inert this run")
                Blocklist.of() // empty → isBlocked() is always false, fail-open
            }

            val controller = AdblockController(appContext, blocklist)

            // (1) DEFAULT PATH — CONNECT-time sinkhole. Core reads this holder in LocalSocksServer,
            // wrapped in runCatching, so a lane fault can never break the SOCKS front. A single cheap
            // volatile check when disabled; the HashSet lookup only runs while enabled.
            FeatureRegistry.blockedHostPredicate = { host ->
                controller.isBlockingEnabled() && blocklist.isBlocked(host)
            }

            // (2) LEGACY PATH — decorate the DNS factory. No-op in the shipping MapDNS-on build.
            val previousFactory = FeatureRegistry.dnsResolverFactory
            FeatureRegistry.dnsResolverFactory = { cfg, dialer ->
                BlocklistDnsResolver(
                    delegate = previousFactory(cfg, dialer),
                    blocklist = blocklist,
                    isEnabled = { controller.isBlockingEnabled() },
                )
            }

            AdblockGateway.controller = controller
            FeatureRegistry.addSettingsPanel(AdblockSettingsPanel(controller))
            installed = controller
            LogBus.i("Adblock", "Adblock lane registered (${blocklist.size} domains, CONNECT-time sinkhole, default off)")
            return controller
        }
    }
}
