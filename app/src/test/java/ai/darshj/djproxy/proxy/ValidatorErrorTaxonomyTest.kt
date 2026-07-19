package ai.darshj.djproxy.proxy

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Exercises every branch of the pre-flight error taxonomy against a real in-process TCP proxy that
 * scripts exact SOCKS5 / HTTP bytes per scenario. No mocking of sockets: the [PreflightValidator]
 * runs its true connect + handshake + probe path against a loopback [ServerSocket].
 */
class ValidatorErrorTaxonomyTest {

    private val fakes = mutableListOf<FakeProxy>()

    @After
    fun tearDown() {
        fakes.forEach { it.close() }
        fakes.clear()
    }

    private fun fake(handler: (DataInputStream, OutputStream) -> Unit): FakeProxy =
        FakeProxy(handler).also { fakes.add(it) }

    private fun socks5(host: String, port: Int, auth: Boolean = false) = ProxyConfig(
        type = ProxyType.SOCKS5,
        host = host,
        port = port,
        username = if (auth) "user" else "",
        password = if (auth) "pass" else "",
    )

    private fun http(host: String, port: Int, auth: Boolean = false) = ProxyConfig(
        type = ProxyType.HTTP,
        host = host,
        port = port,
        username = if (auth) "user" else "",
        password = if (auth) "pass" else "",
    )

    /** Short timeouts keep the timeout branch fast and deterministic. */
    private fun validator() = PreflightValidator(connectTimeoutMs = 2_000, ioTimeoutMs = 600)

    private fun validate(config: ProxyConfig): ValidationResult =
        runBlocking { validator().validate(config) }

    private fun failure(result: ValidationResult): ProxyError {
        assertTrue("expected Failure but got $result", result is ValidationResult.Failure)
        return (result as ValidationResult.Failure).error
    }

    // ---- 1. DNS resolution failed ----

    @Test
    fun dnsResolutionFailed() {
        // .invalid is guaranteed non-resolvable (RFC 6761).
        val result = validate(socks5("nonexistent-djproxy-host.invalid", 1080))
        val err = failure(result)
        assertTrue("got $err", err is ProxyError.DnsResolutionFailed)
    }

    // ---- 2. Connection refused ----

    @Test
    fun connectionRefused() {
        // Grab a port, close it → nothing is listening there.
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = validate(socks5("127.0.0.1", deadPort))
        val err = failure(result)
        assertTrue("got $err", err is ProxyError.ConnectionRefused)
    }

    // ---- 3. Timeout (peer accepts but never speaks) ----

