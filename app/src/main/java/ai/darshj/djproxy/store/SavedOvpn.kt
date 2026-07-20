package ai.darshj.djproxy.store

/**
 * The display / metadata projection of a saved VPN Gate `.ovpn` profile.
 *
 * It is deliberately **text-free**: it carries no `.ovpn` body, so it is safe to log, snapshot, and
 * recompose. The bulky profile text lives only under the per-id `ovpn.<id>` key in the vault (see
 * [SharedPreferencesOvpnVault]) and is materialised on demand by [OvpnVault.resolve] right before a
 * hand-off to an external OpenVPN app. This mirrors [SavedProxy] 1:1 — except there is **no secret**:
 * VPN Gate volunteer profiles are anonymous (no username/password), so nothing here is encrypted and
 * there is no `hasPassword`/Keystore path. (KDoc note for auditors: a hypothetical credential-bearing
 * `.ovpn` would therefore be at-rest **plaintext** under `ovpn.<id>` — acceptable only because VPN
 * Gate profiles carry no credentials; do not repurpose this vault for cred-bearing configs.)
 *
 * @property id           stable UUID assigned on save.
 * @property name         user label — defaults to the origin [hostName] on save.
 * @property countryShort ISO-3166 alpha-2 code, e.g. "JP" (drives the flag badge + grouping).
 * @property countryLong  full country name, e.g. "Japan".
 * @property hostName     the VPN Gate volunteer host the profile came from.
 * @property order        stable sort key (ascending) controlling list position.
 */
data class SavedOvpn(
    val id: String,
    val name: String,
    val countryShort: String,
    val countryLong: String,
    val hostName: String,
    val order: Int,
) {
    /**
     * Regional-indicator flag emoji derived from [countryShort] (e.g. "JP" → 🇯🇵). Empty when the code
     * is not a plain 2-letter ASCII pair, so a junk code never renders mojibake. Copied verbatim from
     * `vpngate.VpnGateServer.flagEmoji` so the saved-profiles list badges identically to the catalog
     * (the store package deliberately does not depend on the vpngate lane).
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
}
