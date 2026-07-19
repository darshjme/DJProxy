package ai.darshj.djproxy.dns

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.proxy.UpstreamDialer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates an ORDERED list of transports `[DoH:443, DoT:853, TCP:53]` (§3.3). `resolve` tries the
 * current head; on null it advances to the next and remembers the first that works as the new head
 * (sticky), so we do not re-pay a failing transport's timeout on every query. This directly
 * implements the design's "DNS-mode fallback": when the primary path is blocked by a residential
 * exit, subsequent queries stick to the transport that survived.
 *
 * Never throws — an all-fail returns null.
 */
class CompositeDnsResolver(
    private val resolvers: List<DnsResolver>,
) : DnsResolver {

    /** Index of the current sticky head; advanced on failure, reset toward 0 is intentional-free. */
    private val head = AtomicInteger(0)

    init { require(resolvers.isNotEmpty()) { "CompositeDnsResolver needs at least one resolver" } }

    /** The label of the transport currently serving (for HealthReport.activeDnsStrategy). */
    override val label: String
        get() = resolvers[head.get().coerceIn(0, resolvers.lastIndex)].label

    /** Alias used by HealthReport wiring. */
    val currentLabel: String get() = label

    override suspend fun resolve(query: ByteArray): ByteArray? {
        val start = head.get().coerceIn(0, resolvers.lastIndex)
        // Try from the sticky head, then wrap to earlier transports (a previously-degraded primary
        // may have recovered on a network change). One full pass, no throw.
        for (offset in resolvers.indices) {
            val idx = (start + offset) % resolvers.size
            val answer = resolvers[idx].resolve(query)
            if (answer != null) {
                head.set(idx) // stick to the winner
                return answer
            }
        }
        return null
    }

    companion object {
        /**
         * The core default: DoH:443 primary, DoT:853 then plain TCP:53 fallbacks — all resolving at
         * the proxy exit through [dialer]. This is what [ai.darshj.djproxy.vpn.FeatureRegistry]
         * ships so no lane is required to wire DNS.
         */
        fun default(config: ProxyConfig, dialer: UpstreamDialer): CompositeDnsResolver =
            CompositeDnsResolver(
                listOf(
                    DohResolver(dialer),
                    DotResolver(dialer),
                    DnsOverTcpResolver(dialer, config.dnsServer),
                ),
            )
    }
}
