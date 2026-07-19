package ai.darshj.djproxy.hotspot

import ai.darshj.djproxy.vpn.seams.HotspotCapability
import ai.darshj.djproxy.vpn.seams.ShareState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the QR/one-tap payload builder and the honest capability detector
 * ([ai.darshj.djproxy.hotspot.HotspotCapability]).
 */
class QrPayloadTest {

    // ---- QR / client-setup payloads -------------------------------------------------------------

    @Test
    fun http_payload_with_and_without_auth() {
        assertEquals(
            "http://192.168.43.1:8787",
            QrPayload.forHttp("192.168.43.1", 8787, null),
        )
        assertEquals(
            "http://djproxy:secret@192.168.43.1:8787",
            QrPayload.forHttp("192.168.43.1", 8787, LanCredential("djproxy", "secret")),
        )
    }

    @Test
    fun socks5_payload_and_state_defaults_to_http() {
        assertEquals(
            "socks5://192.168.1.5:9000",
            QrPayload.forSocks5("192.168.1.5", 9000, null),
        )
        val state = ShareState.LanProxy("192.168.1.5", 9000, cred = "djproxy:pw")
        assertEquals("http://djproxy:pw@192.168.1.5:9000", QrPayload.forState(state))
    }

    @Test
    fun humanHint_includes_credentials_when_present() {
        val hint = QrPayload.humanHint("10.0.0.2", 8787, LanCredential("djproxy", "abc"))
        assertTrue(hint.contains("10.0.0.2"))
        assertTrue(hint.contains("8787"))
        assertTrue(hint.contains("djproxy"))
        assertTrue(hint.contains("abc"))
        val noAuth = QrPayload.humanHint("10.0.0.2", 8787, null)
        assertTrue(noAuth.contains("no username"))
    }

    // ---- LanCredential --------------------------------------------------------------------------

    @Test
    fun credential_random_is_fresh_and_parseable() {
        val a = LanCredential.random()
        val b = LanCredential.random()
        assertEquals("djproxy", a.user)
        assertNotEquals(a.pass, b.pass) // CSPRNG => practically never equal
        val round = LanCredential.parse(a.asUserInfo())
        assertEquals(a, round)
    }

    @Test
    fun credential_parse_rejects_malformed() {
        assertNull(LanCredential.parse(null))
        assertNull(LanCredential.parse(""))
        assertNull(LanCredential.parse("nopassword"))
        assertNull(LanCredential.parse(":onlypass"))
        assertNull(LanCredential.parse("onlyuser:"))
        assertEquals(LanCredential("u", "p"), LanCredential.parse("u:p"))
    }

    // ---- honest capability classification -------------------------------------------------------

    @Test
    fun classify_is_honest_per_tier() {
        assertEquals(
            HotspotCapability.ROOT_TRANSPARENT_AVAILABLE,
            ai.darshj.djproxy.hotspot.HotspotCapability.classify(hasRootBinary = true, hasReachableLanAddress = true),
        )
        assertEquals(
            HotspotCapability.LAN_PROXY_ONLY,
            ai.darshj.djproxy.hotspot.HotspotCapability.classify(hasRootBinary = false, hasReachableLanAddress = true),
        )
        // No reachable LAN address => neither share tier has a client; honestly UNAVAILABLE even with root.
        assertEquals(
            HotspotCapability.UNAVAILABLE,
            ai.darshj.djproxy.hotspot.HotspotCapability.classify(hasRootBinary = true, hasReachableLanAddress = false),
        )
        assertEquals(
            HotspotCapability.UNAVAILABLE,
            ai.darshj.djproxy.hotspot.HotspotCapability.classify(hasRootBinary = false, hasReachableLanAddress = false),
        )
    }

    @Test
    fun scoreInterface_prefers_ap_then_tether_then_wifi() {
        val ap = ai.darshj.djproxy.hotspot.HotspotCapability.scoreInterface("ap0", siteLocal = true)
        val usb = ai.darshj.djproxy.hotspot.HotspotCapability.scoreInterface("rndis0", siteLocal = true)
        val wifi = ai.darshj.djproxy.hotspot.HotspotCapability.scoreInterface("wlan0", siteLocal = true)
        val other = ai.darshj.djproxy.hotspot.HotspotCapability.scoreInterface("eth0", siteLocal = false)
        assertTrue(ap > usb)
        assertTrue(usb > wifi)
        assertTrue(wifi > other)
    }

    @Test
    fun hasRootBinary_is_false_on_test_jvm() {
        // The build/test host has no /system/bin/su etc., so detection must be honestly false.
        assertFalse(ai.darshj.djproxy.hotspot.HotspotCapability.hasRootBinary())
    }

    // ---- root redirect script builders (pure) ---------------------------------------------------

    @Test
    fun rootRedirect_apply_and_revert_scripts_are_symmetric() {
        val apply = RootRedirector.buildApplyScript("tun0", listOf("ap0", "rndis0"), RootRedirector.FWMARK, RootRedirector.TABLE)
        assertTrue(apply.contains("ip rule add fwmark ${RootRedirector.FWMARK} table ${RootRedirector.TABLE}"))
        assertTrue(apply.contains("ip route replace default dev tun0 table ${RootRedirector.TABLE}"))
        assertTrue(apply.contains("-i ap0 -j MARK --set-mark ${RootRedirector.FWMARK}"))
        // MASQUERADE now lives in a dedicated chain, jumped from POSTROUTING (surgical revert).
        assertTrue(apply.contains("iptables -t nat -A ${RootRedirector.NAT_CHAIN} -o tun0 -j MASQUERADE"))
        assertTrue(apply.contains("-A POSTROUTING -j ${RootRedirector.NAT_CHAIN}"))
        assertTrue(apply.contains("net.ipv4.ip_forward=1"))

        val revert = RootRedirector.buildRevertScript("tun0", listOf("ap0", "rndis0"), RootRedirector.FWMARK, RootRedirector.TABLE)
        assertTrue(revert.contains("ip rule del fwmark ${RootRedirector.FWMARK}"))
        assertTrue(revert.contains("iptables -t mangle -X DJPROXY_MARK"))
        // Revert only unlinks/drops our own nat chain; it must NOT delete a generic POSTROUTING rule.
        assertTrue(revert.contains("iptables -t nat -D POSTROUTING -j ${RootRedirector.NAT_CHAIN}"))
        assertTrue(revert.contains("iptables -t nat -X ${RootRedirector.NAT_CHAIN}"))
        assertFalse(revert.contains("-D POSTROUTING -o tun0 -j MASQUERADE"))
    }
}
