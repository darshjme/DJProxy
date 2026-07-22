package ai.darshj.djproxy.wireguard

import org.json.JSONObject

/**
 * A resolved WireGuard profile — the fields the native engine ([ovpnsocks.Ovpnsocks.startWireguard])
 * needs. Keys are standard base64 (as a `.conf` / WARP profile carries them); the Go layer converts
 * them to the UAPI hex form. Powers every WG route: Cloudflare WARP, a user's own Oracle/VPS server,
 * or any public WireGuard peer.
 */
data class WgProfile(
    val privateKey: String,
    val address: String,          // interface Address (v4/CIDR ok)
    val dns: String,              // interface DNS (comma list) — defaults 1.1.1.1
    val peerPublicKey: String,
    val presharedKey: String,     // "" if none (WARP has none)
    val endpoint: String,         // host:port
    val allowedIps: String,       // defaults full-tunnel
) {
    companion object {
        /** Parse the JSON string returned by `Ovpnsocks.registerWarp()` into a full-tunnel WARP profile. */
        fun fromWarpJson(json: String): WgProfile? = runCatching {
            val o = JSONObject(json)
            WgProfile(
                privateKey = o.getString("private_key"),
                address = o.optString("address", "172.16.0.2"),
                dns = "1.1.1.1",
                peerPublicKey = o.getString("peer_public_key"),
                presharedKey = "",
                endpoint = o.optString("endpoint", "engage.cloudflareclient.com:2408"),
                allowedIps = "0.0.0.0/0,::/0",
            )
        }.getOrNull()

        /**
         * Parse a standard WireGuard `.conf` (what a user pastes for their own Oracle/VPS server or any
         * public WG peer). Tolerant, case-insensitive keys; returns null if the mandatory fields
         * (PrivateKey, Address, Peer PublicKey, Endpoint) are missing.
         */
        fun fromConf(text: String): WgProfile? = runCatching {
            var priv = ""; var addr = ""; var dns = ""
            var peerPub = ""; var psk = ""; var endpoint = ""; var allowed = ""
            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith("[")) continue
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "privatekey" -> priv = value
                    "address" -> addr = value
                    "dns" -> dns = value
                    "publickey" -> peerPub = value
                    "presharedkey" -> psk = value
                    "endpoint" -> endpoint = value
                    "allowedips" -> allowed = value
                }
            }
            if (priv.isBlank() || addr.isBlank() || peerPub.isBlank() || endpoint.isBlank()) return null
            WgProfile(
                privateKey = priv,
                address = addr,
                dns = dns.ifBlank { "1.1.1.1" },
                peerPublicKey = peerPub,
                presharedKey = psk,
                endpoint = endpoint,
                allowedIps = allowed.ifBlank { "0.0.0.0/0,::/0" },
            )
        }.getOrNull()
    }
}
