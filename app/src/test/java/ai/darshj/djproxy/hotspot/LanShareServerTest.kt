package ai.darshj.djproxy.hotspot

import ai.darshj.djproxy.proxy.DialResult
import ai.darshj.djproxy.proxy.UpstreamDialer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [LanShareServer]: the pure request-parsing surface AND an end-to-end run of the LAN
 * proxy against a fake upstream dialer that echoes — proving a client's bytes are actually carried
 * through the injected dialer for both HTTP CONNECT and SOCKS5.
 */
class LanShareServerTest {

    private val closeables = CopyOnWriteArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
    }

    // ---- pure request parsing -------------------------------------------------------------------

    @Test
    fun parseRequestLine_valid_and_invalid() {
        val ok = LanShareServer.parseRequestLine("CONNECT example.com:443 HTTP/1.1")
        assertEquals("CONNECT", ok!!.method)
        assertEquals("example.com:443", ok.target)
        assertNull(LanShareServer.parseRequestLine("GARBAGE"))
        assertNull(LanShareServer.parseRequestLine("GET /x FTP/1.0"))
    }

    @Test
    fun hostPortFromConnect_defaults_ipv6_and_bad() {
        assertEquals("h" to 443, LanShareServer.hostPortFromConnect("h"))
        assertEquals("h" to 8443, LanShareServer.hostPortFromConnect("h:8443"))
        assertEquals("::1" to 443, LanShareServer.hostPortFromConnect("[::1]"))
        assertEquals("2001:db8::1" to 8080, LanShareServer.hostPortFromConnect("[2001:db8::1]:8080"))
        assertNull(LanShareServer.hostPortFromConnect("h:0"))
        assertNull(LanShareServer.hostPortFromConnect("h:notaport"))
    }

    @Test
    fun hostPortFromAbsoluteUri_splits_host_port_path() {
        val a = LanShareServer.hostPortFromAbsoluteUri("http://example.com/path?q=1")!!
        assertEquals("example.com", a.host)
        assertEquals(80, a.port)
        assertEquals("/path?q=1", a.pathAndQuery)

        val b = LanShareServer.hostPortFromAbsoluteUri("http://user:pw@host.tld:8080/")!!
        assertEquals("host.tld", b.host)
        assertEquals(8080, b.port)

        assertNull(LanShareServer.hostPortFromAbsoluteUri("ftp://host/x"))
        assertNull(LanShareServer.hostPortFromAbsoluteUri("no-scheme"))
    }

    @Test
    fun httpAuthOk_basic_matching() {
        val cred = LanCredential("djproxy", "s3cret")
        // Base64("djproxy:s3cret")
        val good = Base64Encode("djproxy:s3cret")
        assertTrue(LanShareServer.httpAuthOk(listOf("Proxy-Authorization: Basic $good"), cred))
        assertFalse(LanShareServer.httpAuthOk(listOf("Proxy-Authorization: Basic ${Base64Encode("x:y")}"), cred))
        assertFalse(LanShareServer.httpAuthOk(emptyList(), cred))
        // No credential required => always ok.
        assertTrue(LanShareServer.httpAuthOk(emptyList(), null))
    }

    @Test
    fun rebuildOriginRequest_drops_proxy_headers_and_ensures_host() {
        val target = LanShareServer.AbsoluteTarget("h.tld", 80, "/p")
        val out = LanShareServer.rebuildOriginRequest(
            "GET", target,
            listOf("Proxy-Authorization: Basic z", "Proxy-Connection: keep-alive", "User-Agent: t"),
        )
        assertTrue(out.startsWith("GET /p HTTP/1.1\r\n"))
        assertFalse(out.contains("Proxy-Authorization"))
        assertFalse(out.contains("Proxy-Connection"))
        assertTrue(out.contains("User-Agent: t"))
        assertTrue(out.contains("Host: h.tld"))
        assertTrue(out.endsWith("\r\n\r\n"))
    }

    // ---- end-to-end: HTTP CONNECT through a fake echoing dialer ----------------------------------

    @Test
    fun httpConnect_tunnels_bytes_through_dialer() {
        val echo = startEchoServer()
        val server = LanShareServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            requireAuth = false,
            credential = null,
            dialerProvider = { EchoDialer(echo) },
            preferredPort = 0,
        )
        closeables += AutoCloseable { server.stop() }
        val port = server.start()
        assertTrue(port > 0)

        Socket("127.0.0.1", port).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray())
            c.getOutputStream().flush()
            val status = readLine(c)
            assertTrue("status=$status", status.startsWith("HTTP/1.1 200"))
            // consume the rest of the reply head
            skipToBodyStart(c)
            c.getOutputStream().write("ping".toByteArray()); c.getOutputStream().flush()
            val buf = ByteArray(4)
            DataInputStream(c.getInputStream()).readFully(buf)
            assertEquals("ping", String(buf))
        }
    }

    // ---- end-to-end: SOCKS5 no-auth through a fake echoing dialer --------------------------------

    @Test
    fun socks5_noauth_tunnels_bytes_through_dialer() {
        val echo = startEchoServer()
        val server = LanShareServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            requireAuth = false,
            credential = null,
            dialerProvider = { EchoDialer(echo) },
            preferredPort = 0,
        )
        closeables += AutoCloseable { server.stop() }
        val port = server.start()

        Socket("127.0.0.1", port).use { c ->
            c.soTimeout = 5_000
            val out = c.getOutputStream()
            val ins = DataInputStream(c.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush() // greeting: 1 method = no-auth
            val method = ByteArray(2); ins.readFully(method)
            assertEquals(0x05, method[0].toInt() and 0xFF)
            assertEquals(0x00, method[1].toInt() and 0xFF)
            // CONNECT example.com:443 (domain atyp)
            val host = "example.com".toByteArray()
            val req = ByteArray(4 + 1 + host.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = host.size.toByte()
            System.arraycopy(host, 0, req, 5, host.size)
            req[5 + host.size] = (443 ushr 8).toByte()
            req[6 + host.size] = (443 and 0xFF).toByte()
            out.write(req); out.flush()
            val reply = ByteArray(10); ins.readFully(reply)
            assertEquals(0x00, reply[1].toInt() and 0xFF) // succeeded
            out.write("pong".toByteArray()); out.flush()
            val buf = ByteArray(4); ins.readFully(buf)
            assertEquals("pong", String(buf))
        }
    }

    @Test
    fun socks5_requireAuth_rejects_wrong_password() {
        val echo = startEchoServer()
        val server = LanShareServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            requireAuth = true,
            credential = LanCredential("djproxy", "right"),
            dialerProvider = { EchoDialer(echo) },
            preferredPort = 0,
        )
        closeables += AutoCloseable { server.stop() }
        val port = server.start()

        Socket("127.0.0.1", port).use { c ->
            c.soTimeout = 5_000
            val out = c.getOutputStream()
            val ins = DataInputStream(c.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush() // offer user/pass
            val method = ByteArray(2); ins.readFully(method)
            assertEquals(0x02, method[1].toInt() and 0xFF)
            // RFC1929 with wrong password
            val u = "djproxy".toByteArray(); val p = "wrong".toByteArray()
            val auth = ByteArray(3 + u.size + p.size)
            auth[0] = 0x01; auth[1] = u.size.toByte()
            System.arraycopy(u, 0, auth, 2, u.size)
            auth[2 + u.size] = p.size.toByte()
            System.arraycopy(p, 0, auth, 3 + u.size, p.size)
            out.write(auth); out.flush()
            val authReply = ByteArray(2); ins.readFully(authReply)
            assertEquals(0x01, authReply[1].toInt() and 0xFF) // failure status
        }
    }

    // ---- security regressions -------------------------------------------------------------------

    @Test
    fun boundAddress_isTheGivenLanAddress_neverAnyLocal() {
        val server = LanShareServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            requireAuth = true,
            credential = LanCredential("u", "p"),
            dialerProvider = { null },
            preferredPort = 0,
        )
        closeables += AutoCloseable { server.stop() }
        assertTrue(server.start() > 0)
        val bound = server.boundAddress
        assertTrue("bound=$bound", bound != null && !bound.isAnyLocalAddress)
        assertEquals("127.0.0.1", bound!!.hostAddress)
    }

    @Test
    fun isBlockedTarget_rejects_private_loopback_localhost_but_allows_public() {
        assertTrue(LanShareServer.isBlockedTarget("127.0.0.1"))
        assertTrue(LanShareServer.isBlockedTarget("localhost"))
        assertTrue(LanShareServer.isBlockedTarget("10.0.0.5"))
        assertTrue(LanShareServer.isBlockedTarget("192.168.1.1"))
        assertTrue(LanShareServer.isBlockedTarget("172.16.4.4"))
        assertTrue(LanShareServer.isBlockedTarget("169.254.1.1"))
        assertTrue(LanShareServer.isBlockedTarget("100.64.0.1")) // CGNAT
        assertTrue(LanShareServer.isBlockedTarget("0.0.0.0"))
        assertTrue(LanShareServer.isBlockedTarget("[::1]"))
        // Public IP literals and hostnames (resolved at the exit) are allowed.
        assertFalse(LanShareServer.isBlockedTarget("1.1.1.1"))
        assertFalse(LanShareServer.isBlockedTarget("8.8.8.8"))
        assertFalse(LanShareServer.isBlockedTarget("example.com"))
    }

    @Test
    fun constantTimeEquals_matches_semantics() {
        assertTrue(LanShareServer.constantTimeEquals("abc", "abc"))
        assertFalse(LanShareServer.constantTimeEquals("abc", "abd"))
        assertFalse(LanShareServer.constantTimeEquals("abc", "abcd"))
    }

    @Test
    fun stop_closes_inflight_tunnels() {
        val echo = startEchoServer()
        val server = LanShareServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            requireAuth = false,
            credential = null,
            dialerProvider = { EchoDialer(echo) },
            preferredPort = 0,
        )
        closeables += AutoCloseable { server.stop() }
        val port = server.start()
        val c = Socket("127.0.0.1", port)
        closeables += c
        c.soTimeout = 5_000
        c.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        c.getOutputStream().flush()
        assertTrue(readLine(c).startsWith("HTTP/1.1 200"))
        skipToBodyStart(c)
        // Tunnel is live. Stopping the share must tear it down (fail-closed), so the client sees EOF
        // instead of the pump forwarding indefinitely.
        server.stop()
        val ended = try { c.getInputStream().read() == -1 } catch (_: java.net.SocketException) { true }
        assertTrue("in-flight tunnel should be closed by stop()", ended)
    }

    @Test
    fun startLanShare_null_dialer_http_returns_502() {
        val server = LanShareServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            requireAuth = false,
            credential = null,
            dialerProvider = { null }, // no proxy configured
            preferredPort = 0,
        )
        closeables += AutoCloseable { server.stop() }
        val port = server.start()
        Socket("127.0.0.1", port).use { c ->
            c.soTimeout = 5_000
            c.getOutputStream().write("CONNECT h:443 HTTP/1.1\r\n\r\n".toByteArray()); c.getOutputStream().flush()
            val status = readLine(c)
            assertTrue("status=$status", status.startsWith("HTTP/1.1 502"))
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun startEchoServer(): ServerSocket {
        val ss = ServerSocket()
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        closeables += ss
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    runCatching {
                        val i = s.getInputStream(); val o = s.getOutputStream()
                        val b = ByteArray(4096)
                        while (true) {
                            val n = i.read(b); if (n == -1) break
                            o.write(b, 0, n); o.flush()
                        }
                    }
                    runCatching { s.close() }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss
    }

    /** A dialer that ignores host/port and connects to the local echo server. */
    private class EchoDialer(private val echo: ServerSocket) : UpstreamDialer {
        override suspend fun connect(host: String, port: Int): DialResult {
            val s = Socket("127.0.0.1", echo.localPort)
            s.tcpNoDelay = true
            return DialResult.Ok(s)
        }
    }

    private fun readLine(s: Socket): String {
        val sb = StringBuilder()
        val ins = s.getInputStream()
        while (true) {
            val b = ins.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** After the status line was consumed by readLine, drain remaining header lines to the blank line. */
    private fun skipToBodyStart(s: Socket) {
        val ins = s.getInputStream()
        var line = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b == -1) break
            if (b == '\n'.code) {
                if (line.isEmpty()) break
                line = StringBuilder()
            } else if (b != '\r'.code) {
                line.append(b.toChar())
            }
        }
    }

    /** Test-local base64 encoder (encode side), independent of the server's decoder. */
    private fun Base64Encode(s: String): String {
        val data = s.toByteArray(Charsets.UTF_8)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(alphabet[(n ushr 18) and 0x3F]); sb.append(alphabet[(n ushr 12) and 0x3F])
            sb.append(alphabet[(n ushr 6) and 0x3F]); sb.append(alphabet[n and 0x3F]); i += 3
        }
        when (data.size - i) {
            1 -> { val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(alphabet[(n ushr 18) and 0x3F]); sb.append(alphabet[(n ushr 12) and 0x3F]); sb.append("==") }
            2 -> { val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(alphabet[(n ushr 18) and 0x3F]); sb.append(alphabet[(n ushr 12) and 0x3F]); sb.append(alphabet[(n ushr 6) and 0x3F]); sb.append('=') }
        }
        return sb.toString()
    }
}
