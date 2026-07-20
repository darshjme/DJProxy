package ai.darshj.djproxy.vpngate

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VpnGateCsvParserTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** A full-VPN OpenVPN profile (no proxy directive) → OpenVPN-only, not directly dialable. */
    private val fullVpnOvpn = """
        client
        dev tun
        proto udp
        remote 1.2.3.4 1194
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIB
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent()

    /** A profile that embeds a socks-proxy directive → DJProxy CAN dial it → directlyDialable. */
    private val socksProxyOvpn = """
        client
        dev tun
        socks-proxy 5.6.7.8 1080
        remote 1.2.3.4 1194
    """.trimIndent()

    private val HEADER =
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime," +
            "TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64"

    private fun row(
        host: String,
        ip: String,
        score: Long,
        ping: Int,
        country: String = "Japan",
        countryShort: String = "JP",
        message: String = "welcome",
        ovpnB64: String,
    ): String = listOf(
        host, ip, score.toString(), ping.toString(), "12345678", country, countryShort,
        "10", "3600000", "100", "123456", "2weeks", "Daiyuu Nobori", message, ovpnB64,
    ).joinToString(",")

    // ---- header / banner skipping + column mapping -----------------------------------------------

    @Test
    fun `skips banner and trailing star and maps columns by header`() {
        val csv = buildString {
            append("*vpn_servers\n")
            append(HEADER).append('\n')
            append(row("public-vpn-1", "219.100.37.10", 500_000, 15, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append("*\n")
        }
        val servers = VpnGateCsvParser.parse(csv)
        assertEquals(1, servers.size)
        val s = servers.single()
        assertEquals("public-vpn-1", s.hostName)
        assertEquals("219.100.37.10", s.ip)
        assertEquals(500_000L, s.score)
        assertEquals(15, s.ping)
        assertEquals("Japan", s.countryLong)
        assertEquals("JP", s.countryShort)
        assertEquals(10, s.sessions)
        assertEquals(3_600_000L, s.uptimeMs)
        // Full VPN profile → OpenVPN-only.
        assertFalse(s.directlyDialable)
        assertNull(s.dialConfig)
        assertTrue(s.ovpn.contains("remote 1.2.3.4 1194"))
    }

    // ---- directly-dialable detection via the existing OvpnParser ---------------------------------

    @Test
    fun `row whose ovpn has a socks-proxy directive is flagged directly dialable`() {
        val csv = "$HEADER\n" +
            row("dialable-1", "8.8.8.8", 400_000, 20, ovpnB64 = b64(socksProxyOvpn)) + "\n"
        val s = VpnGateCsvParser.parse(csv).single()
        assertTrue(s.directlyDialable)
        assertNotNull(s.dialConfig)
        assertEquals(ProxyType.SOCKS5, s.dialConfig!!.type)
        assertEquals("5.6.7.8", s.dialConfig!!.host)
        assertEquals(1080, s.dialConfig!!.port)
    }

    // ---- SSRF screen: private / non-public IPs dropped -------------------------------------------

    @Test
    fun `private and malformed ips are screened out`() {
        val csv = buildString {
            append(HEADER).append('\n')
            append(row("lan", "192.168.1.1", 900_000, 5, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append(row("loop", "127.0.0.1", 900_000, 5, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append(row("cgnat", "100.64.0.1", 900_000, 5, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append(row("junk", "not-an-ip", 900_000, 5, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append(row("ok", "8.8.4.4", 100_000, 5, ovpnB64 = b64(fullVpnOvpn))).append('\n')
        }
        val servers = VpnGateCsvParser.parse(csv)
        assertEquals(listOf("8.8.4.4"), servers.map { it.ip })
    }

    // ---- comma-in-Message robustness (base64 is always the LAST field) ---------------------------

    @Test
    fun `message containing commas does not corrupt the base64 profile column`() {
        val csv = "$HEADER\n" +
            row(
                "msg-commas", "203.0.113.9", 300_000, 30,
                message = "hello, world, thanks for using VPN Gate",
                ovpnB64 = b64(socksProxyOvpn),
            ) + "\n"
        val s = VpnGateCsvParser.parse(csv).single()
        assertEquals("203.0.113.9", s.ip)
        // The base64 (final field) survived the extra commas → profile decoded → dialable detected.
        assertTrue(s.directlyDialable)
        assertEquals("5.6.7.8", s.dialConfig!!.host)
    }

    // ---- sort: score desc, then ping asc ---------------------------------------------------------

    @Test
    fun `sorted by score desc then ping asc`() {
        val csv = buildString {
            append(HEADER).append('\n')
            append(row("low-score", "8.8.8.8", 100_000, 10, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append(row("high-score", "8.8.4.4", 900_000, 50, ovpnB64 = b64(fullVpnOvpn))).append('\n')
            append(row("mid-fast", "1.1.1.1", 500_000, 5, ovpnB64 = b64(fullVpnOvpn))).append('\n')
        }
        val servers = VpnGateCsvParser.parse(csv)
        assertEquals(listOf("high-score", "mid-fast", "low-score"), servers.map { it.hostName })
    }

    // ---- cap at MAX_SERVERS ----------------------------------------------------------------------

    @Test
    fun `caps at MAX_SERVERS`() {
        val csv = buildString {
            append(HEADER).append('\n')
            // 250 distinct public IPs (11.x.x.x is public) → capped to 200.
            var n = 0
            for (a in 0..3) for (b in 0..255) {
                if (n >= 250) break
                append(row("h$n", "11.$a.$b.1", (250 - n).toLong(), 10, ovpnB64 = b64(fullVpnOvpn))).append('\n')
                n++
            }
        }
        val servers = VpnGateCsvParser.parse(csv)
        assertEquals(VpnGateCsvParser.MAX_SERVERS, servers.size)
        // Highest score kept first (score = 250 - n, so the earliest rows win).
        assertEquals("h0", servers.first().hostName)
    }

    // ---- degenerate inputs -----------------------------------------------------------------------

    @Test
    fun `no header yields empty list`() {
        assertTrue(VpnGateCsvParser.parse("*vpn_servers\nsome,garbage,line\n*").isEmpty())
    }

    @Test
    fun `row with blank base64 is dropped`() {
        val csv = "$HEADER\n" +
            listOf("nohash", "8.8.8.8", "100", "10", "1", "Japan", "JP", "1", "1", "1", "1", "x", "op", "msg", "")
                .joinToString(",") + "\n"
        assertTrue(VpnGateCsvParser.parse(csv).isEmpty())
    }

    @Test
    fun `flag emoji derives from country short`() {
        val csv = "$HEADER\n" +
            row("jp", "8.8.8.8", 100, 10, country = "Japan", countryShort = "JP", ovpnB64 = b64(fullVpnOvpn)) + "\n"
        val s = VpnGateCsvParser.parse(csv).single()
        assertEquals("🇯🇵", s.flagEmoji) // 🇯🇵
    }
}
