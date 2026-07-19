package ai.darshj.djproxy.hotspot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transparent-redirect scripts must be surgical: apply installs accept/masquerade rules ONLY
 * inside dedicated DJPROXY_* chains, and revert deletes ONLY those chains — it must never issue a
 * generic `-D FORWARD -o tun -j ACCEPT` / `-D POSTROUTING -o tun -j MASQUERADE` that could strip a
 * pre-existing platform rule DJProxy did not create.
 */
class RootRedirectorScriptTest {

    private val tun = "tun0"
    private val ifaces = listOf("ap0", "rndis0")

    @Test
    fun applyScript_routes_through_dedicated_chains() {
        val s = RootRedirector.buildApplyScript(tun, ifaces, RootRedirector.FWMARK, RootRedirector.TABLE)
        assertTrue(s.contains("iptables -N ${RootRedirector.FWD_CHAIN}"))
        assertTrue(s.contains("iptables -A ${RootRedirector.FWD_CHAIN} -o $tun -j ACCEPT"))
        assertTrue(s.contains("iptables -A ${RootRedirector.FWD_CHAIN} -i $tun -j ACCEPT"))
        assertTrue(s.contains("iptables -t nat -N ${RootRedirector.NAT_CHAIN}"))
        assertTrue(s.contains("iptables -t nat -A ${RootRedirector.NAT_CHAIN} -o $tun -j MASQUERADE"))
        // The jumps into the built-in chains reference our chain, not a raw accept/masquerade rule.
        assertTrue(s.contains("-I FORWARD 1 -j ${RootRedirector.FWD_CHAIN}"))
        assertTrue(s.contains("-A POSTROUTING -j ${RootRedirector.NAT_CHAIN}"))
    }

    @Test
    fun revertScript_deletes_only_our_chains_never_generic_builtin_rules() {
        val s = RootRedirector.buildRevertScript(tun, ifaces, RootRedirector.FWMARK, RootRedirector.TABLE)
        // Our chains get unlinked + flushed + dropped.
        assertTrue(s.contains("iptables -D FORWARD -j ${RootRedirector.FWD_CHAIN}"))
        assertTrue(s.contains("iptables -X ${RootRedirector.FWD_CHAIN}"))
        assertTrue(s.contains("iptables -t nat -D POSTROUTING -j ${RootRedirector.NAT_CHAIN}"))
        assertTrue(s.contains("iptables -t nat -X ${RootRedirector.NAT_CHAIN}"))
        // CRITICAL: it must NOT delete generic built-in-chain accept/masquerade rules it may not own.
        assertFalse(s.contains("-D FORWARD -o $tun -j ACCEPT"))
        assertFalse(s.contains("-D FORWARD -i $tun -j ACCEPT"))
        assertFalse(s.contains("-t nat -D POSTROUTING -o $tun -j MASQUERADE"))
    }
}
