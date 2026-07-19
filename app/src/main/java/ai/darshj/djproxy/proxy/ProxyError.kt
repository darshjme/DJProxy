package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import java.net.Socket

/**
 * The full, closed set of reasons validating or dialing a proxy can fail.
 *
 * Every case carries a human [message] and a one-line [hint] on how to fix it — the UI shows
 * these verbatim, so there is never a generic "error". This hierarchy is the single vocabulary
 * shared by the Validator (pre-flight) and the LocalSocksServer (live path); both must map any
 * lower-level exception onto exactly one of these before it reaches the user.
 */
sealed class ProxyError(val message: String, val hint: String) {

    /** DNS could not resolve the proxy host to an address (host typo, dead domain, no network). */
    data class DnsResolutionFailed(val host: String) : ProxyError(
        message = "Could not resolve \"$host\"",
        hint = "Check the host spelling, or use the proxy's IP address instead of a name.",
    )

    /** TCP connect was actively refused — nothing is listening on that host:port. */
    data class ConnectionRefused(val host: String, val port: Int) : ProxyError(
        message = "$host:$port refused the connection",
        hint = "Wrong port, or the proxy is offline. Confirm the port with your provider.",
    )

    /** TCP connect or handshake did not complete within the deadline (firewall/black-hole/slow). */
    data class Timeout(val phase: String) : ProxyError(
        message = "Timed out during $phase",
        hint = "The proxy is unreachable or blocked on this network. Try another network or proxy.",
    )

    /** The proxy answered the handshake but rejected the username/password. */
    object AuthRejected : ProxyError(
        message = "The proxy rejected these credentials",
        hint = "Re-check the username and password — they are case-sensitive.",
    )

    /** Something answered on the port but did not speak SOCKS5 (e.g. an HTTP proxy on a SOCKS port). */
    object NotASocks5Server : ProxyError(
        message = "That port is not a SOCKS5 server",
        hint = "It may be an HTTP proxy — switch Type to HTTP, or fix the port.",
    )

    /** An HTTP CONNECT proxy returned a 4xx/5xx status line other than the auth-specific 407. */
    data class HttpStatus(val code: Int, val reason: String) : ProxyError(
        message = "Proxy returned HTTP $code $reason",
        hint = "The proxy refused the tunnel. Check the endpoint is an HTTP CONNECT proxy.",
    )

    /** The proxy accepted the handshake but refused to open a tunnel to the requested port. */
    data class ConnectRefusedByProxy(val host: String, val port: Int) : ProxyError(
        message = "Proxy would not connect to $host:$port",
        hint = "This proxy blocks that destination port; try a proxy without egress filtering.",
    )

    /** Handshake bytes were structurally invalid (truncated/garbage) — not a spec-compliant proxy. */
    data class HandshakeMalformed(val detail: String) : ProxyError(
        message = "Malformed proxy handshake ($detail)",
        hint = "This endpoint is not a well-formed SOCKS5/HTTP proxy.",
    )

    /** Handshake succeeded but the real probe request through the proxy did not come back. */
    data class ProbeFailed(val detail: String) : ProxyError(
        message = "The proxy connected but the test request failed ($detail)",
        hint = "The proxy may be filtering traffic or dead upstream. Try a different proxy.",
    )

    /** Low-level I/O failure with no more specific classification. */
    data class Io(val detail: String) : ProxyError(
        message = "Network error: $detail",
        hint = "Check your device's own connectivity and try again.",
    )
}

/** Outcome of the validate-before-up pre-flight. Total: success carries proof, failure carries a typed error. */
sealed interface ValidationResult {
    /**
     * The proxy genuinely works: TCP connected, protocol handshake completed, and a real probe
     * request returned. Only this result may bring the VPN up.
     *
     * @param latencyMs round-trip of the probe request through the proxy.
     * @param probeStatus HTTP status of the probe response (e.g. 204), or -1 if not HTTP.
     * @param exitIp the source IP the probe observed (proxy's egress), if the probe reported it.
     */
    data class Success(
        val latencyMs: Long,
        val probeStatus: Int,
        val exitIp: String?,
    ) : ValidationResult

    data class Failure(val error: ProxyError) : ValidationResult
}

/** Result of opening one tunnelled TCP connection through the upstream proxy. */
sealed interface DialResult {
    /** [socket] is connected and already tunnelled to the requested destination; caller owns it. */
    data class Ok(val socket: Socket) : DialResult
    data class Fail(val error: ProxyError) : DialResult
}

/**
 * The single seam through which every off-device socket is protected from the VpnService route.
 *
 * There must be exactly ONE implementation that calls [android.net.VpnService.protect]; a CI grep
 * asserts `VpnService.protect` / `.protect(` appears only in that one file. Everything that dials
 * upstream receives this and must protect its socket before connecting, or the tunnel loops back
 * into itself.
 */
fun interface SocketProtector {
    /** @return true if the socket was successfully excluded from the tun. */
    fun protect(socket: Socket): Boolean
}

/**
 * Opens tunnelled TCP connections through the configured upstream proxy (SOCKS5 or HTTP CONNECT,
 * with optional username/password). This is the SINGLE dial implementation exercised by BOTH the
 * pre-flight [Validator] and the live LocalSocksServer, so validation tests the exact live path.
 *
 * Implementations MUST protect() the raw socket (via the injected [SocketProtector]) before
 * connecting, and MUST map every failure onto a typed [ProxyError] (fail-closed: never return a
 * direct/un-tunnelled socket).
 */
interface UpstreamDialer {
    /**
     * @param host destination hostname or IPv4 literal. For SOCKS5, names are sent for remote
     *   resolution (no device DNS). For HTTP, the CONNECT authority is used.
     * @param port destination port.
     */
    suspend fun connect(host: String, port: Int): DialResult
}

/** Pre-flight validator: real connect + real handshake + real probe, before the VPN is allowed up. */
interface Validator {
    suspend fun validate(config: ProxyConfig): ValidationResult
}
