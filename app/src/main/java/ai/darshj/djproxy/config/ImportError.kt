package ai.darshj.djproxy.config

import ai.darshj.djproxy.proxy.ProxyError

/**
 * The closed, typed set of reasons an *import* (as opposed to a live dial) can fail.
 *
 * Every case carries a human [message] and a one-line [hint], exactly like [ProxyError], so the ui
 * can render it verbatim in the existing inline rose card. Import failures are honest and named — a
 * full-obfuscation scheme DJProxy cannot speak is *rejected by name*, never faked into a success.
 *
 * The config lane owns this vocabulary; the ui lane maps it onto the existing error channel via
 * [toProxyError] (DESIGN_V4 §5).
 */
sealed class ImportError(val message: String, val hint: String) {

    /** Nothing usable was supplied (empty paste / empty clipboard / blank intent extra). */
    object Empty : ImportError(
        message = "Nothing to import",
        hint = "Paste a proxy line, a socks5:// / ss:// link, a subscription URL, or open a .ovpn file.",
    )

    /**
     * The scheme was recognised but it is a full transport/obfuscation protocol DJProxy — a plain
     * SOCKS5/HTTP fronter — cannot terminate (Shadowsocks AEAD, VMess, VLESS, Trojan, Hysteria2, …).
     * [detail] names the concrete cipher/endpoint so the message is actionable.
     */
    data class UnsupportedProtocol(val scheme: String, val detail: String) : ImportError(
        message = "\"$scheme\" is not a proxy DJProxy can speak ($detail)",
        hint = "DJProxy routes SOCKS5 and HTTP CONNECT proxies only. Use a socks5:// or http:// endpoint.",
    )

    /** A `.ovpn` file that carries no `http-proxy`/`socks-proxy` directive — it is a full VPN config. */
    object OvpnNotAProxy : ImportError(
        message = "This .ovpn is a full VPN config, not a proxy",
        hint = "DJProxy routes SOCKS/HTTP proxies only. Import a file that has an http-proxy or socks-proxy line.",
    )

    /** The URI's shape was broken (missing host/port, un-decodable base64, junk after the scheme). */
    data class MalformedUri(val scheme: String, val detail: String) : ImportError(
        message = "Could not read this $scheme link ($detail)",
        hint = "Expected $scheme://user:pass@host:port. Re-copy the full link and try again.",
    )

    /** A subscription URL could not be fetched (offline, DNS failure, TLS error, non-2xx status). */
    data class SubscriptionUnreachable(val detail: String) : ImportError(
        message = "Could not fetch that subscription ($detail)",
        hint = "Check the link and your connectivity, then try again.",
    )

    /** The subscription was fetched but yielded no config DJProxy could parse. */
    object EmptySubscription : ImportError(
        message = "That subscription contained no usable proxies",
        hint = "The list was empty or held only protocols DJProxy can't speak (VMess/Trojan/…).",
    )

    /** A last-resort catch-all when a line matches no known shape at all. */
    data class Unreadable(val detail: String) : ImportError(
        message = "Could not read \"$detail\"",
        hint = "Try socks5://user:pass@host:port, host:port:user:pass, or a subscription URL.",
    )

    /**
     * Map onto the existing [ProxyError] vocabulary so the ui's single inline error card can show it
     * unchanged (DESIGN_V4 §5). [ProxyError.Io] is the closest general bucket; the *import* message and
     * hint are already fully human, so we hand the message straight through.
     */
    fun toProxyError(): ProxyError = ProxyError.Io(message)
}
