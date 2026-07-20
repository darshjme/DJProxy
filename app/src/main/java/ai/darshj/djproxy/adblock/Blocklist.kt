package ai.darshj.djproxy.adblock

import java.io.InputStream

/**
 * The adblock lane's single source of truth: an immutable, O(1)-lookup set of ad/tracker domains
 * loaded ONCE from `assets/adblock/hosts.txt` (a curated StevenBlack subset, MIT/CC). Both adblock
 * seams read the SAME instance — the CONNECT-time sinkhole in [ai.darshj.djproxy.proxy.LocalSocksServer]
 * (via `FeatureRegistry.blockedHostPredicate`) and the belt-and-suspenders [BlocklistDnsResolver] —
 * so the toggle stays a single cheap flag with no per-seam list divergence.
 *
 * Matching is registrable-domain aware WITHOUT a public-suffix table: a host is blocked if the exact
 * host is listed OR any of its parent domains is listed (a bounded walk that strips one leftmost label
 * at a time). Listing `doubleclick.net` therefore also blocks `ad.doubleclick.net` and
 * `stats.g.doubleclick.net` — but never the unrelated sibling `notdoubleclick.net`, because the walk
 * matches on label boundaries, not raw substrings.
 *
 * Pure JVM (an [InputStream] in, a `HashSet` out) so it unit-tests off-device with no Android.
 */
class Blocklist private constructor(
    private val hosts: HashSet<String>,
) {

    /** Number of distinct domains loaded — surfaced honestly in the settings panel. */
    val size: Int get() = hosts.size

    /**
     * True iff [host] is an ad/tracker host to sinkhole. Hot-path contract: this runs on every
     * CONNECT, so it is an exact `HashSet` hit followed by a bounded parent-suffix walk (at most one
     * label stripped per iteration, capped by the label count) — no regex, no allocation beyond the
     * lowercased key. Never throws; a malformed/empty/IP host simply returns false (fail-open).
     */
    fun isBlocked(host: String): Boolean {
        if (hosts.isEmpty()) return false
        // Normalize: lowercase + drop a trailing FQDN dot. Bail on empties and bare IPs (an IP has no
        // parent domain to match and must never be caught by a suffix walk).
        var h = host.trim().lowercase()
        if (h.isEmpty()) return false
        if (h.endsWith(".")) h = h.dropLast(1)
        if (h.isEmpty() || isIpLiteral(h)) return false

        if (hosts.contains(h)) return true
        // Parent-suffix walk: a.b.c → b.c → c. Bounded by the number of dots; stop at the last label
        // (a bare TLD like "net" is never a blocklist entry, so no need to test it).
        var idx = h.indexOf('.')
        while (idx in 0 until h.length - 1) {
            val parent = h.substring(idx + 1)
            if (parent.indexOf('.') < 0) break // reached the bare TLD — nothing left worth testing
            if (hosts.contains(parent)) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    companion object {
        /** Guard against a runaway asset (a malformed multi-MB list): cap entries defensively. */
        private const val MAX_ENTRIES = 200_000

        /**
         * Loads and normalizes [input] into an immutable [Blocklist]. Accepts three line shapes so a
         * raw hosts-file drop-in also works:
         *   - bare domain:            `doubleclick.net`
         *   - hosts redirect:         `0.0.0.0 doubleclick.net` / `127.0.0.1 doubleclick.net`
         *   - trailing comment:       `doubleclick.net   # google ads`
         * Comments (`#…`), blanks, localhost/loopback redirect targets, and IP-only tokens are dropped.
         * Closes [input]. Never throws — a read error yields an empty (fail-open) blocklist.
         */
        fun load(input: InputStream): Blocklist {
            val set = HashSet<String>(4096)
            runCatching {
                input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        if (set.size >= MAX_ENTRIES) break
                        val domain = normalizeLine(raw) ?: continue
                        set.add(domain)
                    }
                }
            }
            return Blocklist(set)
        }

        /** An explicit-set factory for unit tests (no InputStream plumbing needed). */
        fun of(vararg domains: String): Blocklist {
            val set = HashSet<String>(domains.size * 2)
            for (d in domains) normalizeLine(d)?.let(set::add)
            return Blocklist(set)
        }

        /**
         * Extracts the registrable domain token from one raw line, or null if the line carries no
         * usable domain. Pure and side-effect-free so it is directly unit-tested.
         */
        internal fun normalizeLine(raw: String): String? {
            // Strip a trailing comment, then trim.
            val noComment = raw.substringBefore('#').trim()
            if (noComment.isEmpty()) return null

            // Whitespace-split: a hosts line is "<ip> <domain>"; a bare line is just "<domain>".
            val tokens = noComment.split(Regex("\\s+"))
            val candidate = when {
                tokens.size >= 2 && isIpLiteral(tokens[0]) -> tokens[1]
                tokens.size == 1 -> tokens[0]
                else -> return null // multi-token line that isn't "<ip> <domain>" — skip, don't guess
            }

            val host = candidate.lowercase().removeSuffix(".")
            if (host.isEmpty() || host.indexOf('.') < 0) return null // need at least one dot to be a domain
            if (isIpLiteral(host)) return null                        // never blocklist a bare IP
            if (host == "localhost" || host == "broadcasthost") return null
            // Reject anything with a character that can't appear in a hostname (a cheap sanity gate).
            if (host.any { it != '.' && it != '-' && it != '_' && it !in 'a'..'z' && it !in '0'..'9' }) return null
            return host
        }

        /** True for an IPv4 dotted-quad or an IPv6 literal (anything with a colon). Cheap, no parsing. */
        private fun isIpLiteral(s: String): Boolean {
            if (s.indexOf(':') >= 0) return true // IPv6 (or host:port, which we never blocklist anyway)
            val parts = s.split('.')
            if (parts.size != 4) return false
            return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
        }
    }
}
