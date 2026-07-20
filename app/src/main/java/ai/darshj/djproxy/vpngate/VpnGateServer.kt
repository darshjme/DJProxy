package ai.darshj.djproxy.vpngate

import ai.darshj.djproxy.core.ProxyConfig

/**
 * One row of the **VPN Gate public volunteer catalog** (https://www.vpngate.net/api/iphone/).
 *
 * HONEST SCOPE (SSOT `vpngate_scope`): VPN Gate publishes OpenVPN/L2TP/SSTP servers. DJProxy speaks
 * only SOCKS5/HTTP, and the GPLv2 ics-openvpn stack clashes with DJProxy's MIT licence, so an embedded
 * OpenVPN tunnel is **out of scope**. Each row therefore carries the FULL decoded `.ovpn` profile
 * ([ovpn]) so the ui can Export/Share it to an external OpenVPN app. The rare row whose profile embeds
 * an `http-proxy`/`socks-proxy` directive parses to a [ProxyConfig] via the existing `config.OvpnParser`
 * — those, and ONLY those, are [directlyDialable] and get a Use/Save action routed through the
 * unchanged `VpnController.apply(ProxyConfig)` seam. No fake tunnel, no auto-connect.
 *
 * [key] is stable and used as the status-map / list key. All numeric fields degrade to a sentinel
 * (`-1`/`0`) when a column is absent or non-numeric rather than dropping the row.
 */
data class VpnGateServer(
    val hostName: String,
    val ip: String,
    /** Full country name, e.g. "Japan". */
    val countryLong: String,
    /** ISO-3166 alpha-2 code, e.g. "JP" (drives the flag badge). */
    val countryShort: String,
    /** Round-trip latency in ms; `-1` when VPN Gate reported no/blank ping (sorts last). */
    val ping: Int,
    /** VPN Gate quality score — higher is better; the primary sort key. */
    val score: Long,
    /** Line speed in bits/sec as VPN Gate measured it; `0` when unknown. */
    val speed: Long,
    /** Concurrent VPN sessions on the volunteer node (a rough load signal). */
    val sessions: Int,
    /** Node uptime in ms as reported by VPN Gate; `0` when unknown. */
    val uptimeMs: Long,
    /** The decoded OpenVPN profile text — what Export/Share hands to an external OpenVPN app. */
    val ovpn: String,
    /** The ORIGINAL `OpenVPN_ConfigData_Base64` field — kept for a compact cache blob (no re-encode). */
    val configB64: String,
    /** True only when [ovpn] embeds an http-proxy/socks-proxy directive DJProxy can actually dial. */
    val directlyDialable: Boolean,
    /** The dial config for a [directlyDialable] row; `null` for every OpenVPN-only row. */
    val dialConfig: ProxyConfig?,
) {
    /** Stable identity for the live-status map and list keys. */
    val key: String get() = "vpngate:$hostName:$ip"

    /** Line speed rendered in Mbps for the ui badge (0.0 when unknown). */
    val speedMbps: Double get() = speed / 1_000_000.0

    /**
     * Regional-indicator flag emoji derived from [countryShort] (e.g. "JP" → 🇯🇵). Empty when the code
     * is not a plain 2-letter ASCII pair, so a junk code never renders mojibake.
     */
    val flagEmoji: String
        get() {
            val c = countryShort.trim().uppercase()
            if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
            val base = 0x1F1E6
            val first = base + (c[0] - 'A')
            val second = base + (c[1] - 'A')
            return String(Character.toChars(first)) + String(Character.toChars(second))
        }

    companion object {
        /** The unmissable honest label the ui shows on every non-dialable row. */
        const val OPENVPN_ONLY_LABEL: String =
            "OpenVPN-only — open in an external OpenVPN app"
    }
}