    @Test
    fun timeoutDuringHandshake() {
        val proxy = fake { _, _ -> Thread.sleep(3_000) } // accept, stay silent past the 600ms io timeout
        val err = failure(validate(socks5("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.Timeout)
    }

    // ---- 4. Auth rejected (SOCKS5 RFC 1929) ----

    @Test
    fun socksAuthRejected() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write(byteArrayOf(0x05, 0x02)); out.flush() // choose username/password
            readAuth(din)
            out.write(byteArrayOf(0x01, 0x01)); out.flush() // status != 0 → rejected
        }
        val err = failure(validate(socks5("127.0.0.1", proxy.port, auth = true)))
        assertTrue("got $err", err is ProxyError.AuthRejected)
    }

    // ---- 5. Wrong proxy type: SOCKS5 configured, peer speaks HTTP ----

    @Test
    fun notASocks5Server() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); out.flush()
        }
        val err = failure(validate(socks5("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.NotASocks5Server)
    }

    // ---- 6. HTTP proxy returned an error status ----

    @Test
    fun httpStatusError() {
        val proxy = fake { din, out ->
            readHttpHead(din)
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); out.flush()
        }
        val err = failure(validate(http("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.HttpStatus)
        assertEquals(502, (err as ProxyError.HttpStatus).code)
    }

    // ---- 6b. HTTP proxy returned an error status *with a body* (must not hang / bleed) ----

    @Test
    fun httpStatusErrorWithBody() {
        val proxy = fake { din, out ->
            readHttpHead(din)
            val body = "<html>blocked</html>"
            out.write(
                ("HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray()
            )
            out.flush()
        }
        val err = failure(validate(http("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.HttpStatus)
        assertEquals(403, (err as ProxyError.HttpStatus).code)
    }

    // ---- 7. Auth rejected (HTTP 407) ----

    @Test
    fun httpAuthRejected() {
        val proxy = fake { din, out ->
            readHttpHead(din)
            out.write(
                "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n".toByteArray()
            )
            out.flush()
        }
        val err = failure(validate(http("127.0.0.1", proxy.port, auth = true)))
        assertTrue("got $err", err is ProxyError.AuthRejected)
    }

    // ---- 8. Proxy refused to open the tunnel (SOCKS REP = 0x05) ----

    @Test
    fun connectRefusedByProxy() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            readSocksRequest(din)
            // REP=0x05 connection refused, BND 0.0.0.0:0
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
        }
        val err = failure(validate(socks5("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.ConnectRefusedByProxy)
    }

    // ---- 9. Probe failed (handshake OK, proxy closes before probe reply) ----

    @Test
    fun probeFailed() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            readSocksRequest(din)
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            // Do NOT answer the probe; close the tunnel.
        }
        val err = failure(validate(socks5("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.ProbeFailed)
    }

    // ---- 10. SOCKS5 success ----

    @Test
    fun socksSuccess() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            readSocksRequest(din)
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            readHttpHead(din) // the probe GET
            out.write(
                "HTTP/1.1 204 No Content\r\nX-Client-Ip: 203.0.113.7\r\n\r\n".toByteArray()
            )
            out.flush()
            drain(din)
        }
        val result = validate(socks5("127.0.0.1", proxy.port))
        assertTrue("expected Success but got $result", result is ValidationResult.Success)
        val ok = result as ValidationResult.Success
        assertEquals(204, ok.probeStatus)
        assertEquals("203.0.113.7", ok.exitIp)
    }

    // ---- 11. HTTP CONNECT success ----

    @Test
    fun httpSuccess() {
        val proxy = fake { din, out ->
            readHttpHead(din) // CONNECT
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); out.flush()
            readHttpHead(din) // probe GET
            out.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray()); out.flush()
            drain(din)
        }
        val result = validate(http("127.0.0.1", proxy.port))
        assertTrue("expected Success but got $result", result is ValidationResult.Success)
        assertEquals(204, (result as ValidationResult.Success).probeStatus)
    }

    // ---- 12. Wrong proxy type: HTTP configured, peer speaks SOCKS ----

    @Test
    fun httpConfiguredButPeerSpeaksSocks() {
        val proxy = fake { din, out ->
            readHttpHead(din)                                // read the CONNECT so we don't RST the writer
            out.write(byteArrayOf(0x05, 0x00)); out.flush()  // SOCKS-shaped reply, then close
        }
        val err = failure(validate(http("127.0.0.1", proxy.port)))
        assertTrue("got $err", err is ProxyError.HandshakeMalformed)
    }

    // ---- Direct dialer/handshake checks (not routed through the probe) ----

    @Test
    fun socks5DialerReachesDestinationWithoutAuth() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            readSocksRequest(din)
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            drain(din)
        }
        val cfg = socks5("127.0.0.1", proxy.port)
        val socket = Socket("127.0.0.1", proxy.port).apply { soTimeout = 2_000 }
        val result = Socks5Dialer(cfg).connect(socket, "example.com", 443)
        assertTrue("got $result", result is DialResult.Ok)
        socket.close()
    }

    @Test
    fun udpAssociateReturnsRelayEndpoint() {
        val proxy = fake { din, out ->
            readGreeting(din)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            readSocksRequest(din)
            // BND 127.0.0.1:9999
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, (9999 shr 8).toByte(), (9999 and 0xFF).toByte()))
            out.flush()
            drain(din)
        }
        val cfg = socks5("127.0.0.1", proxy.port)
        val socket = Socket("127.0.0.1", proxy.port).apply { soTimeout = 2_000 }
        val result = Socks5Dialer(cfg).associate(socket)
        assertTrue("got $result", result is Socks5Dialer.AssociateResult.Ok)
        val ok = result as Socks5Dialer.AssociateResult.Ok
        assertEquals("127.0.0.1", ok.relayHost)
        assertEquals(9999, ok.relayPort)
        socket.close()
    }

    @Test
    fun socks5UdpCodecRoundTrips() {
        val encoded = Socks5UdpCodec.encapsulate("8.8.8.8", 53, byteArrayOf(1, 2, 3, 4, 5))
        val decoded = Socks5UdpCodec.decapsulate(encoded, encoded.size)
        assertNotNull(decoded)
        assertEquals("8.8.8.8", decoded!!.srcHost)
        assertEquals(53, decoded.srcPort)
        assertEquals(5, decoded.payload.size)
        assertEquals(1.toByte(), decoded.payload[0])
    }

    // ---- fake proxy + byte helpers ----

    private class FakeProxy(handler: (DataInputStream, OutputStream) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val acceptor: Thread = thread(isDaemon = true, name = "fake-proxy-$port") {
            try {
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(isDaemon = true) {
                        client.use { c ->
                            try {
                                handler(DataInputStream(c.getInputStream()), c.getOutputStream())
                            } catch (_: Exception) {
                                // scenario ended / peer closed
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // server closed
            }
        }

        override fun close() {
            try {
                server.close()
            } catch (_: Exception) {
            }
            acceptor.interrupt()
        }
    }

    companion object {
        /** Consumes the SOCKS5 greeting: VER, NMETHODS, METHODS. */
        fun readGreeting(din: DataInputStream) {
            din.readUnsignedByte() // VER
            val n = din.readUnsignedByte()
            repeat(n) { din.readUnsignedByte() }
        }

        /** Consumes an RFC 1929 auth message: VER, ULEN, UNAME, PLEN, PASSWD. */
        fun readAuth(din: DataInputStream) {
            din.readUnsignedByte() // VER
            val ulen = din.readUnsignedByte()
            repeat(ulen) { din.readUnsignedByte() }
            val plen = din.readUnsignedByte()
            repeat(plen) { din.readUnsignedByte() }
        }

        /** Consumes a SOCKS5 request: VER, CMD, RSV, ATYP, ADDR, PORT. */
        fun readSocksRequest(din: DataInputStream) {
            din.readUnsignedByte() // VER
            din.readUnsignedByte() // CMD
            din.readUnsignedByte() // RSV
            when (din.readUnsignedByte()) { // ATYP
                0x01 -> repeat(4) { din.readUnsignedByte() }
                0x03 -> {
                    val len = din.readUnsignedByte()
                    repeat(len) { din.readUnsignedByte() }
                }
                0x04 -> repeat(16) { din.readUnsignedByte() }
            }
            din.readUnsignedByte(); din.readUnsignedByte() // PORT
        }

        /** Reads an HTTP head up to CRLFCRLF. */
        fun readHttpHead(ins: InputStream) {
            var m = 0
            while (true) {
                val b = ins.read()
                if (b == -1) break
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
        }

        /** Reads until the peer closes, so the socket stays open until the client is done. */
        fun drain(ins: InputStream) {
            try {
                while (ins.read() != -1) { /* discard */ }
            } catch (_: Exception) {
            }
        }
    }
}
