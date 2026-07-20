package ai.darshj.djproxy.vpngate

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VpnGateCacheTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private val fullVpnOvpn = "client\ndev tun\nremote 1.2.3.4 1194\n"
    private val socksProxyOvpn = "client\nsocks-proxy 5.6.7.8 1080\nremote 1.2.3.4 1194\n"

    private fun server(
        host: String,
        ip: String = "8.8.8.8",
        country: String = "Japan",
        ovpn: String = fullVpnOvpn,
        dialable: Boolean = false,
    ): VpnGateServer {
        val configB64 = b64(ovpn)
        return VpnGateServer(
            hostName = host,
            ip = ip,
            countryLong = country,
            countryShort = "JP",
            ping = 15,
            score = 500_000L,
            speed = 12_345_678L,
            sessions = 10,
            uptimeMs = 3_600_000L,
            ovpn = ovpn,
            configB64 = configB64,
            directlyDialable = dialable,
            dialConfig = if (dialable) {
                ai.darshj.djproxy.core.ProxyConfig(ProxyType.SOCKS5, "5.6.7.8", 1080)
            } else {
                null
            },
        )
    }

    private class LongBox(var value: Long)

    private fun cache(now: Long): Pair<VpnGateCache, LongBox> {
        val clock = LongBox(now)
        return VpnGateCache(
            persistence = VpnGateCache.InMemoryPersistence(),
            clock = { clock.value },
        ) to clock
    }

    // ---- codec round-trip (re-derives ovpn + dialable from the stored base64) ---------------------

    @Test
    fun `codec round-trips servers and re-derives dialable from ovpn`() {
        val snap = VpnGateCache.Snapshot(
            servers = listOf(
                server("full-vpn", country = "Côte d'Ivoire, région"), // comma + unicode in country
                server("dialable", ip = "8.8.4.4", ovpn = socksProxyOvpn, dialable = true),
            ),
            fetchedAt = 1_700_000_000_000L,
        )
        val decoded = VpnGateCodec.decode(VpnGateCodec.encode(snap))
        assertNotNull(decoded)
        assertEquals(snap.fetchedAt, decoded!!.fetchedAt)
        assertEquals(2, decoded.servers.size)

        val full = decoded.servers[0]
        assertEquals("full-vpn", full.hostName)
        assertEquals("Côte d'Ivoire, région", full.countryLong)
        assertEquals(fullVpnOvpn, full.ovpn)
        assertFalse(full.directlyDialable)
        assertNull(full.dialConfig)

        val dial = decoded.servers[1]
        assertTrue(dial.directlyDialable)
        assertNotNull(dial.dialConfig)
        assertEquals("5.6.7.8", dial.dialConfig!!.host)
        assertEquals(1080, dial.dialConfig!!.port)
    }

    @Test
    fun `codec returns null on garbage and skips malformed records`() {
        assertNull(VpnGateCodec.decode("not-a-number-first-line"))
        // valid header, one malformed record (too few fields) → 0 entries but non-null snapshot.
        val decoded = VpnGateCodec.decode("123\nonlyonefield\n")
        assertNotNull(decoded)
        assertEquals(123L, decoded!!.fetchedAt)
        assertEquals(0, decoded.servers.size)
    }

    // ---- TTL fresh / stale -----------------------------------------------------------------------

    @Test
    fun `getFresh returns snapshot within TTL and null once expired`() {
        val (c, clock) = cache(now = 1_000_000L)
        c.put(listOf(server("h")), fetchedAt = 1_000_000L)

        clock.value = 1_000_000L + VpnGateCache.TTL_MS - 1
        assertNotNull(c.getFresh())

        clock.value = 1_000_000L + VpnGateCache.TTL_MS
        assertNotNull(c.getFresh())

        clock.value = 1_000_000L + VpnGateCache.TTL_MS + 1
        assertNull(c.getFresh())
        assertNotNull(c.getAny())
        assertEquals("h", c.getAny()!!.servers.single().hostName)
    }

    @Test
    fun `getFresh null before any put`() {
        val (c, _) = cache(now = 0L)
        assertNull(c.getFresh())
        assertNull(c.getAny())
    }

    // ---- persistence survives a fresh cache instance (process-death sim) -------------------------

    @Test
    fun `snapshot survives via persistence into a fresh cache instance`() {
        val persistence = VpnGateCache.InMemoryPersistence()
        val clock = LongBox(5_000L)
        val first = VpnGateCache(persistence, clock = { clock.value })
        first.put(listOf(server("survivor", ip = "9.9.9.9")), fetchedAt = 5_000L)

        clock.value = 5_000L + 1000
        val second = VpnGateCache(persistence, clock = { clock.value })
        val fresh = second.getFresh()
        assertNotNull(fresh)
        assertEquals("survivor", fresh!!.servers.single().hostName)
        assertEquals("9.9.9.9", fresh.servers.single().ip)
    }

    @Test
    fun `clear drops memory and persistence`() {
        val (c, _) = cache(now = 0L)
        c.put(listOf(server("h")), fetchedAt = 0L)
        assertNotNull(c.getAny())
        c.clear()
        assertNull(c.getAny())
    }
}
