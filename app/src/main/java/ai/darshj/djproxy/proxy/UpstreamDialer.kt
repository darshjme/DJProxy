package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The single concrete [UpstreamDialer]. It owns exactly one responsibility: take a destination
 * host:port, open a raw TCP socket to the configured *proxy*, [SocketProtector.protect] it so the
 * bytes leave the device instead of looping back into the tun, and then hand the connected socket
 * to the correct handshake ([Socks5Dialer] or [HttpConnectDialer]) which tunnels it to the
 * destination.
 *
 * Fail-closed: on any error the socket is closed and a typed [ProxyError] is returned; a caller can
 * never receive a socket that is connected to the destination *directly* instead of through the
 * proxy.
 *
 * This is the identical dial path used by both the pre-flight [PreflightValidator] and the live
 * LocalSocksServer, so a green pre-flight proves the exact runtime path works.
 */
class ProxyDialer(
    private val config: ProxyConfig,
    private val protector: SocketProtector,
    private val connectTimeoutMs: Int = 8_000,
    private val ioTimeoutMs: Int = 8_000,
) : UpstreamDialer {

    override suspend fun connect(host: String, port: Int): DialResult = withContext(Dispatchers.IO) {
        // Resolve the *proxy* address up-front so a bad proxy host is distinguishable from a bad
        // destination. Runs before the VPN is up (pre-flight) or on a protected socket (live), so
        // this device-DNS lookup never loops through the tun.
        val proxyAddr: InetAddress = try {
            InetAddress.getByName(config.host)
        } catch (_: UnknownHostException) {
            return@withContext DialResult.Fail(ProxyError.DnsResolutionFailed(config.host))
        }

        val socket = Socket()
        try {
            // MUST protect before connect: the tun is already up on the live path, and the FIRST
            // packet of the TCP handshake must escape the route or we deadlock against ourselves.
            protector.protect(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(proxyAddr, config.port), connectTimeoutMs)
            socket.soTimeout = ioTimeoutMs
        } catch (_: SocketTimeoutException) {
            ProxyIo.closeQuietly(socket)
            return@withContext DialResult.Fail(ProxyError.Timeout("connect"))
        } catch (_: ConnectException) {
            ProxyIo.closeQuietly(socket)
            return@withContext DialResult.Fail(ProxyError.ConnectionRefused(config.host, config.port))
        } catch (_: NoRouteToHostException) {
            ProxyIo.closeQuietly(socket)
            return@withContext DialResult.Fail(ProxyError.ConnectionRefused(config.host, config.port))
        } catch (e: IOException) {
            ProxyIo.closeQuietly(socket)
            return@withContext DialResult.Fail(ProxyError.Io(e.message ?: "connect failed"))
        }

        val result = when (config.type) {
            ProxyType.SOCKS5 -> Socks5Dialer(config).connect(socket, host, port)
            ProxyType.HTTP -> HttpConnectDialer(config).connect(socket, host, port)
        }
        if (result is DialResult.Fail) ProxyIo.closeQuietly(socket)
        result
    }
}

/**
 * Byte-level stream helpers shared by every handshake in this package. Deliberately raw: an HTTP
 * `CONNECT` reply must be read one byte at a time up to the `\r\n\r\n` terminator and NOT a byte
 * further, or a buffering reader would swallow the first bytes of tunnel payload.
 */
internal object ProxyIo {

    /** Reads one byte, throwing [EOFException] on a premature close instead of returning -1. */
    fun readByteOrThrow(ins: InputStream): Int {
        val b = ins.read()
        if (b == -1) throw EOFException("stream closed mid-handshake")
        return b and 0xFF
    }

    /** Reads exactly [n] bytes or throws [EOFException]. */
    fun readFully(ins: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r == -1) throw EOFException("stream closed after $off/$n bytes")
            off += r
        }
        return buf
    }

    /**
     * Reads an HTTP head: everything up to and including the first CRLFCRLF, leaving the stream
     * positioned exactly at the body / tunnel start. Stops early (returning what it has) if the
     * peer closes after sending *some* bytes — that lets us classify a non-HTTP peer that dumps a
     * few bytes and hangs up. Throws [EOFException] only if the peer sent nothing at all.
     */
    fun readHead(ins: InputStream, limit: Int = 65_536): String {
        val out = ByteArrayOutputStream()
        var m = 0 // how many bytes of the CRLFCRLF terminator we have matched
        while (out.size() < limit) {
            val b = ins.read()
            if (b == -1) {
                if (out.size() == 0) throw EOFException("peer closed with no response")
                break
            }
            out.write(b)
            m = when {
                m == 0 && b == 13 -> 1
                m == 1 && b == 10 -> 2
                m == 2 && b == 13 -> 3
                m == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (m == 4) break
        }
        return out.toString("US-ASCII")
    }

    fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
            // best-effort
        }
    }
}

/** RFC 4648 Base64 (no line breaks). Hand-rolled to avoid android.util.Base64 (unavailable off-device) */
/*  and java.util.Base64 (API 26) so the same code runs on API 24 and in JVM unit tests. */
internal fun base64Encode(data: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= data.size) {
        val n = ((data[i].toInt() and 0xFF) shl 16) or
            ((data[i + 1].toInt() and 0xFF) shl 8) or
            (data[i + 2].toInt() and 0xFF)
        sb.append(alphabet[(n ushr 18) and 0x3F])
        sb.append(alphabet[(n ushr 12) and 0x3F])
        sb.append(alphabet[(n ushr 6) and 0x3F])
        sb.append(alphabet[n and 0x3F])
        i += 3
    }
    when (data.size - i) {
        1 -> {
            val n = (data[i].toInt() and 0xFF) shl 16
            sb.append(alphabet[(n ushr 18) and 0x3F])
            sb.append(alphabet[(n ushr 12) and 0x3F])
            sb.append("==")
        }
        2 -> {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
            sb.append(alphabet[(n ushr 18) and 0x3F])
            sb.append(alphabet[(n ushr 12) and 0x3F])
            sb.append(alphabet[(n ushr 6) and 0x3F])
            sb.append('=')
        }
    }
    return sb.toString()
}

/** Parsed first line of an HTTP response. [code] is -1 when the line was not an HTTP status line. */
internal data class HttpStatusLine(val code: Int, val reason: String)

/** Parses `HTTP/1.1 200 Connection established` → (200, "Connection established"); null if not HTTP. */
internal fun parseHttpStatusLine(head: String): HttpStatusLine? {
    val firstLine = head.substringBefore("\r\n").ifEmpty { head.substringBefore("\n") }
    if (!firstLine.startsWith("HTTP/")) return null
    val parts = firstLine.split(' ', limit = 3)
    if (parts.size < 2) return null
    val code = parts[1].toIntOrNull() ?: return null
    val reason = if (parts.size == 3) parts[2].trim() else ""
    return HttpStatusLine(code, reason)
}
