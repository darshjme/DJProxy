package ai.darshj.djproxy.dns

/**
 * One pluggable DNS transport (§3.2). Resolves a raw DNS *message* (no length prefix) through the
 * proxy exit so geo/OTT unblocking is consistent and there is no DNS geo-leak.
 *
 * Hard contract:
 *  - MUST NOT throw. Any failure returns `null` and the caller decides whether to fall back.
 *  - MUST resolve through the injected proxy dialer (exit-side resolution), never the device resolver.
 */
interface DnsResolver {
    /** Stable label for logs / [ai.darshj.djproxy.vpn.HealthReport], e.g. "DoH:443". */
    val label: String

    /**
     * @param query raw DNS query message (the exact bytes the app put on the wire, ID included).
     * @return raw DNS answer message (ID matching [query]), or null on failure.
     */
    suspend fun resolve(query: ByteArray): ByteArray?
}
