package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyType

/**
 * Pure, JVM-testable parser + **SSRF/junk screen** for the free public proxy lists.
 *
 * Input is line-oriented TXT (`ip:port`, `socks5://ip:port`, `http://ip:port`, optionally with a
 * `user:pass@` prefix which is discarded — public proxies are auth-less). Every parsed entry is
 * screened: the host must be a valid **public IPv4 literal** and the port in `1..65535`. Loopback,
 * private, link-local, CGNAT, multicast, reserved, unspecified and broadcast targets are dropped —
 * this closes the SSRF vector where a poisoned list could aim the checker/tunnel at the device's own
 * LAN or a metadata endpoint. Results are deduped by [FreeProxyEntry.key] and capped at [MAX_ENTRIES].
 *
 * No Android, no network, no JSON — safe under `unitTests.isReturnDefaultValues = true`.
 */
object FreeProxyParser {

    /**
     * Hard cap on the merged CANDIDATE pool fed to the health-check sweep. The DISPLAYED list is only
     * the green (alive) survivors of that sweep, so a bigger pool = more live results — 600 candidates
     * at ~4 s timeouts / 28-wide fan-out still completes in well under two minutes worst-case.
     */
    const val MAX_ENTRIES: Int = 600

    /**
     * Parse a full TXT [body] into screened entries, tagging each with [sourceLabel] and defaulting
     * bare `ip:port` lines to [defaultType] (the list's protocol). Order preserved; unscreened/malformed
     * lines are dropped silently.
     */
    fun parse(body: String, defaultType: ProxyType, sourceLabel: String): List<FreeProxyEntry> {
        val out = ArrayList<FreeProxyEntry>()
        for (raw in body.lineSequence()) {
            parseLine(raw, defaultType, sourceLabel)?.let(out::add)
        }
        return out
    }

    /**
     * Parse and screen a single line. Returns null for blanks, comments, unsupported schemes, malformed
     * `host:port`, or any host/port the SSRF screen rejects.
     */
    fun parseLine(raw: String, defaultType: ProxyType, sourceLabel: String): FreeProxyEntry? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return null

        // ---- scheme (optional) ----
        var type = defaultType
        val schemeIdx = line.indexOf("://")
        if (schemeIdx > 0) {
            val scheme = line.substring(0, schemeIdx)
            // socks4, ss, vmess, … are not dialable by DJProxy's SOCKS5/HTTP path → drop.
            type = ProxyType.fromScheme(scheme) ?: return null
            line = line.substring(schemeIdx + 3)
        }

        // Strip any trailing path / query / fragment a provider might append.
        line = line.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        if (line.isEmpty()) return null

        // Discard credentials if present (user:pass@host:port) — free proxies are auth-less.
        val at = line.lastIndexOf('@')
        if (at >= 0) line = line.substring(at + 1).trim()
        if (line.isEmpty()) return null

        // host:port  (also tolerates host:port:user:pass by taking the first two fields).
        val hostPart: String
        val portPart: String
        if (line.startsWith("[")) {
            // Bracketed host (e.g. an IPv6 literal) — unsupported by the IPv4 screen, drop early.
            return null
        }
        val firstColon = line.indexOf(':')
        if (firstColon <= 0 || firstColon == line.length - 1) return null
        hostPart = line.substring(0, firstColon).trim()
        // port is the field immediately after the host; ignore any further :user:pass tail.
        val afterHost = line.substring(firstColon + 1)
        portPart = afterHost.substringBefore(':').trim()

        val port = portPart.toIntOrNull() ?: return null
        if (isScreenedOut(hostPart, port)) return null
        return FreeProxyEntry(type = type, host = hostPart, port = port, sourceLabel = sourceLabel)
    }

    /**
     * Merge per-source lists in the given priority order, dedupe by [FreeProxyEntry.key], and cap at
     * [MAX_ENTRIES]. Deterministic: earlier lists win a duplicate; within a list, original order holds.
     */
    fun mergeDedupeCap(lists: List<List<FreeProxyEntry>>): List<FreeProxyEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<FreeProxyEntry>(MAX_ENTRIES)
        for (list in lists) {
            for (e in list) {
                if (out.size >= MAX_ENTRIES) return out
                if (seen.add(e.key)) out.add(e)
            }
        }
        return out
    }

    // ---- SSRF / junk screen (pure integer math, no DNS) ------------------------------------------

    /**
     * True when [host]/[port] must be dropped: not a valid public IPv4 literal, or a port out of range.
     * Blocks unspecified/this-network (0/8), loopback (127/8), private (10/8, 172.16/12, 192.168/16),
     * link-local (169.254/16), CGNAT (100.64/10), multicast (224/4), reserved (240/4) and the
     * limited-broadcast address.
     */
    fun isScreenedOut(host: String, port: Int): Boolean {
        if (port !in 1..65535) return true
        val ip = parseIpv4(host) ?: return true
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        return when {
            a == 0 -> true                                   // 0.0.0.0/8 unspecified / "this network"
            a == 10 -> true                                  // 10.0.0.0/8 private
            a == 100 && b in 64..127 -> true                 // 100.64.0.0/10 CGNAT
            a == 127 -> true                                 // 127.0.0.0/8 loopback
            a == 169 && b == 254 -> true                     // 169.254.0.0/16 link-local
            a == 172 && b in 16..31 -> true                  // 172.16.0.0/12 private
            a == 192 && b == 168 -> true                     // 192.168.0.0/16 private
            a in 224..239 -> true                            // 224.0.0.0/4 multicast
            a >= 240 -> true                                 // 240.0.0.0/4 reserved + 255.255.255.255
            else -> false
        }
    }

    /** Parse a strict dotted-quad IPv4 literal to a packed Int, or null if not a valid literal. */
    fun parseIpv4(host: String): Int? {
        val h = host.trim()
        if (h.isEmpty() || h.length > 15) return null
        val parts = h.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return null
            if (!p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }
}
