package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeProxyCacheTest {

    private fun entry(host: String, label: String = "jetkai · socks5") =
        FreeProxyEntry(ProxyType.SOCKS5, host, 1080, label)

    private fun cache(now: Long): Pair<FreeProxyCache, LongBox> {
        val clock = LongBox(now)
        return FreeProxyCache(
            persistence = FreeProxyCache.InMemoryPersistence(),
            clock = { clock.value },
        ) to clock
    }

    private class LongBox(var value: Long)

    // ---- codec round-trip ------------------------------------------------------------------------

    @Test
    fun `codec round-trips entries incl labels with separators and unicode`() {
        val snap = FreeProxyCache.Snapshot(
            entries = listOf(
                entry("8.8.8.8", "jetkai · socks5"),
                FreeProxyEntry(ProxyType.HTTP, "1.1.1.1", 8080, "proxifly · http \t weird"),
            ),
            fetchedAt = 1_700_000_000_000L,
        )
        val decoded = FreeProxyCodec.decode(FreeProxyCodec.encode(snap))
        assertNotNull(decoded)
        assertEquals(snap.fetchedAt, decoded!!.fetchedAt)
        assertEquals(snap.entries, decoded.entries)
    }

    @Test
    fun `codec returns null on garbage and skips malformed records`() {
        assertNull(FreeProxyCodec.decode("not-a-number-first-line"))
        // valid header, one good record, one malformed record (wrong field count) → 1 entry
        val blob = "123\nSOCKS58.8.8.81080label\nBROKENLINE\n"
        val decoded = FreeProxyCodec.decode(blob)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.entries.size)
        assertEquals(123L, decoded.fetchedAt)
    }

    // ---- TTL fresh / stale -----------------------------------------------------------------------

    @Test
    fun `getFresh returns snapshot within TTL and null once expired`() {
        val (c, clock) = cache(now = 1_000_000L)
        c.put(listOf(entry("8.8.8.8")), fetchedAt = 1_000_000L)

        // within TTL
        clock.value = 1_000_000L + FreeProxyCache.TTL_MS - 1
        assertNotNull(c.getFresh())

        // exactly at TTL boundary is still fresh
        clock.value = 1_000_000L + FreeProxyCache.TTL_MS
        assertNotNull(c.getFresh())

        // past TTL → stale, getFresh null but getAny still serves it
        clock.value = 1_000_000L + FreeProxyCache.TTL_MS + 1
        assertNull(c.getFresh())
        assertNotNull(c.getAny())
        assertEquals("8.8.8.8", c.getAny()!!.entries.single().host)
    }

    @Test
    fun `getFresh null before any put`() {
        val (c, _) = cache(now = 0L)
        assertNull(c.getFresh())
        assertNull(c.getAny())
    }

    // ---- persistence survives a new cache instance (process-death sim) ---------------------------

    @Test
    fun `snapshot survives via persistence into a fresh cache instance`() {
        val persistence = FreeProxyCache.InMemoryPersistence()
        val clock = LongBox(5_000L)
        val first = FreeProxyCache(persistence, clock = { clock.value })
        first.put(listOf(entry("9.9.9.9")), fetchedAt = 5_000L)

        // New instance backed by the same persistence blob, still within TTL.
        clock.value = 5_000L + 1000
        val second = FreeProxyCache(persistence, clock = { clock.value })
        val fresh = second.getFresh()
        assertNotNull(fresh)
        assertEquals("9.9.9.9", fresh!!.entries.single().host)
    }

    @Test
    fun `clear drops memory and persistence`() {
        val (c, _) = cache(now = 0L)
        c.put(listOf(entry("8.8.8.8")), fetchedAt = 0L)
        assertNotNull(c.getAny())
        c.clear()
        assertNull(c.getAny())
    }
}
