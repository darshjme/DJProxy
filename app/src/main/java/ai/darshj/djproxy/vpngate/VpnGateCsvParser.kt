package ai.darshj.djproxy.vpngate

import ai.darshj.djproxy.config.Base64Compat
import ai.darshj.djproxy.config.ImportResult
import ai.darshj.djproxy.config.OvpnParser
import ai.darshj.djproxy.freeproxy.FreeProxyParser

/**
 * Pure, JVM-testable parser for the VPN Gate **iPhone CSV** feed (SSOT `vpngate_scope`).
 *
 * The feed shape is:
 * ```
 * *vpn_servers
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,...,OpenVPN_ConfigData_Base64
 * public-vpn-1,219.100.37.10,1234567,15,12345678,Japan,JP,10,3600000,...,<base64 ovpn>
 * *
 * ```
 * We skip the `*vpn_servers` header line and the trailing `*`, column-map the leading fields by the
 * `#HostName,…` header (order-independent), and — because the free-text `Message`/`Operator` columns can
 * themselves contain commas — always take the base64 profile as the **last** comma field (it is always
 * the final column). Every row is then:
 *   1. SSRF-screened on its IP with the SAME integer-math predicate as [FreeProxyParser.isScreenedOut]
 *      (public IPv4 only — closes the poisoned-list → device-LAN vector),
 *   2. base64-decoded ([Base64Compat], unit-test-safe) into its full `.ovpn` text, and
 *   3. run through the EXISTING [OvpnParser.parse]; a profile that yields [ImportResult.Single] is
 *      flagged [VpnGateServer.directlyDialable] and carries the parsed [dialConfig], all others are
 *      OpenVPN-only (Export/Share).
 *
 * Results are capped at [MAX_SERVERS] and sorted by score desc, then ping asc (unknown ping last). No
 * Android, no network, no JSON — safe under `unitTests.isReturnDefaultValues = true`.
 */
object VpnGateCsvParser {

    /** Hard cap on the catalog so the picker + status checker can't be flooded by the ~7k-row feed. */
    const val MAX_SERVERS: Int = 200

    // Canonical column headers we read (VPN Gate spells them exactly like this, with a leading '#').
    private const val COL_HOST = "HostName"
    private const val COL_IP = "IP"
    private const val COL_SCORE = "Score"
    private const val COL_PING = "Ping"
    private const val COL_SPEED = "Speed"
    private const val COL_COUNTRY_LONG = "CountryLong"
    private const val COL_COUNTRY_SHORT = "CountryShort"
    private const val COL_SESSIONS = "NumVpnSessions"
    private const val COL_UPTIME = "Uptime"

    fun parse(csv: String): List<VpnGateServer> {
        val lines = csv.lineSequence().iterator()

        // Locate the header line (starts with '#'), skipping the '*vpn_servers' banner / blanks.
        var header: Map<String, Int>? = null
        val dataLines = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line == "*") continue                    // trailing terminator
            if (line.startsWith("*")) continue           // '*vpn_servers' banner
            if (line.startsWith("#")) {
                if (header == null) header = mapHeader(line.substring(1))
                continue
            }
            dataLines.add(line)
        }
        val cols = header ?: return emptyList()
        val expected = cols.size

        val out = ArrayList<VpnGateServer>(dataLines.size)
        for (line in dataLines) {
            parseRow(line, cols, expected)?.let(out::add)
        }

        // Score desc, then ping asc with unknown ping (-1) pushed to the very end.
        return out
            .sortedWith(
                compareByDescending<VpnGateServer> { it.score }
                    .thenBy { if (it.ping < 0) Int.MAX_VALUE else it.ping },
            )
            .take(MAX_SERVERS)
    }

    /** Build a case-sensitive header→index map from the comma-separated header (leading '#' stripped). */
    private fun mapHeader(headerBody: String): Map<String, Int> {
        val map = HashMap<String, Int>()
        headerBody.split(',').forEachIndexed { i, name -> map[name.trim()] = i }
        return map
    }

    private fun parseRow(line: String, cols: Map<String, Int>, expected: Int): VpnGateServer? {
        val parts = line.split(',')
        // A well-formed row has >= the header's column count. It may have MORE (a free-text Message /
        // Operator carrying commas) — the base64 profile is still the final field, read as parts.last().
        // A row with FEWER fields is truncated/garbage → drop it so we never read the wrong column.
        if (parts.size < expected) return null
        val lastIdx = parts.size - 1

        val host = col(parts, cols, COL_HOST)?.trim().orEmpty()
        val ip = col(parts, cols, COL_IP)?.trim().orEmpty()
        if (host.isEmpty() || ip.isEmpty()) return null

        // SSRF / junk screen: public IPv4 literals only (dummy port 443 exercises the range check).
        if (FreeProxyParser.isScreenedOut(ip, 443)) return null

        // The base64 OpenVPN profile is ALWAYS the final comma field — robust to commas in Message.
        val configB64 = parts[lastIdx].trim()
        if (configB64.isEmpty()) return null
        val ovpn = Base64Compat.decodeToString(configB64) ?: return null
        if (ovpn.isBlank()) return null

        // Reuse the EXISTING OpenVPN reader: only a profile with an http/socks-proxy directive is dialable.
        val dialConfig = when (val r = OvpnParser.parse(ovpn)) {
            is ImportResult.Single -> r.config
            else -> null
        }

        return VpnGateServer(
            hostName = host,
            ip = ip,
            countryLong = col(parts, cols, COL_COUNTRY_LONG)?.trim().orEmpty(),
            countryShort = col(parts, cols, COL_COUNTRY_SHORT)?.trim().orEmpty(),
            ping = col(parts, cols, COL_PING)?.trim()?.toIntOrNull() ?: -1,
            score = col(parts, cols, COL_SCORE)?.trim()?.toLongOrNull() ?: 0L,
            speed = col(parts, cols, COL_SPEED)?.trim()?.toLongOrNull() ?: 0L,
            sessions = col(parts, cols, COL_SESSIONS)?.trim()?.toIntOrNull() ?: 0,
            uptimeMs = col(parts, cols, COL_UPTIME)?.trim()?.toLongOrNull() ?: 0L,
            ovpn = ovpn,
            configB64 = configB64,
            directlyDialable = dialConfig != null,
            dialConfig = dialConfig,
        )
    }

    /** Read a mapped column by header name; null when the header is absent or the row is too short. */
    private fun col(parts: List<String>, cols: Map<String, Int>, name: String): String? {
        val idx = cols[name] ?: return null
        return parts.getOrNull(idx)
    }
}
