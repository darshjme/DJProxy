package ai.darshj.djproxy.dns

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.6 DohResolverTest — DoH request/response framing + a full resolve over a loopback HTTP server. */
class DohResolverTest {

    // ---- pure framing --------------------------------------------------------------------------

    @Test
    fun buildRequest_isWellFormedDohPost() {
        val query = DnsMessage.buildQuery(0x1234, "example.com")
        val bytes = DohHttp.buildRequest(query, "cloudflare-dns.com")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("POST /dns-query HTTP/1.1\r\n"))
        assertTrue(text.contains("Host: cloudflare-dns.com\r\n"))
        assertTrue(text.contains("Content-Type: application/dns-message\r\n"))
        assertTrue(text.contains("Accept: application/dns-message\r\n"))
        assertTrue(text.contains("Content-Length: ${query.size}\r\n"))
        // The raw query bytes are appended verbatim after the blank line.
        val bodyStart = text.indexOf("\r\n\r\n") + 4
        assertArrayEquals(query, bytes.copyOfRange(bodyStart, bytes.size))
    }

    @Test
    fun parseResponse_readsContentLengthBody() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val resp = LoopbackHttpServer.dnsResponse(body)
        val parsed = DohHttp.parseResponse(resp.inputStream())
        assertArrayEquals(body, parsed)
    }

    @Test
    fun parseResponse_nullsOnNon200() {
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertNull(DohHttp.parseResponse(resp.inputStream()))
    }

    @Test
    fun parseResponse_readsToEofWhenNoContentLength() {
        val body = byteArrayOf(9, 8, 7)
        val resp = ("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
        val parsed = DohHttp.parseResponse(resp.inputStream())
        assertArrayEquals(body, parsed)
    }

    @Test
    fun parseStatusCode_parses() {
        assertEquals(200, DohHttp.parseStatusCode("HTTP/1.1 200 OK"))
        assertNull(DohHttp.parseStatusCode("garbage"))
    }

    @Test
    fun shouldUseSniApis_onlyOnApi24Plus() {
        // The SNIHostName / setServerNames / setEndpointIdentificationAlgorithm APIs are API 24 (N).
        // On 21-23 they throw NoSuchMethodError, so the guard must select the manual-verify path.
        assertFalse(DohResolver.shouldUseSniApis(21))
        assertFalse(DohResolver.shouldUseSniApis(22))
        assertFalse(DohResolver.shouldUseSniApis(23))
        assertTrue(DohResolver.shouldUseSniApis(24))
        assertTrue(DohResolver.shouldUseSniApis(34))
    }

    // ---- full resolve path over a loopback server (identity TLS wrap) --------------------------

    @Test
    fun resolve_returnsAnswerWithRewrittenId() = runBlocking {
        // A fake 16-byte DNS answer whose ID (0x0000) differs from the query's.
        val answerBody = ByteArray(16).also { it[0] = 0; it[1] = 0; it[8] = 0x2A }
        val server = LoopbackHttpServer(LoopbackHttpServer.dnsResponse(answerBody))
        server.start()
        server.use {
            val resolver = DohResolver(
                dialer = FakeLoopbackDialer(server.port),
                endpoints = listOf(DohEndpoint("1.1.1.1", "cloudflare-dns.com")),
                tlsWrap = { s, _ -> s }, // identity: plaintext loopback, no real TLS
            )
            val query = DnsMessage.buildQuery(0xBEEF, "example.com")
            val answer = resolver.resolve(query)
            assertTrue("resolve must return the server's dns-message", answer != null)
            // ID rewritten to match the query; the rest of the body preserved.
            assertEquals(query[0], answer!![0])
            assertEquals(query[1], answer[1])
            assertEquals(0x2A.toByte(), answer[8])
        }
    }

    @Test
    fun resolve_nullsWhenDialFails() = runBlocking {
        val resolver = DohResolver(
            dialer = FailingDialer(),
            endpoints = listOf(DohEndpoint("1.1.1.1", "cloudflare-dns.com")),
            tlsWrap = { s, _ -> s },
        )
        assertNull(resolver.resolve(DnsMessage.buildQuery(1, "x.com")))
    }
}
