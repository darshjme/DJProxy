package ai.darshj.djproxy.core

/**
 * Parses the many shapes proxy credentials get pasted in.
 *
 * Recognised:
 *   socks5://user:pass@host:port      http://user:pass@host:port
 *   socks5://host:port                user:pass@host:port
 *   host:port:user:pass               user:pass:host:port
 *   host:port                         host port user pass   (any whitespace)
 *
 * The type is taken from the scheme when present, otherwise [default] is kept.
 */
object ProxyParser {

    sealed interface Result {
        data class Ok(val config: ProxyConfig) : Result
        data class Err(val message: String, val hint: String? = null) : Result
    }

    fun parse(raw: String, default: ProxyConfig = ProxyConfig()): Result {
        val input = raw.trim()
        if (input.isEmpty()) return Result.Err("Nothing to parse", "Paste a proxy line first.")

        var rest = input
        var type = default.type

        // ---- scheme ----
        val schemeIdx = rest.indexOf("://")
        if (schemeIdx > 0) {
            val scheme = rest.substring(0, schemeIdx)
            type = ProxyType.fromScheme(scheme)
                ?: return Result.Err(
                    "Unknown proxy scheme \"$scheme\"",
                    "Use socks5:// or http:// — those are the two DJProxy speaks.",
                )
            rest = rest.substring(schemeIdx + 3)
        }
        rest = rest.trim().trimEnd('/')
        if (rest.isEmpty()) return Result.Err("No host after the scheme")

        // Whitespace-separated form: host port [user] [pass]
        val ws = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (ws.size >= 2 && !rest.contains('@') && ws[1].toIntOrNull() != null) {
            return build(type, ws[0], ws[1], ws.getOrElse(2) { "" }, ws.getOrElse(3) { "" }, default)
        }
        if (ws.size > 1) {
            return Result.Err(
                "Could not read \"$input\"",
                "Spaces are only allowed in the \"host port user pass\" form.",
            )
        }

        // ---- user:pass@host:port ----
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val credPart = rest.substring(0, at)
            val hostPart = rest.substring(at + 1)
            val hp = splitHostPort(hostPart)
                ?: return Result.Err("Could not read host:port from \"$hostPart\"")
            val colon = credPart.indexOf(':')
            val user = if (colon >= 0) credPart.substring(0, colon) else credPart
            val pass = if (colon >= 0) credPart.substring(colon + 1) else ""
            return build(type, hp.first, hp.second, user, pass, default)
        }

        // ---- colon-separated forms ----
        val parts = rest.split(':')
        return when (parts.size) {
            2 -> build(type, parts[0], parts[1], "", "", default)
            4 -> when {
                // host:port:user:pass
                parts[1].toIntOrNull() != null ->
                    build(type, parts[0], parts[1], parts[2], parts[3], default)
                // user:pass:host:port
                parts[3].toIntOrNull() != null ->
                    build(type, parts[2], parts[3], parts[0], parts[1], default)
                else -> Result.Err(
                    "Neither field 2 nor field 4 is a port number",
                    "Expected host:port:user:pass or user:pass:host:port.",
                )
            }
            1 -> Result.Err(
                "No port found in \"$rest\"",
                "Add the port, e.g. $rest:1080",
            )
            else -> Result.Err(
                "Could not read \"$input\"",
                "Try socks5://user:pass@host:port or host:port:user:pass.",
            )
        }
    }

    private fun build(
        type: ProxyType,
        host: String,
        portStr: String,
        user: String,
        pass: String,
        default: ProxyConfig,
    ): Result {
        val port = portStr.toIntOrNull()
            ?: return Result.Err("\"$portStr\" is not a port number")
        if (port !in 1..65535) return Result.Err("Port $port is out of range (1-65535)")
        val cleanHost = host.trim().removeSurrounding("[", "]")
        if (cleanHost.isEmpty()) return Result.Err("Host is empty")
        return Result.Ok(
            default.copy(
                type = type,
                host = cleanHost,
                port = port,
                username = user.trim(),
                password = pass,
            )
        )
    }

    private fun splitHostPort(s: String): Pair<String, String>? {
        val i = s.lastIndexOf(':')
        if (i <= 0 || i == s.length - 1) return null
        return s.substring(0, i) to s.substring(i + 1)
    }
}
