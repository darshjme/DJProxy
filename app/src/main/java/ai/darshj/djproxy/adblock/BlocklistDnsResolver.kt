package ai.darshj.djproxy.adblock

import ai.darshj.djproxy.dns.DnsMessage
import ai.darshj.djproxy.dns.DnsResolver

/**
 * The adblock lane's LEGACY-path seam: a [DnsResolver] decorator that the lane's Registrar wraps
 * around the core default (DoH▸DoT▸TCP53). When adblock is on and the queried name is on the
 * [Blocklist], it short-circuits with a synthetic NXDOMAIN answer instead of resolving upstream;
 * otherwise it delegates untouched.
 *
 * ── HONEST scope (do not oversell this) ───────────────────────────────────────────────────────────
 * In the SHIPPING build `mapDns=true`, so hev answers DNS with a synthetic fake-IP inside the tun and
 * the real domain never enters Kotlin as a DNS query — it reappears only as the SOCKS `ATYP_DOMAIN`
 * in a CONNECT. That path is covered by the CONNECT-time sinkhole in `LocalSocksServer` (the core
 * seam). This decorator therefore fires ZERO times in the default build; it is belt-and-suspenders
 * ONLY for the legacy MapDNS-off configuration, where DNS queries do flow through Kotlin. It reads the
 * SAME [blocklist] and the SAME [isEnabled] flag as the CONNECT seam, so there is a single truth.
 *
 * Contract (inherited from [DnsResolver]): MUST NOT throw. Any parse hiccup falls through to the real
 * [delegate] — fail-open, never break resolution because of a blocklist miss.
 */
class BlocklistDnsResolver(
    private val delegate: DnsResolver,
    private val blocklist: Blocklist,
    private val isEnabled: () -> Boolean,
) : DnsResolver {

    override val label: String get() = delegate.label

    override suspend fun resolve(query: ByteArray): ByteArray? {
        if (isEnabled()) {
            val name = runCatching { qnameOf(query) }.getOrNull()
            if (name != null && blocklist.isBlocked(name)) {
                return synthNxDomain(query)
            }
        }
        return delegate.resolve(query)
    }

    private companion object {
        /**
         * Reads the dotted lowercase QNAME from a raw DNS query, or null if malformed / compressed.
         * Mirrors the label-walk in [DnsMessage.questionKey] but rebuilds the human-readable host.
         */
        fun qnameOf(query: ByteArray): String? {
            if (query.size < DnsMessage.HEADER_LEN + 1) return null
            val sb = StringBuilder(64)
            var p = DnsMessage.HEADER_LEN
            while (p < query.size) {
                val len = query[p].toInt() and 0xFF
                if (len == 0) break                 // root label — end of name
                if (len and 0xC0 != 0) return null  // compression pointer: not valid in a query name
                val start = p + 1
                val end = start + len
                if (end > query.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in start until end) sb.append((query[i].toInt() and 0xFF).toChar())
                p = end
            }
            if (sb.isEmpty()) return null
            return sb.toString().lowercase()
        }

        /**
         * Builds an NXDOMAIN (RCODE 3) response for [query]: same ID + question, QR=1, RA=1, all
         * counts zeroed. Refusing the name — rather than returning `0.0.0.0` — means the app never even
         * attempts a doomed connect to a sinkhole IP.
         */
        fun synthNxDomain(query: ByteArray): ByteArray {
            // Find the end of the question section so we can echo it back verbatim.
            var p = DnsMessage.HEADER_LEN
            while (p < query.size) {
                val len = query[p].toInt() and 0xFF
                if (len == 0) { p += 1; break }
                p += 1 + len
                if (p > query.size) { p = query.size; break }
            }
            val qEnd = (p + 4).coerceAtMost(query.size) // + QTYPE(2) + QCLASS(2)
            val out = query.copyOf(qEnd)
            // Flags: QR=1, keep opcode+RD from the query, RA=1, RCODE=3 (NXDOMAIN).
            out[2] = ((query[2].toInt() and 0x79) or 0x80).toByte() // set QR(0x80), preserve opcode(0x78)+RD(0x01)
            out[3] = 0x83.toByte()                                  // RA=1 (0x80) + RCODE=3 (0x03)
            // QDCOUNT stays 1 (bytes 4..5 from the query); zero ANCOUNT/NSCOUNT/ARCOUNT.
            out[6] = 0; out[7] = 0
            out[8] = 0; out[9] = 0
            out[10] = 0; out[11] = 0
            return out
        }
    }
}
