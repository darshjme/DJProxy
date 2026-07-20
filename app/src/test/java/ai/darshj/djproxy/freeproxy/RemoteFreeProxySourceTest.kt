package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteFreeProxySourceTest {

    private val jetkaiSocks = RemoteFreeProxySource.Source(
        "https://raw.githubusercontent.com/jetkai/x/socks5.txt", ProxyType.SOCKS5, "jetkai · socks5",
    )
    private val proxiflyHttp = RemoteFreeProxySource.Source(
        "https://raw.githubusercontent.com/proxifly/x/http.txt", ProxyType.HTTP, "proxifly · http",
    )

    /** Records fetched URLs; returns a mapped body or throws for absent keys. */
    private class FakeFetcher(
        private val bodies: Map<String, String>,
        private val failWith: Throwable? = null,
    ) : RemoteFreeProxySource.BodyFetcher {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String, maxBytes: Int): String {
            fetched += url
            failWith?.let { throw it }
            return bodies[url] ?: throw IOException("404 $url")
        }
    }

    private fun freshCache() = FreeProxyCache(
        persistence = FreeProxyCache.InMemoryPersistence(),
        clock = { 1_000L },
    )

    // ---- happy path: fake fetcher -> parse + merge ------------------------------------------------

    @Test
    fun `fetches all sources parses and merges deduped`() = runTest {
        val fetcher = FakeFetcher(
            mapOf(
                jetkaiSocks.url to "8.8.8.8:1080\n1.1.1.1:1080\n127.0.0.1:1080", // loopback dropped
                proxiflyHttp.url to "http://8.8.4.4:8080\n8.8.8.8:8080",         // second: default HTTP
            ),
        )
        val src = RemoteFreeProxySource(
            cache = freshCache(),
            sources = listOf(jetkaiSocks, proxiflyHttp),
            fetcher = fetcher,
            clock = { 42L },
        )
        val res = src.fetch(force = false)
        assertTrue(res is FreeProxyResult.Ok)
        res as FreeProxyResult.Ok
        assertFalse(res.fromCache)
        assertEquals(42L, res.fetchedAt)
        // socks: 8.8.8.8, 1.1.1.1 ; http: 8.8.4.4, 8.8.8.8 (distinct scheme → distinct key)
        assertEquals(
            listOf(
                "free:socks5:8.8.8.8:1080",
                "free:socks5:1.1.1.1:1080",
                "free:http:8.8.4.4:8080",
                "free:http:8.8.8.8:8080",
            ),
            res.entries.map { it.key },
        )
    }

    @Test
    fun `second fetch within TTL is served from cache without re-fetching`() = runTest {
        val cache = freshCache()
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to "8.8.8.8:1080"))
        val src = RemoteFreeProxySource(cache, listOf(jetkaiSocks), fetcher, clock = { 1_000L })

        val first = src.fetch(force = false) as FreeProxyResult.Ok
        assertFalse(first.fromCache)
        assertEquals(1, fetcher.fetched.size)

        val second = src.fetch(force = false) as FreeProxyResult.Ok
        assertTrue(second.fromCache)
        assertEquals("no network hop on a fresh-cache hit", 1, fetcher.fetched.size)
    }

    @Test
    fun `force bypasses a fresh cache and re-fetches`() = runTest {
        val cache = freshCache()
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to "8.8.8.8:1080"))
        val src = RemoteFreeProxySource(cache, listOf(jetkaiSocks), fetcher, clock = { 1_000L })

        src.fetch(force = false)
        src.fetch(force = true)
        assertEquals(2, fetcher.fetched.size)
    }

    // ---- https-only enforcement ------------------------------------------------------------------

    @Test
    fun `non-https source is refused and never fetched`() = runTest {
        val httpSource = RemoteFreeProxySource.Source(
            "http://raw.githubusercontent.com/insecure/list.txt", ProxyType.SOCKS5, "insecure",
        )
        val fetcher = FakeFetcher(
            mapOf(
                jetkaiSocks.url to "8.8.8.8:1080",
                httpSource.url to "9.9.9.9:1080", // must NOT be reached
            ),
        )
        val src = RemoteFreeProxySource(
            cache = freshCache(),
            sources = listOf(httpSource, jetkaiSocks),
            fetcher = fetcher,
        )
        val res = src.fetch(force = true) as FreeProxyResult.Ok
        // Only the https source's entry is present.
        assertEquals(listOf("8.8.8.8"), res.entries.map { it.host })
        // The http URL was screened out before any network call.
        assertFalse(fetcher.fetched.contains(httpSource.url))
        assertTrue(fetcher.fetched.contains(jetkaiSocks.url))
    }

    @Test
    fun `malformed source url is refused`() = runTest {
        val bad = RemoteFreeProxySource.Source("::::not a url", ProxyType.SOCKS5, "bad")
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to "8.8.8.8:1080"))
        val src = RemoteFreeProxySource(freshCache(), listOf(bad, jetkaiSocks), fetcher)
        val res = src.fetch(force = true) as FreeProxyResult.Ok
        assertEquals(listOf("8.8.8.8"), res.entries.map { it.host })
        assertFalse(fetcher.fetched.contains(bad.url))
    }

    // ---- byte cap --------------------------------------------------------------------------------

    @Test
    fun `oversized body is truncated at maxBytes before parsing`() = runTest {
        // maxBytes = 20 cuts the body mid-second-line so only the first entry survives.
        val body = "8.8.8.8:1080\n1.1.1.1:1080"
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to body))
        val src = RemoteFreeProxySource(
            cache = freshCache(),
            sources = listOf(jetkaiSocks),
            fetcher = fetcher,
            maxBytes = 20,
        )
        val res = src.fetch(force = true) as FreeProxyResult.Ok
        assertEquals(listOf("8.8.8.8"), res.entries.map { it.host })
    }

    // ---- offline degradation ---------------------------------------------------------------------

    @Test
    fun `fetch failure serves stale cache when present`() = runTest {
        val cache = freshCache()
        // Seed a stale snapshot directly.
        cache.put(listOf(FreeProxyEntry(ProxyType.SOCKS5, "5.5.5.5", 1080, "old")), fetchedAt = 1L)
        val failing = FakeFetcher(emptyMap(), failWith = IOException("no network"))
        val src = RemoteFreeProxySource(cache, listOf(jetkaiSocks), failing, clock = { 999L })

        val res = src.fetch(force = true) as FreeProxyResult.Ok
        assertTrue(res.fromCache)
        assertEquals("5.5.5.5", res.entries.single().host)
    }

    @Test
    fun `fetch failure with no cache returns Failed`() = runTest {
        val failing = FakeFetcher(emptyMap(), failWith = IOException("no network"))
        val src = RemoteFreeProxySource(freshCache(), listOf(jetkaiSocks), failing)
        val res = src.fetch(force = true)
        assertTrue(res is FreeProxyResult.Failed)
    }

    // ---- cache-only read (Free-tab auto-populate, no network egress) -----------------------------

    @Test
    fun `fetchCachedOnly returns null and never touches network on a cold cache`() = runTest {
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to "8.8.8.8:1080"))
        val src = RemoteFreeProxySource(freshCache(), listOf(jetkaiSocks), fetcher)
        val res = src.fetchCachedOnly()
        assertEquals(null, res)
        assertTrue("cache-only read must not open any socket", fetcher.fetched.isEmpty())
    }

    @Test
    fun `fetchCachedOnly serves the stored list without any fetch`() = runTest {
        val cache = freshCache()
        cache.put(listOf(FreeProxyEntry(ProxyType.SOCKS5, "5.5.5.5", 1080, "old")), fetchedAt = 7L)
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to "8.8.8.8:1080"))
        val src = RemoteFreeProxySource(cache, listOf(jetkaiSocks), fetcher, clock = { 1_000L })

        val res = src.fetchCachedOnly()
        assertTrue(res is FreeProxyResult.Ok)
        res as FreeProxyResult.Ok
        assertTrue(res.fromCache)
        assertEquals(7L, res.fetchedAt)
        assertEquals("5.5.5.5", res.entries.single().host)
        assertTrue("cache-only read must not open any socket", fetcher.fetched.isEmpty())
    }

    @Test
    fun `all sources parse to nothing usable falls back to Failed when no cache`() = runTest {
        // Reachable but every line is screened out (loopback) → empty merge, no cache.
        val fetcher = FakeFetcher(mapOf(jetkaiSocks.url to "127.0.0.1:1080\n10.0.0.1:1080"))
        val src = RemoteFreeProxySource(freshCache(), listOf(jetkaiSocks), fetcher)
        val res = src.fetch(force = true)
        assertTrue(res is FreeProxyResult.Failed)
    }
}
