package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [OvpnParser] — proxy-relevant directives only; honest rejection otherwise. */
class OvpnParserTest {

    @Test
    fun http_proxy_directive_maps_to_http_config() {
        val ovpn = """
            client
            dev tun
            proto tcp
            remote vpn.example.com 1194
            http-proxy 10.0.0.9 8080
            <ca>
            -----BEGIN CERTIFICATE-----
            </ca>
        """.trimIndent()
        val r = OvpnParser.parse(ovpn)
        assertTrue(r is ImportResult.Single)
        val c = (r as ImportResult.Single).config
        assertEquals(ProxyType.HTTP, c.type)
        assertEquals("10.0.0.9", c.host)
        assertEquals(8080, c.port)
    }

    @Test
    fun socks_proxy_directive_maps_to_socks5() {
        val ovpn = "client\nsocks-proxy 127.0.0.1 9050\n"
        val c = (OvpnParser.parse(ovpn) as ImportResult.Single).config
        assertEquals(ProxyType.SOCKS5, c.type)
        assertEquals("127.0.0.1", c.host)
        assertEquals(9050, c.port)
    }

    @Test
    fun no_proxy_directive_is_rejected_as_not_a_proxy() {
        val ovpn = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            cipher AES-256-GCM
            auth-user-pass
        """.trimIndent()
        val r = OvpnParser.parse(ovpn)
        assertEquals(ImportResult.Rejected(ImportError.OvpnNotAProxy), r)
    }

    @Test
    fun inline_comments_are_stripped() {
        val ovpn = "socks-proxy 8.8.8.8 1080 # my socks\n"
        val c = (OvpnParser.parse(ovpn) as ImportResult.Single).config
        assertEquals("8.8.8.8", c.host)
        assertEquals(1080, c.port)
    }

    @Test
    fun looksLikeOvpn_detects_vpn_skeleton() {
        assertTrue(OvpnParser.looksLikeOvpn("client\ndev tun\nremote a 1\n"))
        assertTrue(OvpnParser.looksLikeOvpn("http-proxy 1.2.3.4 8080\nverb 3\n"))
        assertFalse(OvpnParser.looksLikeOvpn("socks5://1.2.3.4:1080"))
        assertFalse(OvpnParser.looksLikeOvpn("1.2.3.4:1080:user:pass"))
    }
}
