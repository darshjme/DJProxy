package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * HTTP CONNECT client (RFC 7231 §4.3.6) with RFC 7617 Basic proxy authentication.
 *
 * Operates on an already-connected, already-protected [Socket]. Sends a `CONNECT host:port` request
 * and classifies the status line:
 *   - 200 → tunnel established; the socket is now a clean byte pipe to the destination.
 *   - 407 → Proxy-Authentication-Required → [ProxyError.AuthRejected].
 *   - 403 / 502 / any other 4xx-5xx → [ProxyError.HttpStatus] with the code+reason verbatim.
 *   - no `HTTP/` status line → the peer is not an HTTP proxy (e.g. a SOCKS5 server) →
 *     [ProxyError.HandshakeMalformed].
 *
 * Body handling: [ProxyIo.readHead] stops exactly at the header terminator, so a proxy that returns
 * an explanatory HTML body on a 403/502 neither hangs us nor bleeds into the tunnel; the body is
 * left unread and discarded when [ProxyDialer] closes the failed socket.
 */
class HttpConnectDialer(private val config: ProxyConfig) {

    fun connect(socket: Socket, host: String, port: Int): DialResult {
        return try {
            val authority = "$host:$port"
            val req = StringBuilder()
            req.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            req.append("Host: ").append(authority).append("\r\n")
            if (config.hasAuth) {
                val token = base64Encode("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
                req.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
            }
            req.append("Proxy-Connection: keep-alive\r\n")
            req.append("User-Agent: DJProxy\r\n")
            req.append("\r\n")

            val out = socket.getOutputStream()
            out.write(req.toString().toByteArray(Charsets.US_ASCII))
            out.flush()

            val head = ProxyIo.readHead(socket.getInputStream())
            val status = parseHttpStatusLine(head)
                ?: return DialResult.Fail(
                    ProxyError.HandshakeMalformed("no HTTP status line — is this really an HTTP proxy?")
                )

            when (status.code) {
                200 -> DialResult.Ok(socket)
                407 -> DialResult.Fail(ProxyError.AuthRejected)
                else -> DialResult.Fail(
                    ProxyError.HttpStatus(status.code, status.reason.ifEmpty { httpReason(status.code) })
                )
            }
        } catch (_: SocketTimeoutException) {
            DialResult.Fail(ProxyError.Timeout("http-connect"))
        } catch (_: EOFException) {
            DialResult.Fail(ProxyError.HandshakeMalformed("connection closed during CONNECT — not an HTTP proxy?"))
        } catch (e: IOException) {
            DialResult.Fail(ProxyError.Io(e.message ?: "http connect io error"))
        }
    }

    private fun httpReason(code: Int): String = when (code) {
        400 -> "Bad Request"
        403 -> "Forbidden"
        405 -> "Method Not Allowed"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Error"
    }
}
