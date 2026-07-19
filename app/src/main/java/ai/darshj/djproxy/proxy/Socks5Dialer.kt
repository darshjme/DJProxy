package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.net.stringToIp
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * SOCKS5 client — RFC 1928 (greeting + CONNECT / UDP ASSOCIATE) and RFC 1929 (username/password
 * sub-negotiation). Operates on an already-connected, already-protected [Socket]; it never opens
 * sockets itself (that is [ProxyDialer]'s job). CONNECT is the live egress path; the UDP-ASSOCIATE
 * primitive is retained (and unit-tested) for a future UDP relay but is NOT wired into any live
 * path — UDP is unconditionally dropped at the tun to keep the WebRTC/QUIC leak closed.
 *
 * Names are always sent as ATYP=DOMAINNAME (0x03) so the *proxy* resolves them — the device
 * resolver is never consulted, closing the DNS-leak vector.
 */
class Socks5Dialer(private val config: ProxyConfig) {

    companion object {
        const val VER = 0x05
        const val CMD_CONNECT = 0x01
        const val CMD_UDP_ASSOCIATE = 0x03

        const val ATYP_IPV4 = 0x01
        const val ATYP_DOMAIN = 0x03
        const val ATYP_IPV6 = 0x04

        const val METHOD_NONE = 0x00
        const val METHOD_USERPASS = 0x02
        const val METHOD_NONE_ACCEPTABLE = 0xFF

        const val AUTH_VER = 0x01
        const val AUTH_OK = 0x00

        // RFC 1928 reply (REP) codes.
        const val REP_SUCCEEDED = 0x00
        const val REP_GENERAL_FAILURE = 0x01
        const val REP_NOT_ALLOWED = 0x02
        const val REP_NET_UNREACHABLE = 0x03
        const val REP_HOST_UNREACHABLE = 0x04
        const val REP_CONN_REFUSED = 0x05
        const val REP_TTL_EXPIRED = 0x06
        const val REP_CMD_UNSUPPORTED = 0x07
        const val REP_ATYP_UNSUPPORTED = 0x08
    }

    /** Result of a UDP ASSOCIATE: the relay endpoint the client must send its encapsulated UDP to. */
    sealed interface AssociateResult {
        data class Ok(val relayHost: String, val relayPort: Int) : AssociateResult
        data class Fail(val error: ProxyError) : AssociateResult
    }

    /**
     * Performs greeting + auth + CONNECT to [host]:[port] on [socket]. On success the socket is a
     * clean byte tunnel to the destination and is returned in [DialResult.Ok]. On failure a typed
     * [ProxyError] is returned; the caller owns closing the socket.
     */
    fun connect(socket: Socket, host: String, port: Int): DialResult {
        return try {
            val ins = socket.getInputStream()
            val out = socket.getOutputStream()

            greetAndAuth(ins, out)?.let { return DialResult.Fail(it) }

            sendRequest(out, CMD_CONNECT, host, port)
            val reply = readReply(ins)
            if (reply.error != null) DialResult.Fail(reply.error) else DialResult.Ok(socket)
        } catch (_: SocketTimeoutException) {
            DialResult.Fail(ProxyError.Timeout("socks5-handshake"))
        } catch (_: EOFException) {
            DialResult.Fail(ProxyError.HandshakeMalformed("connection closed during handshake"))
        } catch (e: IOException) {
            DialResult.Fail(ProxyError.Io(e.message ?: "socks5 io error"))
        }
    }

    /**
     * Performs greeting + auth + UDP ASSOCIATE. [bindHost]/[bindPort] declare the address the client
     * will send UDP *from* (0.0.0.0:0 = "any", the common choice). Returns the relay endpoint.
     */
    fun associate(
        socket: Socket,
        bindHost: String = "0.0.0.0",
        bindPort: Int = 0,
    ): AssociateResult {
        return try {
            val ins = socket.getInputStream()
            val out = socket.getOutputStream()

            greetAndAuth(ins, out)?.let { return AssociateResult.Fail(it) }

            sendRequest(out, CMD_UDP_ASSOCIATE, bindHost, bindPort)
            val reply = readReply(ins)
            when {
                reply.error != null -> AssociateResult.Fail(reply.error)
                reply.bndHost == null -> AssociateResult.Fail(
                    ProxyError.HandshakeMalformed("UDP associate returned no relay address")
                )
                else -> AssociateResult.Ok(reply.bndHost, reply.bndPort)
            }
        } catch (_: SocketTimeoutException) {
            AssociateResult.Fail(ProxyError.Timeout("socks5-udp-associate"))
        } catch (_: EOFException) {
            AssociateResult.Fail(ProxyError.HandshakeMalformed("connection closed during UDP associate"))
        } catch (e: IOException) {
            AssociateResult.Fail(ProxyError.Io(e.message ?: "socks5 io error"))
        }
    }

    // ---- handshake primitives ----

    /** RFC 1928 method negotiation + optional RFC 1929 auth. Returns null on success. */
    private fun greetAndAuth(ins: InputStream, out: OutputStream): ProxyError? {
        val methods = if (config.hasAuth) {
            byteArrayOf(METHOD_NONE.toByte(), METHOD_USERPASS.toByte())
        } else {
            byteArrayOf(METHOD_NONE.toByte())
        }
        out.write(byteArrayOf(VER.toByte(), methods.size.toByte()) + methods)
        out.flush()

        val ver = ProxyIo.readByteOrThrow(ins)
        // Anything not speaking 0x05 here is not a SOCKS5 server (classic case: an HTTP proxy that
        // starts replying "HTTP/1.1 ..." → first byte 'H' = 0x48).
        if (ver != VER) return ProxyError.NotASocks5Server

        return when (val method = ProxyIo.readByteOrThrow(ins)) {
            METHOD_NONE -> null
            METHOD_USERPASS -> if (config.hasAuth) userPassAuth(ins, out) else ProxyError.AuthRejected
            METHOD_NONE_ACCEPTABLE -> ProxyError.AuthRejected
            else -> ProxyError.HandshakeMalformed("server selected unsupported method 0x%02x".format(method))
        }
    }

    /** RFC 1929 username/password sub-negotiation. Returns null on success. */
    private fun userPassAuth(ins: InputStream, out: OutputStream): ProxyError? {
        val user = config.username.toByteArray(Charsets.UTF_8)
        val pass = config.password.toByteArray(Charsets.UTF_8)
        if (user.size > 255 || pass.size > 255) {
            return ProxyError.HandshakeMalformed("username/password exceeds 255 bytes")
        }
        val msg = ByteArray(3 + user.size + pass.size)
        var i = 0
        msg[i++] = AUTH_VER.toByte()
        msg[i++] = user.size.toByte()
        System.arraycopy(user, 0, msg, i, user.size); i += user.size
        msg[i++] = pass.size.toByte()
        System.arraycopy(pass, 0, msg, i, pass.size)
        out.write(msg)
        out.flush()

        val ver = ProxyIo.readByteOrThrow(ins)
        val status = ProxyIo.readByteOrThrow(ins)
        if (ver != AUTH_VER) return ProxyError.HandshakeMalformed("bad auth reply version 0x%02x".format(ver))
        return if (status == AUTH_OK) null else ProxyError.AuthRejected
    }

    /** Writes VER|CMD|RSV|ATYP|ADDR|PORT. Sends a domain name (0x03) unless [host] is an IPv4 literal. */
    private fun sendRequest(out: OutputStream, cmd: Int, host: String, port: Int) {
        val body = ByteArrayBuilder()
        body.b(VER)
        body.b(cmd)
        body.b(0x00) // RSV

        val ipv4 = runCatching { stringToIp(host) }.getOrNull()
        if (ipv4 != null) {
            body.b(ATYP_IPV4)
            body.b((ipv4 ushr 24) and 0xFF)
            body.b((ipv4 ushr 16) and 0xFF)
            body.b((ipv4 ushr 8) and 0xFF)
            body.b(ipv4 and 0xFF)
        } else {
            val name = host.toByteArray(Charsets.US_ASCII)
            require(name.size <= 255) { "hostname longer than 255 bytes" }
            body.b(ATYP_DOMAIN)
            body.b(name.size)
            body.raw(name)
        }
        body.b((port ushr 8) and 0xFF)
        body.b(port and 0xFF)

        out.write(body.toByteArray())
        out.flush()
    }

    private data class Reply(val error: ProxyError?, val bndHost: String?, val bndPort: Int)

    /** Reads VER|REP|RSV|ATYP|BND.ADDR|BND.PORT, fully draining the reply so the socket stays aligned. */
    private fun readReply(ins: InputStream): Reply {
        val ver = ProxyIo.readByteOrThrow(ins)
        if (ver != VER) return Reply(ProxyError.NotASocks5Server, null, 0)
        val rep = ProxyIo.readByteOrThrow(ins)
        ProxyIo.readByteOrThrow(ins) // RSV
        val atyp = ProxyIo.readByteOrThrow(ins)

        val bndHost: String = when (atyp) {
            ATYP_IPV4 -> {
                val a = ProxyIo.readFully(ins, 4)
                "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
            }
            ATYP_DOMAIN -> {
                val len = ProxyIo.readByteOrThrow(ins)
                String(ProxyIo.readFully(ins, len), Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val a = ProxyIo.readFully(ins, 16)
                a.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
            }
            else -> return Reply(
                ProxyError.HandshakeMalformed("reply has unknown address type 0x%02x".format(atyp)),
                null, 0,
            )
        }
        val portBytes = ProxyIo.readFully(ins, 2)
        val bndPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        return Reply(mapRep(rep), bndHost, bndPort)
    }

    private fun mapRep(rep: Int): ProxyError? = when (rep) {
        REP_SUCCEEDED -> null
        REP_NOT_ALLOWED -> ProxyError.ConnectRefusedByProxy("destination", 0)
        REP_CONN_REFUSED -> ProxyError.ConnectRefusedByProxy("destination", 0)
        REP_NET_UNREACHABLE -> ProxyError.Io("SOCKS5: network unreachable")
        REP_HOST_UNREACHABLE -> ProxyError.Io("SOCKS5: host unreachable")
        REP_TTL_EXPIRED -> ProxyError.Io("SOCKS5: TTL expired")
        REP_CMD_UNSUPPORTED -> ProxyError.HandshakeMalformed("SOCKS5: command not supported")
        REP_ATYP_UNSUPPORTED -> ProxyError.HandshakeMalformed("SOCKS5: address type not supported")
        REP_GENERAL_FAILURE -> ProxyError.Io("SOCKS5: general server failure")
        else -> ProxyError.Io("SOCKS5: reply code 0x%02x".format(rep))
    }

    /** Tiny growable byte builder — keeps request framing readable without pulling in extra deps. */
    private class ByteArrayBuilder {
        private val buf = java.io.ByteArrayOutputStream()
        fun b(v: Int) { buf.write(v and 0xFF) }
        fun raw(a: ByteArray) { buf.write(a) }
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}

/**
 * SOCKS5 UDP request-header codec (RFC 1928 §7). Retained and unit-tested for a future UDP relay;
 * not referenced by any live path today (UDP is dropped at the tun).
 */
object Socks5UdpCodec {

    /** Wraps a payload for the destination: RSV(2)=0 | FRAG(1)=0 | ATYP | ADDR | PORT | DATA. */
    fun encapsulate(destHost: String, destPort: Int, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(payload.size + 22)
        out.write(0x00); out.write(0x00) // RSV
        out.write(0x00)                  // FRAG (no fragmentation)
        val ipv4 = runCatching { stringToIp(destHost) }.getOrNull()
        if (ipv4 != null) {
            out.write(Socks5Dialer.ATYP_IPV4)
            out.write((ipv4 ushr 24) and 0xFF)
            out.write((ipv4 ushr 16) and 0xFF)
            out.write((ipv4 ushr 8) and 0xFF)
            out.write(ipv4 and 0xFF)
        } else {
            val name = destHost.toByteArray(Charsets.US_ASCII)
            out.write(Socks5Dialer.ATYP_DOMAIN)
            out.write(name.size)
            out.write(name)
        }
        out.write((destPort ushr 8) and 0xFF)
        out.write(destPort and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** Decoded inbound UDP datagram from the relay. */
    data class Decapsulated(val srcHost: String, val srcPort: Int, val payload: ByteArray)

    /** Strips the SOCKS5 UDP header from a datagram received from the relay. Null if malformed/fragmented. */
    fun decapsulate(datagram: ByteArray, length: Int): Decapsulated? {
        if (length < 10) return null
        // datagram[0..1] RSV, datagram[2] FRAG (we ignore reassembly and drop fragments).
        if (datagram[2].toInt() != 0) return null
        var p = 3
        val srcHost: String
        when (datagram[p++].toInt() and 0xFF) {
            Socks5Dialer.ATYP_IPV4 -> {
                if (p + 4 > length) return null
                srcHost = "${datagram[p].toInt() and 0xFF}.${datagram[p + 1].toInt() and 0xFF}." +
                    "${datagram[p + 2].toInt() and 0xFF}.${datagram[p + 3].toInt() and 0xFF}"
                p += 4
            }
            Socks5Dialer.ATYP_DOMAIN -> {
                val len = datagram[p++].toInt() and 0xFF
                if (p + len > length) return null
                srcHost = String(datagram, p, len, Charsets.US_ASCII)
                p += len
            }
            Socks5Dialer.ATYP_IPV6 -> {
                if (p + 16 > length) return null
                val sb = StringBuilder()
                for (i in 0 until 16) {
                    sb.append("%02x".format(datagram[p + i].toInt() and 0xFF))
                    if (i % 2 == 1 && i != 15) sb.append(':')
                }
                srcHost = sb.toString()
                p += 16
            }
            else -> return null
        }
        if (p + 2 > length) return null
        val srcPort = ((datagram[p].toInt() and 0xFF) shl 8) or (datagram[p + 1].toInt() and 0xFF)
        p += 2
        val payload = datagram.copyOfRange(p, length)
        return Decapsulated(srcHost, srcPort, payload)
    }
}
