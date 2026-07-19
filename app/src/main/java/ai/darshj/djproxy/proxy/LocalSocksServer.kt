package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The loopback SOCKS5 policy front. The native engine (hev-socks5-tunnel) terminates every TCP flow
 * from the tun and re-offers it here as a SOCKS5 CONNECT on 127.0.0.1:[listenPort]. This server
 * accepts that connection, then dials the *real* upstream proxy for the requested destination through
 * the one shared [ProxyDialer] — so every byte that leaves the device does so through the user's
 * proxy, over the single [SocketProtector] seam, exactly like the pre-flight validator proved.
 *
 * It is deliberately the ONLY policy authority: it speaks no-auth SOCKS5 to the trusted loopback peer
 * (hev), but forwards using the configured proxy's own auth. UDP ASSOCIATE is refused here — the
 * default UDP-drop leak guarantee is enforced at the tun; this front never opens a UDP path.
 *
 * Fail-closed: any handshake/dial failure closes both sockets; a caller (hev) never gets a half-open
 * tunnel that could fall back to a direct connection.
 */
class LocalSocksServer(
    private val config: ProxyConfig,
    private val protector: SocketProtector,
    private val onConnectionOpened: () -> Unit = {},
    private val onConnectionClosed: () -> Unit = {},
    /** In-tun DNS sentinel; a CONNECT to [dnsSentinelHost]:53 is answered locally, not dialed upstream. */
    private val dnsSentinelHost: String? = null,
    /** Tunnelled DNS resolver used for DNS-over-TCP to the sentinel (the OS resolver's TCP fallback). */
    private val dnsResolve: (suspend (ByteArray) -> ByteArray?)? = null,
) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    /** The loopback port hev must dial. Valid only after [start]; -1 before/after. */
    val listenPort: Int get() = server?.localPort ?: -1

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
        server = ss
        running = true
        Thread({ acceptLoop(ss) }, "djproxy-socks-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                if (running) continue else break
            }
            Thread({ handle(client) }, "djproxy-socks-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        onConnectionOpened()
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            // Bound the handshake: a peer that connects and then stalls half-open must not pin this
            // per-connection thread forever. Cleared to 0 before the (possibly idle) live pump.
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ins = client.getInputStream()
            val out = client.getOutputStream()

            if (!negotiateNoAuth(ins, out)) return

            val request = readRequest(ins) ?: run {
                writeReply(out, REP_GENERAL_FAILURE); return
            }
            if (request.cmd != CMD_CONNECT) {
                // Only CONNECT is a valid egress path; UDP ASSOCIATE / BIND are refused (UDP is
                // dropped at the tun by policy — no relay path exists through this front).
                writeReply(out, REP_CMD_UNSUPPORTED); return
            }

            // DNS-over-TCP to the in-tun sentinel (the resolver's TCP fallback for truncated answers)
            // is terminated locally via the tunnelled resolver, never dialed upstream to the
            // non-routable sentinel bogon.
            val resolver = dnsResolve
            if (resolver != null && request.host == dnsSentinelHost && request.port == DNS_PORT) {
                writeReply(out, REP_SUCCEEDED)
                serveDnsOverTcp(client, ins, out, resolver)
                return
            }

            // The one shared dial path: connect + handshake to the upstream proxy, protected.
            val dial = runBlocking { ProxyDialer(config, protector).connect(request.host, request.port) }
            when (dial) {
                is DialResult.Fail -> {
                    writeReply(out, repFor(dial.error))
                    return
                }
                is DialResult.Ok -> {
                    upstream = dial.socket
                    writeReply(out, REP_SUCCEEDED)
                    // A live tunnel may idle for long stretches; the handshake timeout must not reap it.
                    client.soTimeout = 0
                    pump(client, upstream)
                }
            }
        } catch (_: IOException) {
            // fail-closed (includes SocketTimeoutException on a stalled handshake)
        } finally {
            ProxyIo.closeQuietly(upstream)
            ProxyIo.closeQuietly(client)
            onConnectionClosed()
        }
    }

    /**
     * Serves DNS-over-TCP (RFC 7766, 2-byte length prefix) for the in-tun sentinel by resolving each
     * framed query through the tunnelled [resolver] and framing the answer back. Loops until the peer
     * closes or a resolve fails (fail-closed).
     */
    private fun serveDnsOverTcp(
        client: Socket,
        ins: InputStream,
        out: OutputStream,
        resolver: suspend (ByteArray) -> ByteArray?,
    ) {
        try {
            while (running && !client.isClosed) {
                val lenBuf = ProxyIo.readFully(ins, 2)
                val qLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                if (qLen <= 0 || qLen > 0xFFFF) break
                val query = ProxyIo.readFully(ins, qLen)
                val answer = runBlocking { resolver(query) } ?: break
                val framed = ByteArray(2 + answer.size)
                framed[0] = ((answer.size ushr 8) and 0xFF).toByte()
                framed[1] = (answer.size and 0xFF).toByte()
                System.arraycopy(answer, 0, framed, 2, answer.size)
                out.write(framed)
                out.flush()
            }
        } catch (_: IOException) {
            // peer closed / timed out — fail-closed
        }
    }

    // ---- SOCKS5 server handshake (RFC 1928) -----------------------------------------------------

    /** Reads the client greeting and selects "no authentication" (loopback peer is trusted). */
    private fun negotiateNoAuth(ins: InputStream, out: OutputStream): Boolean {
        val ver = ins.read()
        if (ver != VER) return false
        val nMethods = ins.read()
        if (nMethods <= 0) return false
        ProxyIo.readFully(ins, nMethods) // discard offered methods; loopback is trusted
        out.write(byteArrayOf(VER.toByte(), METHOD_NONE.toByte()))
        out.flush()
        return true
    }

    private data class Request(val cmd: Int, val host: String, val port: Int)

    /** Reads VER|CMD|RSV|ATYP|ADDR|PORT. Returns null if malformed / unsupported address type. */
    private fun readRequest(ins: InputStream): Request? {
        val ver = ins.read(); if (ver != VER) return null
        val cmd = ins.read(); if (cmd < 0) return null
        ins.read() // RSV
        val host: String = when (ins.read()) {
            ATYP_IPV4 -> {
                val a = ProxyIo.readFully(ins, 4)
                "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
            }
            ATYP_DOMAIN -> {
                val len = ins.read(); if (len <= 0) return null
                String(ProxyIo.readFully(ins, len), Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val a = ProxyIo.readFully(ins, 16)
                a.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
            }
            else -> return null
        }
        val p = ProxyIo.readFully(ins, 2)
        val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        return Request(cmd, host, port)
    }

    /** Writes VER|REP|RSV|ATYP=IPv4|0.0.0.0|0 — the bound address is irrelevant to a CONNECT client. */
    private fun writeReply(out: OutputStream, rep: Int) {
        out.write(byteArrayOf(VER.toByte(), rep.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun repFor(error: ProxyError): Int = when (error) {
        is ProxyError.ConnectionRefused, is ProxyError.ConnectRefusedByProxy -> REP_CONN_REFUSED
        is ProxyError.DnsResolutionFailed -> REP_HOST_UNREACHABLE
        is ProxyError.Timeout -> REP_TTL_EXPIRED
        else -> REP_GENERAL_FAILURE
    }

    // ---- bidirectional byte pump ----------------------------------------------------------------

    /** Copies bytes both ways until either side closes; closing one direction tears the flow down. */
    private fun pump(a: Socket, b: Socket) {
        val t1 = Thread({ copy(a.getInputStream(), b.getOutputStream(), a, b) }, "djproxy-pump-up")
        t1.isDaemon = true
        t1.start()
        // Run the other direction on the current (connection) thread so handle() blocks until done.
        copy(b.getInputStream(), a.getOutputStream(), a, b)
        runCatching { t1.join(PUMP_JOIN_MS) }
    }

    private fun copy(src: InputStream, dst: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = src.read(buf)
                if (n == -1) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: IOException) {
            // peer closed / reset — normal teardown
        } finally {
            // Closing both ends unblocks the sibling copy thread so the flow is fully reaped.
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val BUFFER_SIZE = 32 * 1024
        private const val PUMP_JOIN_MS = 2_000L
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val DNS_PORT = 53

        private const val VER = 0x05
        private const val METHOD_NONE = 0x00
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        private const val REP_SUCCEEDED = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_CONN_REFUSED = 0x05
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_TTL_EXPIRED = 0x06
        private const val REP_CMD_UNSUPPORTED = 0x07
    }
}
