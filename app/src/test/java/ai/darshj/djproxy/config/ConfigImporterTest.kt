package ai.darshj.djproxy.config

import ai.darshj.djproxy.core.ProxyParser
import ai.darshj.djproxy.core.ProxyType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Unit tests for the [ConfigImporter] facade and the [ProxyParser] delegation seam. */
class ConfigImporterTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    // ---- importLocal: front-door shape sniffing ---------------------------------------------------

    @Test
    fun paste_colon_form_falls_back_to_core_parser() {
        val c = (ConfigImporter.importLocal("1.2.3.4:1080:user:pass") as ImportResult.Single).config
        assertEquals("1.2.3.4", c.host)
        assertEquals(1080, c.port)
        assertEquals("user", c.username)
        assertEquals("pass", c.password)
    }

    @Test
    fun single_ss_uri_is_parsed() {
        val uri = "ss://${Base64.getUrlEncoder().withoutPadding().encodeToString("none:pw".toByteArray())}@1.1.1.1:8388"
        val c = (ConfigImporter.importLocal(uri) as ImportResult.Single).config
        assertEquals(ProxyType.SOCKS5, c.type)
        assertEquals(8388, c.port)
    }

    @Test
    fun ovpn_text_is_routed_to_ovpn_parser() {
        val ovpn = "client\ndev tun\nsocks-proxy 127.0.0.1 9050\n"
        val c = (ConfigImporter.importLocal(ovpn) as ImportResult.Single).config
        assertEquals("127.0.0.1", c.host)
        assertEquals(9050, c.port)
    }

    @Test
    fun pasted_base64_config_list_becomes_many() {
        val blob = b64("socks5://1.1.1.1:1080\nsocks5://2.2.2.2:1080")
        val many = (ConfigImporter.importLocal(blob) as ImportResult.Many).configs
        assertEquals(2, many.size)
    }

    @Test
    fun subscription_url_on_sync_path_is_reported_not_faked() {
        val r = ConfigImporter.importLocal("https://sub.example.com/link/abcd")
        assertTrue(r is ImportResult.Rejected)
    }

    @Test
    fun bare_http_host_port_is_a_proxy_not_a_subscription() {
        val c = (ConfigImporter.importLocal("http://10.0.0.1:8080") as ImportResult.Single).config
        assertEquals(ProxyType.HTTP, c.type)
        assertEquals(8080, c.port)
    }

    @Test
    fun empty_is_rejected() {
        assertEquals(ImportResult.Rejected(ImportError.Empty), ConfigImporter.importLocal("   "))
    }

    // ---- import (suspend) full facade -------------------------------------------------------------

    @Test
    fun import_dispatches_single_uri() = runTest {
        val r = ConfigImporter.import("socks5://u:p@3.3.3.3:1080")
        assertEquals("3.3.3.3", (r as ImportResult.Single).config.host)
    }

    @Test
    fun deep_link_front_door() {
        val c = (ConfigImporter.fromDeepLink("djproxy://connect?host=4.4.4.4&port=1080") as ImportResult.Single).config
        assertEquals("4.4.4.4", c.host)
    }

    @Test
    fun clipboard_helper() = runTest {
        val r = ConfigImporter.fromClipboard("socks5://1.2.3.4:1080")
        assertEquals(1080, (r as ImportResult.Single).config.port)
    }

    // ---- ProxyParser delegation seam (core → config for schemes it can't speak) -------------------

    @Test
    fun proxyparser_delegates_ss_to_config_lane() {
        val uri = "ss://${Base64.getUrlEncoder().withoutPadding().encodeToString("none:pw".toByteArray())}@5.5.5.5:8388"
        val r = ProxyParser.parse(uri)
        assertTrue(r is ProxyParser.Result.Ok)
        assertEquals("5.5.5.5", (r as ProxyParser.Result.Ok).config.host)
    }

    @Test
    fun proxyparser_delegates_vmess_and_surfaces_named_error() {
        val r = ProxyParser.parse("vmess://${b64("{\"add\":\"1.1.1.1\",\"port\":\"443\"}")}")
        assertTrue(r is ProxyParser.Result.Err)
        assertTrue((r as ProxyParser.Result.Err).message.contains("vmess"))
    }

    @Test
    fun proxyparser_still_speaks_socks5_natively() {
        val r = ProxyParser.parse("socks5://1.2.3.4:1080")
        assertTrue(r is ProxyParser.Result.Ok)
    }
}
