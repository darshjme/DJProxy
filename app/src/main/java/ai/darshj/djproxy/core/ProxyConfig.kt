package ai.darshj.djproxy.core

enum class ProxyType(val label: String, val scheme: String) {
    SOCKS5("SOCKS5", "socks5"),
    HTTP("HTTP CONNECT", "http");

    companion object {
        fun fromScheme(s: String): ProxyType? = when (s.lowercase()) {
            "socks5", "socks5h", "socks" -> SOCKS5
            "http", "https", "http-connect" -> HTTP
            else -> null
        }
    }
}

data class ProxyConfig(
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** Upstream DNS resolved over TCP through the proxy — never via the device resolver. */
    val dnsServer: String = "1.1.1.1",
) {
    val hasAuth: Boolean get() = username.isNotEmpty()

    /**
     * Display string safe for surfaces that leave the app (the foreground notification, the status
     * card, logs). Neither the password NOR the username is revealed — a masked marker only signals
     * that credentials are present, so a lockscreen/notification-listener never sees half the pair.
     */
    fun redacted(): String {
        val auth = if (hasAuth) "•••@" else ""
        return "${type.scheme}://$auth$host:$port"
    }

    /** Field-level validation, independent of whether the proxy is actually reachable. */
    fun validate(): String? = when {
        host.isBlank() -> "Proxy host is empty"
        host.contains(' ') -> "Proxy host contains a space"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        password.isNotEmpty() && username.isEmpty() -> "Password set but username is empty"
        dnsServer.isBlank() -> "DNS server is empty"
        else -> runCatching { ai.darshj.djproxy.net.stringToIp(dnsServer) }
            .fold({ null }, { "DNS server must be an IPv4 literal (e.g. 1.1.1.1)" })
    }
}
