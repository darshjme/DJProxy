package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The pre-flight validator — exactly what the **Apply** button calls before the VPN is allowed up.
 *
 * It performs the *real* path, not a ping: real TCP connect to the proxy, real protocol handshake
 * ([Socks5Dialer] / [HttpConnectDialer] via the shared [ProxyDialer]), and a real HTTP probe
 * request *through* the established tunnel. Because it reuses [ProxyDialer] — the same object the
 * live LocalSocksServer dials with — a green pre-flight proves the live path, byte for byte.
 *
 * Every failure is one of the closed [ProxyError] cases, each carrying a human message and a
 * one-line fix hint. The distinct outcomes it separates:
 *   - [ProxyError.DnsResolutionFailed]   proxy host does not resolve
 *   - [ProxyError.ConnectionRefused]     nothing listening on host:port
 *   - [ProxyError.Timeout]               connect/handshake black-holed
 *   - [ProxyError.AuthRejected]          credentials refused (SOCKS 1929 / HTTP 407)
 *   - [ProxyError.NotASocks5Server]      SOCKS5 selected but peer speaks HTTP
 *   - [ProxyError.HandshakeMalformed]    HTTP selected but peer isn't HTTP / garbled handshake
 *   - [ProxyError.HttpStatus]            HTTP proxy returned 403/502/…
 *   - [ProxyError.ConnectRefusedByProxy] proxy refused to open the tunnel (SOCKS REP≠0)
 *   - [ProxyError.ProbeFailed]           handshake ok but the probe request never came back
 *
 * @param protector the ONE protect() seam; defaults to a no-op for pre-flight (the tun is not up
 *   yet), but the vpn lane may pass the real protector so validation and runtime are identical.
 */
class PreflightValidator(
    private val protector: SocketProtector = SocketProtector { true },
    private val connectTimeoutMs: Int = 8_000,
    private val ioTimeoutMs: Int = 8_000,
    private val probeHost: String = DEFAULT_PROBE_HOST,
    private val probePort: Int = DEFAULT_PROBE_PORT,
    private val probePath: String = DEFAULT_PROBE_PATH,
) : Validator {

    companion object {
        // A tiny, cache-friendly "204 No Content" captive-portal endpoint reachable over plain HTTP.
        const val DEFAULT_PROBE_HOST = "www.gstatic.com"
        const val DEFAULT_PROBE_PORT = 80
        const val DEFAULT_PROBE_PATH = "/generate_204"
    }

    override suspend fun validate(config: ProxyConfig): ValidationResult = withContext(Dispatchers.IO) {
        // Defensive: an empty host would otherwise resolve to loopback. UI validates first, but the
        // validator must never green-light a blank target.
        if (config.host.isBlank()) {
            return@withContext ValidationResult.Failure(ProxyError.DnsResolutionFailed(config.host))
        }

        val dialer = ProxyDialer(config, protector, connectTimeoutMs, ioTimeoutMs)

        // Real connect + real handshake, tunnelling to the probe endpoint.
        val dial = dialer.connect(probeHost, probePort)
        val socket = when (dial) {
            is DialResult.Fail -> return@withContext ValidationResult.Failure(dial.error)
            is DialResult.Ok -> dial.socket
        }

        try {
            socket.soTimeout = ioTimeoutMs
            val out = socket.getOutputStream()
            val req = buildString {
                append("GET ").append(probePath).append(" HTTP/1.1\r\n")
                append("Host: ").append(probeHost).append("\r\n")
                append("User-Agent: DJProxy\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            val startNs = System.nanoTime()
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val head = ProxyIo.readHead(socket.getInputStream())
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000

            val status = parseHttpStatusLine(head)
                ?: return@withContext ValidationResult.Failure(
                    ProxyError.ProbeFailed("proxy returned a non-HTTP reply to the probe")
                )

            ValidationResult.Success(
                latencyMs = latencyMs,
                probeStatus = status.code,
                exitIp = extractExitIp(head),
            )
        } catch (_: SocketTimeoutException) {
            ValidationResult.Failure(ProxyError.ProbeFailed("probe timed out through the proxy"))
        } catch (_: EOFException) {
            ValidationResult.Failure(ProxyError.ProbeFailed("proxy closed the tunnel before replying"))
        } catch (e: IOException) {
            ValidationResult.Failure(ProxyError.ProbeFailed(e.message ?: "probe I/O error"))
        } finally {
            ProxyIo.closeQuietly(socket)
        }
    }

    /**
     * Best-effort exit-IP extraction from probe response headers. Many echo/captive endpoints reflect
     * the observed client IP in a header; if present it confirms the egress address. Null otherwise
     * (e.g. a bare 204) — never a hard failure.
     */
    private fun extractExitIp(head: String): String? {
        val keys = listOf("x-client-ip", "x-real-ip", "x-forwarded-for", "cf-connecting-ip")
        for (line in head.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            if (name in keys) {
                val value = line.substring(idx + 1).trim().substringBefore(',').trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }
}
