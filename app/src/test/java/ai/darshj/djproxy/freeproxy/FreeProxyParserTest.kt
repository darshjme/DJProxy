package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeProxyParserTest {

    // ---- real-shaped payloads --------------------------------------------------------------------

    /** jetkai `proxies-socks5.txt` shape: bare `ip:port`, one per line, CRLF-ish, with blanks. */
    @Test
    fun `parses jetkai bare ip-port lines with default type`() {
        val body = """
            8.8.8.9:1080
            203.0.114.10:9050

            51.79.52.80:3080
        """.trimIndent()
        val out = FreeProxyParser.parse(body, ProxyType.SOCKS5, "jetkai · socks5")
        assertEquals(3, out.size)
        assertTrue(out.all { it.type == ProxyType.SOCKS5 })
        assertEquals("8.8.8.9", out[0].host)
        assertEquals(1080, out[0].port)
        assertEquals("free:socks5:8.8.8.9:1080", out[0].key)
        assertEquals("jetkai · socks5", out[0].sourceLabel)
    }

    /** proxifly `data.txt` shape: `socks5://ip:port` / `http://ip:port`. Scheme overrides default. */
    @Test
    fun `parses proxifly scheme-prefixed lines and honours the scheme`() {
        val body = """
            socks5://45.61.98.4:1080
            http://45.61.98.5:8080
        """.trimIndent()
        val out = FreeProxyParser.parse(body, ProxyType.SOCKS5, "proxifly")
        assertEquals(2, out.size)
        assertEquals(ProxyType.SOCKS5, out[0].type)
        assertEquals(ProxyType.HTTP, out[1].type)
        assertEquals(8080, out[1].port)
    }

    @Test
    fun `strips path query fragment and credentials`() {
        val e = FreeProxyParser.parseLine("http://user:pass@8.8.4.4:3128/path?x=1#frag", ProxyType.HTTP, "s")
        assertNotNull(e)
        assertEquals("8.8.4.4", e!!.host)
        assertEquals(3128, e.port)
        // Public entries carry no auth even if the line smuggled some.
        assertEquals("", e.toConfig().username)
        assertEquals("", e.toConfig().password)
    }

    @Test
    fun `tolerates host colon port colon user colon pass by taking first two fields`() {
        val e = FreeProxyParser.parseLine("8.8.4.4:1080:bob:secret", ProxyType.SOCKS5, "s")
        assertNotNull(e)
        assertEquals("8.8.4.4", e!!.host)
        assertEquals(1080, e.port)
    }

    // ---- malformed input -------------------------------------------------------------------------

    @Test
    fun `drops blanks comments and unsupported schemes`() {
        assertNull(FreeProxyParser.parseLine("", ProxyType.SOCKS5, "s"))
        assertNull(FreeProxyParser.parseLine("   ", ProxyType.SOCKS5, "s"))
        assertNull(FreeProxyParser.parseLine("# a comment", ProxyType.SOCKS5, "s"))
        assertNull(FreeProxyParser.parseLine("// note", ProxyType.SOCKS5, "s"))
        assertNull(FreeProxyParser.parseLine("socks4://8.8.8.8:1080", ProxyType.SOCKS5, "s"))
        assertNull(FreeProxyParser.parseLine("ss://8.8.8.8:1080", ProxyType.SOCKS5, "s"))
    }

    @Test
    fun `drops malformed host-port lines`() {
        assertNull(FreeProxyParser.parseLine("8.8.8.8", ProxyType.SOCKS5, "s"))          // no port
        assertNull(FreeProxyParser.parseLine("8.8.8.8:", ProxyType.SOCKS5, "s"))         // empty port
        assertNull(FreeProxyParser.parseLine("8.8.8.8:notaport", ProxyType.SOCKS5, "s")) // non-numeric
        assertNull(FreeProxyParser.parseLine("8.8.8.8:70000", ProxyType.SOCKS5, "s"))    // port range
        assertNull(FreeProxyParser.parseLine("8.8.8.8:0", ProxyType.SOCKS5, "s"))        // port 0
        assertNull(FreeProxyParser.parseLine("not-an-ip:1080", ProxyType.SOCKS5, "s"))   // not IPv4
        assertNull(FreeProxyParser.parseLine("[::1]:1080", ProxyType.SOCKS5, "s"))       // IPv6 bracket
        assertNull(FreeProxyParser.parseLine("8.8.8.8.8:1080", ProxyType.SOCKS5, "s"))   // 5 octets
        assertNull(FreeProxyParser.parseLine("999.1.1.1:1080", ProxyType.SOCKS5, "s"))   // octet > 255
    }

    // ---- SSRF screen -----------------------------------------------------------------------------

    @Test
    fun `SSRF screen drops loopback private link-local cgnat multicast reserved and unspecified`() {
        val blocked = listOf(
            "0.0.0.0", "0.10.20.30",            // unspecified / this-network 0/8
            "127.0.0.1", "127.9.9.9",           // loopback 127/8
            "10.0.0.1", "10.255.255.255",       // private 10/8
            "172.16.0.1", "172.31.255.255",     // private 172.16/12
            "192.168.0.1", "192.168.99.99",     // private 192.168/16
            "169.254.1.1",                      // link-local
            "100.64.0.1", "100.127.255.255",    // CGNAT
            "224.0.0.1", "239.1.2.3",           // multicast
            "240.0.0.1", "255.255.255.255",     // reserved / broadcast
        )
        for (h in blocked) {
            assertTrue("expected $h to be screened out", FreeProxyParser.isScreenedOut(h, 1080))
        }
    }

    @Test
    fun `SSRF screen allows ordinary public addresses`() {
        val allowed = listOf(
            "8.8.8.8", "1.1.1.1", "9.9.9.9",
            "203.0.114.5", "51.79.52.80",
            "172.15.0.1", "172.32.0.1",         // just outside 172.16/12
            "100.63.255.255", "100.128.0.1",    // just outside CGNAT 100.64/10
            "223.255.255.255", "192.167.0.1", "192.169.0.1",
        )
        for (h in allowed) {
            assertFalse("expected $h to be allowed", FreeProxyParser.isScreenedOut(h, 1080))
        }
    }

    @Test
    fun `parse drops screened rows inside a mixed body`() {
        val body = """
            8.8.8.8:1080
            127.0.0.1:1080
            192.168.1.1:8080
            1.1.1.1:3128
        """.trimIndent()
        val out = FreeProxyParser.parse(body, ProxyType.SOCKS5, "s")
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), out.map { it.host })
    }

    // ---- dedupe + cap ----------------------------------------------------------------------------

    @Test
    fun `mergeDedupeCap dedupes by key preserving source priority order`() {
        val a = FreeProxyParser.parse("8.8.8.8:1080\n1.1.1.1:1080", ProxyType.SOCKS5, "A")
        val b = FreeProxyParser.parse("8.8.8.8:1080\n9.9.9.9:1080", ProxyType.SOCKS5, "B")
        val merged = FreeProxyParser.mergeDedupeCap(listOf(a, b))
        assertEquals(listOf("8.8.8.8", "1.1.1.1", "9.9.9.9"), merged.map { it.host })
        // The winning duplicate keeps the earlier list's label.
        assertEquals("A", merged.first { it.host == "8.8.8.8" }.sourceLabel)
    }

    @Test
    fun `same host different scheme are distinct keys`() {
        val socks = FreeProxyParser.parseLine("socks5://8.8.8.8:1080", ProxyType.SOCKS5, "s")!!
        val http = FreeProxyParser.parseLine("http://8.8.8.8:1080", ProxyType.HTTP, "s")!!
        assertTrue(socks.key != http.key)
        val merged = FreeProxyParser.mergeDedupeCap(listOf(listOf(socks, http)))
        assertEquals(2, merged.size)
    }

    @Test
    fun `mergeDedupeCap caps at MAX_ENTRIES`() {
        // Build 300 distinct public entries across the 5.x/6.x space (all public, all pass screen).
        val lines = (0 until 300).joinToString("\n") { i ->
            "5.${i / 256}.${i % 256}.1:1080"
        }
        val list = FreeProxyParser.parse(lines, ProxyType.SOCKS5, "big")
        assertEquals(300, list.size)
        val merged = FreeProxyParser.mergeDedupeCap(listOf(list))
        assertEquals(FreeProxyParser.MAX_ENTRIES, merged.size)
    }
}
