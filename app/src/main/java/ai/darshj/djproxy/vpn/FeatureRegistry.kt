package ai.darshj.djproxy.vpn

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.dns.CompositeDnsResolver
import ai.darshj.djproxy.dns.DnsResolver
import ai.darshj.djproxy.proxy.UpstreamDialer
import ai.darshj.djproxy.vpn.seams.CriticalFailureSink
import ai.darshj.djproxy.vpn.seams.HotspotController
import ai.darshj.djproxy.vpn.seams.LocationController
import ai.darshj.djproxy.vpn.seams.SettingsPanel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ONE wiring point feature lanes touch (§9.1). Process-global nullable holders that feature
 * lanes set from their androidx.startup Initializers (compat wires the `<meta-data>`); core reads
 * them and always invokes them wrapped in runCatching so a lane fault can never break bring-up.
 *
 * Core ships a WORKING default for [dnsResolverFactory] (the composite DoH▸DoT▸TCP53 resolver), so
 * DNS resolves through the proxy exit even with no feature lane present. Everything else defaults to
 * null and simply does nothing until its lane attaches.
 */
object FeatureRegistry {

    /** Core default = CompositeDnsResolver[DoH:443, DoT:853, TCP:53]. Overridable for tests/custom. */
    @Volatile
    var dnsResolverFactory: (ProxyConfig, UpstreamDialer) -> DnsResolver =
        { cfg, dialer -> CompositeDnsResolver.default(cfg, dialer) }

    @Volatile
    var locationController: LocationController? = null // set by location lane

    @Volatile
    var hotspotController: HotspotController? = null   // set by hotspot lane

    @Volatile
    var criticalFailureSink: CriticalFailureSink? = null // set by diagnostics lane

    /** Feature lanes contribute settings UI without editing ui/: the ui host renders these. */
    val settingsPanels: MutableList<SettingsPanel> = CopyOnWriteArrayList()

    fun addSettingsPanel(p: SettingsPanel) {
        // De-dup by id so a re-run Initializer cannot double-register a panel.
        if (settingsPanels.none { it.id == p.id }) settingsPanels.add(p)
    }

    /** Reports a critical failure to the diagnostics sink; never throws (§9.7 invariant 1). */
    fun reportCritical(failure: ai.darshj.djproxy.vpn.seams.CriticalFailure) {
        runCatching { criticalFailureSink?.onCriticalFailure(failure) }
    }
}
