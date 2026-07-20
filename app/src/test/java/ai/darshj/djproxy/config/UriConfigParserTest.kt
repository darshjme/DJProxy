package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Unit tests for [UriConfigParser] — every scheme it must map or honestly reject. */
class UriConfigParserTest {

    private fun single(raw: String): ImportResult.Single {
        val r = UriConfigParser.parse(raw)
        assertTrue("expected Single, got $r", r is ImportResult.Single)
        return r as ImportResult.Single
    }

    private fun rejected(raw: String): ImportError {
        val r = UriConfigParser.parse(raw)
        assertTrue("expected Rejected, got $r", r is ImportResult.Rejected)
        return (r as ImportResult.Rejected).error
    }

    private fun b64url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray())

    // ---- socks5 / http (delegated to the frozen core parser) --------------------------------------

    @Test
    fun socks5_with_auth() {
        val c = single("socks5://user:p%40ss@1.2.3.4:1080").config
        assertEquals(ProxyType.SOCKS5, c.type)
        assertEquals("1.2.3.4", c.host)
        assertEquals(1080, c.port)
        assertEquals("user", c.username)
    }

    @Test
    fun http_no_auth() {
        val c = single("http://proxy.example.com:8080").config
        assertEquals(ProxyType.HTTP, c.type)
        assertEquals("proxy.example.com", c.host)
        assertEquals(8080, c.port)
    }

    @Test
    fun socks5h_alias_maps_to_socks5() {
        val c = single("socks5h://10.0.0.1:9050").config
        assertEquals(ProxyType.SOCKS5, c.type)
        assertEquals(9050, c.port)
    }

    @Test
    fun plus_in_userinfo_is_kept_literal_not_turned_into_a_space() {
        // '+' is unreserved in RFC 3986 userinfo; URLDecoder would wrongly turn it into a space and
        // corrupt the credentials so the pre-flight auth fails on a perfectly valid link.
        val c = single("socks5://alice+corp:pa+ss@1.2.3.4:1080").config
        assertEquals("alice+corp", c.username)
        assertEquals("pa+ss", c.password)
    }

    @Test
    fun percent_escapes_still_decode_in_userinfo() {
        val c = single("socks5://user:p%40ss@1.2.3.4:1080").config
        assertEquals("user", c.username)
        assertEquals("p@ss", c.password)
    }

    @Test
    fun https_proxy_uri_is_rejected_not_dialed_in_cleartext() {
        // DJProxy has no TLS-to-proxy; an https proxy must be rejected, not silently mapped to plain
        // HTTP CONNECT (which would send Basic creds in the clear against a plaintext listener).
        val err = rejected("https://user:pass@proxy.example:443")
        assertTrue(err is ImportError.UnsupportedProtocol)
        assertEquals("https", (err as ImportError.UnsupportedProtocol).scheme)
    }

    // ---- ss:// SIP002 ------------------------------------------------------------------------------

    @Test
    fun ss_sip002_none_cipher_maps_to_socks5() {
        val uri = "ss://${b64url("none:hunter2")}@1.2.3.4:8388#My%20Node"
        val c = single(uri).config
        assertEquals(ProxyType.SOCKS5, c.type)
        assertEquals("1.2.3.4", c.host)
        assertEquals(8388, c.port)
    }

    @Test
    fun ss_legacy_fully_base64_none() {
        val uri = "ss://${b64("none:pw@5.6.7.8:443")}#legacy"
        val c = single(uri).config
        assertEquals("5.6.7.8", c.host)
        assertEquals(443, c.port)
    }

    @Test
    fun ss_aead_cipher_is_rejected_by_name() {
        val uri = "ss://${b64url("aes-256-gcm:secret")}@1.2.3.4:8388"
        val err = rejected(uri)
        assertTrue(err is ImportError.UnsupportedProtocol)
        err as ImportError.UnsupportedProtocol
        assertEquals("ss", err.scheme)
        assertTrue(err.detail.contains("aes-256-gcm"))
    }

    // ---- full-obfuscation protocols → rejected ----------------------------------------------------

    @Test
    fun vmess_rejected_with_endpoint() {
        val json = """{"v":"2","ps":"n","add":"9.9.9.9","port":"443","id":"uuid","net":"ws"}"""
        val err = rejected("vmess://${b64(json)}")
        assertTrue(err is ImportError.UnsupportedProtocol)
        err as ImportError.UnsupportedProtocol
        assertEquals("vmess", err.scheme)
        assertTrue(err.detail.contains("9.9.9.9"))
        assertTrue(err.detail.contains("443"))
    }

    @Test
    fun vless_rejected() {
        val err = rejected("vless://uuid@host.example:443?type=tcp#n") as ImportError.UnsupportedProtocol
        assertEquals("vless", err.scheme)
        assertTrue(err.detail.contains("host.example:443"))
    }

    @Test
    fun trojan_rejected() {
        val err = rejected("trojan://pass@1.1.1.1:443#t") as ImportError.UnsupportedProtocol
        assertEquals("trojan", err.scheme)
        assertTrue(err.detail.contains("1.1.1.1:443"))
    }

    @Test
    fun hysteria2_and_hy2_rejected() {
        val a = rejected("hysteria2://auth@2.2.2.2:8443#h") as ImportError.UnsupportedProtocol
        assertEquals("hysteria2", a.scheme)
        val b = rejected("hy2://auth@3.3.3.3:8443") as ImportError.UnsupportedProtocol
        assertEquals("hysteria2", b.scheme)
    }

    // ---- deep links -------------------------------------------------------------------------------

    @Test
    fun deeplink_with_embedded_uri() {
        val inner = "socks5://u:p@4.4.4.4:1080"
        val encoded = java.net.URLEncoder.encode(inner, "UTF-8")
        val c = single("djproxy://import?uri=$encoded").config
        assertEquals("4.4.4.4", c.host)
        assertEquals(1080, c.port)
        assertEquals("u", c.username)
    }

    @Test
    fun deeplink_with_fields() {
        val c = single("djproxy://connect?host=5.5.5.5&port=3128&type=http&user=a&pass=b").config
        assertEquals(ProxyType.HTTP, c.type)
        assertEquals("5.5.5.5", c.host)
        assertEquals(3128, c.port)
        assertEquals("a", c.username)
        assertEquals("b", c.password)
    }

    @Test
    fun deeplink_missing_target_rejected() {
        val err = rejected("djproxy://import?foo=bar")
        assertTrue(err is ImportError.MalformedUri)
    }

    // ---- IPv6 -------------------------------------------------------------------------------------

    @Test
    fun ipv6_socks5() {
        val c = single("socks5://[2001:db8::1]:1080").config
        assertEquals("2001:db8::1", c.host)
        assertEquals(1080, c.port)
    }

    // ---- malformed --------------------------------------------------------------------------------

    @Test
    fun no_scheme_rejected() {
        assertTrue(UriConfigParser.parse("1.2.3.4:1080") is ImportResult.Rejected)
    }

    @Test
    fun ss_missing_port_rejected() {
        val err = rejected("ss://${b64url("none:pw")}@1.2.3.4")
        assertTrue(err is ImportError.MalformedUri)
    }

    @Test
    fun empty_rejected() {
        assertTrue(UriConfigParser.parse("   ") is ImportResult.Rejected)
    }

    @Test
    fun port_out_of_range_rejected() {
        val err = rejected("socks5://1.2.3.4:70000")
        assertTrue(err is ImportError.MalformedUri)
    }
}
