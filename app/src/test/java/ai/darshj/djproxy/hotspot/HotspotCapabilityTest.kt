package ai.darshj.djproxy.hotspot

import ai.darshj.djproxy.vpn.seams.HotspotCapability as Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-detection is the honesty contract of the hotspot lane: we must NEVER advertise a transparent
 * (root) redirect on a device that cannot deliver it, and must NEVER advertise any share when no
 * client-reachable LAN/hotspot address exists. Both facts feed [HotspotCapability.classify]; the
 * interface preference that decides WHICH address a client dials is [HotspotCapability.scoreInterface].
 * Both are pure, so they are proven here without root, without real NICs, and off-device.
 */
class HotspotCapabilityTest {

    // ---- classify: the two facts -> the honest tier ------------------------------------------------

    @Test
    fun root_present_and_reachable_lan_offers_transparent_redirect() {
        assertEquals(
            Tier.ROOT_TRANSPARENT_AVAILABLE,
            HotspotCapability.classify(hasRootBinary = true, hasReachableLanAddress = true),
        )
    }

    @Test
    fun unrooted_with_reachable_lan_offers_lan_proxy_only() {
        assertEquals(
            Tier.LAN_PROXY_ONLY,
            HotspotCapability.classify(hasRootBinary = false, hasReachableLanAddress = true),
        )
    }

    @Test
    fun root_but_no_reachable_lan_is_unavailable_not_a_fake_transparent_claim() {
        // Root alone proxies nothing if there is no tethered/LAN client that can reach us. Honest = UNAVAILABLE.
        assertEquals(
            Tier.UNAVAILABLE,
            HotspotCapability.classify(hasRootBinary = true, hasReachableLanAddress = false),
        )
    }

    @Test
    fun no_root_and_no_lan_is_unavailable() {
        assertEquals(
            Tier.UNAVAILABLE,
            HotspotCapability.classify(hasRootBinary = false, hasReachableLanAddress = false),
        )
    }

    @Test
    fun reachable_lan_is_the_necessary_condition_for_any_share() {
        // For BOTH root values, no reachable LAN address must collapse to UNAVAILABLE.
        for (root in listOf(false, true)) {
            assertEquals(
                "root=$root with no LAN must be UNAVAILABLE",
                Tier.UNAVAILABLE,
                HotspotCapability.classify(hasRootBinary = root, hasReachableLanAddress = false),
            )
        }
    }

    // ---- scoreInterface: which address a client is told to dial ------------------------------------

    @Test
    fun soft_ap_interfaces_outrank_everything() {
        val ap = HotspotCapability.scoreInterface("ap0", siteLocal = true)
        val swlan = HotspotCapability.scoreInterface("swlan0", siteLocal = true)
        val wlan1 = HotspotCapability.scoreInterface("wlan1", siteLocal = true)
        val tether = HotspotCapability.scoreInterface("rndis0", siteLocal = true)
        val wlan0 = HotspotCapability.scoreInterface("wlan0", siteLocal = true)
        val other = HotspotCapability.scoreInterface("eth0", siteLocal = true)

        // ap / swlan / wlan1 (the device IS the router) beat USB/BT tethering, which beat a Wi-Fi
        // client address, which beats any other site-local, which beats a non-site-local address.
        assertTrue(ap > tether)
        assertTrue(swlan > tether)
        assertTrue(wlan1 > tether)
        assertTrue(tether > wlan0)
        assertTrue(wlan0 > other)
        assertTrue(other > HotspotCapability.scoreInterface("eth0", siteLocal = false))
    }

    @Test
    fun usb_and_bluetooth_tethering_rank_above_plain_wifi_client() {
        val rndis = HotspotCapability.scoreInterface("rndis0", siteLocal = true)
        val usb = HotspotCapability.scoreInterface("usb0", siteLocal = true)
        val btpan = HotspotCapability.scoreInterface("bt-pan", siteLocal = true)
        val wlanClient = HotspotCapability.scoreInterface("wlan0", siteLocal = true)
        assertTrue(rndis > wlanClient)
        assertTrue(usb > wlanClient)
        assertTrue(btpan > wlanClient)
    }

    @Test
    fun a_wifi_client_address_only_scores_when_site_local() {
        // wlan0 on a public (non-site-local) address must NOT get the elevated Wi-Fi-client score.
        val siteLocal = HotspotCapability.scoreInterface("wlan0", siteLocal = true)
        val publicAddr = HotspotCapability.scoreInterface("wlan0", siteLocal = false)
        assertTrue(siteLocal > publicAddr)
    }

    @Test
    fun a_generic_site_local_outranks_a_non_site_local_address() {
        val siteLocal = HotspotCapability.scoreInterface("eth0", siteLocal = true)
        val nonSite = HotspotCapability.scoreInterface("eth0", siteLocal = false)
        assertTrue(siteLocal > nonSite)
    }

    @Test
    fun scoring_is_case_and_prefix_stable_for_ap_family() {
        // The detector lowercases the name before scoring; ensure the ap* prefix rule is exercised
        // for representative OEM soft-AP names.
        assertEquals(
            HotspotCapability.scoreInterface("ap0", siteLocal = true),
            HotspotCapability.scoreInterface("ap1", siteLocal = true),
        )
        assertTrue(
            HotspotCapability.scoreInterface("softap0", siteLocal = true) >
                HotspotCapability.scoreInterface("wlan0", siteLocal = true),
        )
    }
}
