package ai.darshj.djproxy.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocklist unit tests — exact hit, parent-suffix coverage, non-match safety, IP/malformed fail-open,
 * normalization, and the line parser (bare / hosts-redirect / commented shapes). Pure JVM, no Android.
 */
class BlocklistTest {

    private val list = Blocklist.of(
        "doubleclick.net",
        "google-analytics.com",
        "amazon-adsystem.com",
    )

    @Test
    fun `exact host is blocked`() {
        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("google-analytics.com"))
    }

    @Test
    fun `sub-domain is blocked by parent-suffix match`() {
        assertTrue(list.isBlocked("sub.doubleclick.net"))
        assertTrue(list.isBlocked("stats.g.doubleclick.net"))
        assertTrue(list.isBlocked("ssl.google-analytics.com"))
        assertTrue(list.isBlocked("c.amazon-adsystem.com"))
    }

    @Test
    fun `unrelated host is not blocked`() {
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("github.com"))
        // Must NOT catch a look-alike sibling that only shares a raw substring, not a label boundary.
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("mydoubleclick.net"))
    }

    @Test
    fun `bare TLD is never blocked even if a domain under it is listed`() {
        assertFalse(list.isBlocked("net"))
        assertFalse(list.isBlocked("com"))
    }

    @Test
    fun `matching is case-insensitive and ignores a trailing dot`() {
        assertTrue(list.isBlocked("DOUBLECLICK.NET"))
        assertTrue(list.isBlocked("Sub.DoubleClick.Net"))
        assertTrue(list.isBlocked("doubleclick.net."))
        assertTrue(list.isBlocked("ad.doubleclick.net."))
    }

    @Test
    fun `ip literals and empties are never blocked`() {
        assertFalse(list.isBlocked("8.8.8.8"))
        assertFalse(list.isBlocked("127.0.0.1"))
        assertFalse(list.isBlocked("::1"))
        assertFalse(list.isBlocked(""))
        assertFalse(list.isBlocked("   "))
    }

    @Test
    fun `empty blocklist blocks nothing`() {
        val empty = Blocklist.of()
        assertEquals(0, empty.size)
        assertFalse(empty.isBlocked("doubleclick.net"))
    }

    @Test
    fun `loads mixed hosts-file shapes and drops noise`() {
        val body = """
            # DJProxy blocklist test
            doubleclick.net
            0.0.0.0 googlesyndication.com
            127.0.0.1 adnxs.com   # programmatic exchange

            localhost
            0.0.0.0 0.0.0.0
            criteo.com
            not a domain line with spaces
        """.trimIndent()
        val loaded = Blocklist.load(body.byteInputStream())

        assertTrue(loaded.isBlocked("doubleclick.net"))
        assertTrue(loaded.isBlocked("ad.googlesyndication.com")) // suffix match on the redirect entry
        assertTrue(loaded.isBlocked("adnxs.com"))                // trailing comment stripped
        assertTrue(loaded.isBlocked("criteo.com"))
        // Noise dropped: localhost, the IP redirect target, and the free-text line.
        assertFalse(loaded.isBlocked("localhost"))
        assertEquals("only the 4 real domains survive", 4, loaded.size)
    }

    @Test
    fun `normalizeLine extracts the domain token`() {
        assertEquals("doubleclick.net", Blocklist.normalizeLine("doubleclick.net"))
        assertEquals("doubleclick.net", Blocklist.normalizeLine("0.0.0.0 doubleclick.net"))
        assertEquals("doubleclick.net", Blocklist.normalizeLine("127.0.0.1   doubleclick.net  # ads"))
        assertEquals("doubleclick.net", Blocklist.normalizeLine("DoubleClick.NET."))
        assertEquals(null, Blocklist.normalizeLine("# whole-line comment"))
        assertEquals(null, Blocklist.normalizeLine(""))
        assertEquals(null, Blocklist.normalizeLine("localhost"))
        assertEquals(null, Blocklist.normalizeLine("192.168.1.1"))
        assertEquals(null, Blocklist.normalizeLine("no-dot-here"))
    }
}
