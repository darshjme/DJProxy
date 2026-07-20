package ai.darshj.djproxy.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Unit tests for [SubscriptionFetcher] — body parsing + the injectable fetch seam. */
class SubscriptionFetcherTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun parses_base64_newline_list() {
        val list = "socks5://1.1.1.1:1080#Tokyo\nhttp://2.2.2.2:8080#Paris"
        val r = SubscriptionFetcher.parseSubscriptionBody(b64(list))
        assertTrue(r is ImportResult.Many)
        val many = (r as ImportResult.Many).configs
        assertEquals(2, many.size)
        assertEquals("Tokyo", many[0].name)
        assertEquals("1.1.1.1", many[0].config.host)
        assertEquals("Paris", many[1].name)
    }

    @Test
    fun parses_plain_newline_list_without_base64() {
        val list = "socks5://9.9.9.9:1080\n# a comment\n\nhttp://8.8.8.8:3128"
        val many = (SubscriptionFetcher.parseSubscriptionBody(list) as ImportResult.Many).configs
        assertEquals(2, many.size)
    }

    @Test
    fun unsupported_lines_are_dropped_not_fatal() {
        val list = "vmess://YWJj\nsocks5://7.7.7.7:1080\ntrojan://p@6.6.6.6:443"
        val many = (SubscriptionFetcher.parseSubscriptionBody(b64(list)) as ImportResult.Many).configs
        assertEquals(1, many.size)
        assertEquals("7.7.7.7", many[0].config.host)
    }

    @Test
    fun parsed_entry_count_is_capped() {
        // A hostile body of tens of thousands of short valid URIs must not produce an unbounded
        // pick-list (OOM/jank). parseSubscriptionBody caps the entries even though the byte guard passes.
        val sb = StringBuilder()
        repeat(1500) { sb.append("socks5://1.1.1.1:1080\n") }
        val many = (SubscriptionFetcher.parseSubscriptionBody(sb.toString()) as ImportResult.Many).configs
        assertEquals(1000, many.size)
    }

    @Test
    fun empty_subscription_is_rejected() {
        val r = SubscriptionFetcher.parseSubscriptionBody(b64("vmess://YWJj\ntrojan://p@1.1.1.1:443"))
        assertEquals(ImportResult.Rejected(ImportError.EmptySubscription), r)
    }

    @Test
    fun fetch_maps_network_failure_to_unreachable() = runTest {
        val r = SubscriptionFetcher.fetch("https://sub.example/x") {
            throw java.io.IOException("boom")
        }
        assertTrue(r is ImportResult.Rejected)
        assertTrue((r as ImportResult.Rejected).error is ImportError.SubscriptionUnreachable)
    }

    @Test
    fun fetch_success_returns_many() = runTest {
        val body = b64("socks5://1.2.3.4:1080#Node")
        val r = SubscriptionFetcher.fetch("https://sub.example/x") { body }
        val many = (r as ImportResult.Many).configs
        assertEquals(1, many.size)
        assertEquals("Node", many[0].name)
    }
}
