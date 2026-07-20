package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType

/**
 * Reads an OpenVPN `.ovpn` profile for the **proxy-relevant directives only** (DESIGN_V4 §5).
 *
 * DJProxy is a SOCKS5/HTTP proxy app, **not** an OpenVPN client. It therefore parses:
 *   - `http-proxy HOST PORT [...]`   → [ProxyType.HTTP]
 *   - `socks-proxy HOST PORT [...]`  (alias `socks-proxy-server`) → [ProxyType.SOCKS5]
 * and reads `remote HOST PORT` + `proto` **only as context** for an honest message.
 *
 * If the file carries no proxy directive it is a full VPN config → [ImportError.OvpnNotAProxy].
 * All PKI/crypto/`<ca>`/`<cert>` blocks are ignored.
 */
object OvpnParser {

    fun parse(text: String): ImportResult {
        var remoteHost: String? = null
        var remotePort: String? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            // Strip inline comments (OpenVPN allows trailing `# ...`).
            val effective = line.substringBefore('#').substringBefore(';').trim()
            if (effective.isEmpty()) continue

            val tokens = effective.split(Regex("\\s+"))
            when (tokens[0].lowercase()) {
                "http-proxy" -> {
                    val cfg = directiveToConfig(tokens, ProxyType.HTTP)
                    if (cfg != null) return ImportResult.Single(cfg)
                }
                "socks-proxy", "socks-proxy-server" -> {
                    val cfg = directiveToConfig(tokens, ProxyType.SOCKS5)
                    if (cfg != null) return ImportResult.Single(cfg)
                }
                "remote" -> {
                    if (tokens.size >= 2 && remoteHost == null) {
                        remoteHost = tokens[1]
                        remotePort = tokens.getOrNull(2)
                    }
                }
            }
        }

        // No proxy directive anywhere → honestly not a proxy DJProxy can use.
        return ImportResult.Rejected(ImportError.OvpnNotAProxy)
    }

    private fun directiveToConfig(tokens: List<String>, type: ProxyType): ProxyConfig? {
        if (tokens.size < 3) return null
        val host = tokens[1].trim()
        val port = tokens[2].toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return ProxyConfig(type = type, host = host, port = port)
    }

    /**
     * Cheap structural sniff so [ConfigImporter] can route multi-line text here. True when the text
     * looks like an OpenVPN profile (a proxy directive, or the classic VPN skeleton directives).
     */
    fun looksLikeOvpn(text: String): Boolean {
        if (!text.contains('\n')) return false
        val markers = listOf(
            "http-proxy", "socks-proxy", "remote ", "proto ", "dev tun", "dev tap",
            "<ca>", "<cert>", "tls-client", "auth-user-pass", "client",
        )
        return text.lineSequence().any { raw ->
            val l = raw.trim().lowercase()
            markers.any { l.startsWith(it) || l == it.trim() }
        }
    }
}
