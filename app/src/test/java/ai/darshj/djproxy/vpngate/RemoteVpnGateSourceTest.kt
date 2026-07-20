package ai.darshj.djproxy.vpngate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class RemoteVpnGateSourceTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private val fullVpnOvpn = "client\ndev tun\nremote 1.2.3.4 1194\n"

    private val HEADER =
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime," +
            "TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64"

    private fun csv(vararg rows: Triple<String, String, Long>): String = buildString {
        append("*vpn_servers\n").append(HEADER).append('\n')
        for ((host, ip, score) in rows) {
            append(
                listOf(
                    host, ip, score.toString(), "10", "12345678", "Japan", "JP",
                    "10", "3600000", "100", "123", "2weeks", "op", "msg", b64(fullVpnOvpn),
                ).joinToString(","),
            ).append('\n')
        }
        append("*\n")
    }

    /** Records fetched URLs; returns a mapped body or throws. */
    private class FakeFetcher(
        private val body: String? = null,
        private val failWith: Throwable? = null,
    ) : RemoteVpnGateSource.BodyFetcher {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String, maxBytes: Int): String {
            fetched += url
            failWith?.let { throw it }
            return body ?: throw IOException("no body")
        }
    }

    private fun freshCache() = VpnGateCache(
        persistence = VpnGateCache.InMemoryPersistence(),
        clock = { 1_000L },
    )

    // ---- happy path ------------------------------------------------------------------------------

    @Test
    fun `fetches parses and caches`() = runTest {
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L), Triple("h2", "8.8.4.4", 900L)))
        val src = RemoteVpnGateSource(freshCache(), fetcher = fetcher, clock = { 42L })
        val res = src.fetch(force = false)
        assertTrue(res is VpnGateResult.Ok)
        res as VpnGateResult.Ok
        assertFalse(res.fromCache)
        assertEquals(42L, res.fetchedAt)
        // Score desc → h2 (900) before h1 (500).
        assertEquals(listOf("h2", "h1"), res.servers.map { it.hostName })
    }

    @Test
    fun `second fetch within TTL is served from cache`() = runTest {
        val cache = freshCache()
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L)))
        val src = RemoteVpnGateSource(cache, fetcher = fetcher, clock = { 1_000L })

        val first = src.fetch(force = false) as VpnGateResult.Ok
        assertFalse(first.fromCache)
        assertEquals(1, fetcher.fetched.size)

        val second = src.fetch(force = false) as VpnGateResult.Ok
        assertTrue(second.fromCache)
        assertEquals("no network hop on a fresh-cache hit", 1, fetcher.fetched.size)
    }

    @Test
    fun `force bypasses a fresh cache and re-fetches`() = runTest {
        val cache = freshCache()
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L)))
        val src = RemoteVpnGateSource(cache, fetcher = fetcher, clock = { 1_000L })
        src.fetch(force = false)
        src.fetch(force = true)
        assertEquals(2, fetcher.fetched.size)
    }

    // ---- https-only enforcement ------------------------------------------------------------------

    @Test
    fun `non-https url is refused and never fetched`() = runTest {
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L)))
        val src = RemoteVpnGateSource(
            freshCache(),
            url = "http://www.vpngate.net/api/iphone/",
            fetcher = fetcher,
        )
        val res = src.fetch(force = true)
        assertTrue(res is VpnGateResult.Failed)
        assertTrue(fetcher.fetched.isEmpty())
    }

    // ---- byte cap MUST be large enough to hold the CSV -------------------------------------------

    @Test
    fun `MAX_BYTES is raised to 8 MiB so the base64-heavy CSV is not truncated`() {
        assertEquals(8 * 1024 * 1024, RemoteVpnGateSource.MAX_BYTES)
        assertTrue("512 KB would truncate the feed", RemoteVpnGateSource.MAX_BYTES > 512 * 1024)
    }

    @Test
    fun `oversized body is truncated at maxBytes before parsing`() = runTest {
        // A tiny maxBytes cuts the body inside the header → no complete data row survives.
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L)))
        val src = RemoteVpnGateSource(freshCache(), fetcher = fetcher, maxBytes = 20)
        val res = src.fetch(force = true)
        assertTrue(res is VpnGateResult.Failed)
    }

    // ---- offline degradation ---------------------------------------------------------------------

    @Test
    fun `fetch failure serves stale cache when present`() = runTest {
        val cache = freshCache()
        // Seed a stale snapshot directly (via a prior successful parse-and-put).
        cache.put(VpnGateCsvParser.parse(csv(Triple("old", "5.5.5.5", 1L))), fetchedAt = 1L)
        val failing = FakeFetcher(failWith = IOException("no network"))
        val src = RemoteVpnGateSource(cache, fetcher = failing, clock = { 999L })

        val res = src.fetch(force = true) as VpnGateResult.Ok
        assertTrue(res.fromCache)
        assertEquals("old", res.servers.single().hostName)
    }

    @Test
    fun `fetch failure with no cache returns Failed`() = runTest {
        val failing = FakeFetcher(failWith = IOException("no network"))
        val src = RemoteVpnGateSource(freshCache(), fetcher = failing)
        assertTrue(src.fetch(force = true) is VpnGateResult.Failed)
    }

    // ---- cache-only read (tab auto-populate, no network egress) ----------------------------------

    @Test
    fun `fetchCachedOnly returns null and never touches network on a cold cache`() = runTest {
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L)))
        val src = RemoteVpnGateSource(freshCache(), fetcher = fetcher)
        assertEquals(null, src.fetchCachedOnly())
        assertTrue("cache-only read must not open any socket", fetcher.fetched.isEmpty())
    }

    @Test
    fun `fetchCachedOnly serves the stored catalog without any fetch`() = runTest {
        val cache = freshCache()
        cache.put(VpnGateCsvParser.parse(csv(Triple("old", "5.5.5.5", 7L))), fetchedAt = 7L)
        val fetcher = FakeFetcher(csv(Triple("h1", "8.8.8.8", 500L)))
        val src = RemoteVpnGateSource(cache, fetcher = fetcher, clock = { 1_000L })

        val res = src.fetchCachedOnly()
        assertTrue(res is VpnGateResult.Ok)
        res as VpnGateResult.Ok
        assertTrue(res.fromCache)
        assertEquals(7L, res.fetchedAt)
        assertEquals("old", res.servers.single().hostName)
        assertTrue("cache-only read must not open any socket", fetcher.fetched.isEmpty())
    }

    @Test
    fun `empty catalog after screening returns Failed when no cache`() = runTest {
        // Every row is a private IP → screened out → empty parse → Failed.
        val fetcher = FakeFetcher(csv(Triple("lan", "192.168.0.1", 500L)))
        val src = RemoteVpnGateSource(freshCache(), fetcher = fetcher)
        assertTrue(src.fetch(force = true) is VpnGateResult.Failed)
    }
}
