package ai.darshj.djproxy.hotspot

import java.io.File
import java.net.NetworkInterface

/**
 * The root-only transparent redirect (§3 tier: rooted). It pulls TETHERED traffic — packets forwarded
 * from soft-AP / USB / Bluetooth clients — into DJProxy's tun, so a device connected to the phone's
 * hotspot is proxied WITHOUT having to configure any proxy on the client. This genuinely cannot be
 * done on stock unrooted Android (the VpnService only captures THIS device's apps, never forwarded
 * traffic), so it lives behind a real root check and honest failure reporting.
 *
 * Mechanism (policy routing): mark packets arriving on tether interfaces, add an `ip rule` that sends
 * marked packets to a dedicated table whose default route is the tun device, allow the FORWARD flows,
 * and MASQUERADE out the tun. All commands run through a single `su -c` shell; a denied grant or a
 * missing tun is surfaced as a typed failure rather than a fake success.
 *
 * The exact command script is produced by pure builders ([buildApplyScript]/[buildRevertScript]) so
 * the generated iptables/ip rules are unit-testable without root.
 */
class RootRedirector {

    @Volatile private var active = false
    @Volatile private var appliedTun: String? = null

    val isActive: Boolean get() = active

    /**
     * Applies the redirect. Returns [Result.Ok] with the tun iface used, or [Result.Fail] with an
     * honest reason (root denied / no tun / command error). Never throws.
     */
    fun apply(): Result {
        if (active) return Result.Ok(appliedTun ?: "")
        if (!HotspotCapability.hasRootBinary()) return Result.Fail("No su binary present — device is not rooted")
        val tun = detectTunInterface()
            ?: return Result.Fail("No tun interface found — connect DJProxy (the VPN) first")
        if (!HotspotCapability.confirmRoot()) return Result.Fail("Root access was denied (su returned non-zero)")

        val script = buildApplyScript(tun, TETHER_IFACES, FWMARK, TABLE)
        val res = runSu(script)
        return if (res.exit == 0) {
            active = true
            appliedTun = tun
            Result.Ok(tun)
        } else {
            // Best-effort cleanup of any partially-applied rules.
            runSu(buildRevertScript(tun, TETHER_IFACES, FWMARK, TABLE))
            Result.Fail("Failed to apply redirect: ${res.output.trim().take(300).ifEmpty { "su error" }}")
        }
    }

    /** Reverts all rules this class installs. Idempotent; safe to call when not active. */
    fun revert() {
        val tun = appliedTun ?: detectTunInterface() ?: DEFAULT_TUN
        runSu(buildRevertScript(tun, TETHER_IFACES, FWMARK, TABLE))
        active = false
        appliedTun = null
    }

    /**
     * One-shot startup reconciliation: if a prior session crashed / was force-stopped while a root
     * redirect was live, the DJPROXY_* chains, the fwmark ip rule, table 1998 and the stashed
     * ip_forward would linger. This runs [buildRevertScript] once (idempotent — a no-op when nothing is
     * installed) so stale kernel state from a dead session is cleaned up on next launch. Only touches
     * anything when a su binary is present; never throws; runs off the caller's thread.
     */
    fun reconcileStaleRules() {
        if (active) return
        if (!HotspotCapability.hasRootBinary()) return
        Thread {
            runCatching {
                val tun = detectTunInterface() ?: DEFAULT_TUN
                runSu(buildRevertScript(tun, TETHER_IFACES, FWMARK, TABLE))
            }
        }.apply { isDaemon = true; start() }
    }

    sealed interface Result {
        data class Ok(val tun: String) : Result
        data class Fail(val reason: String) : Result
    }

    private data class SuOutput(val exit: Int, val output: String)

    private fun runSu(script: String): SuOutput = try {
        val proc = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        // Drain stdout on a daemon thread so a chatty command can't dead-lock the pipe, and bound the
        // wait: if the su manager stalls (grant revoked between confirmRoot() and here, or a wedged
        // busybox), we must not hang apply()/revert() forever — treat a timeout as a failure.
        val sb = StringBuilder()
        val drain = Thread {
            runCatching { proc.inputStream.bufferedReader().use { sb.append(it.readText()) } }
        }.apply { isDaemon = true; start() }
        val finished = waitForBounded(proc, SU_TIMEOUT_MS)
        if (!finished) {
            runCatching { proc.destroy() }
            drain.join(500)
            SuOutput(-1, "su timed out after ${SU_TIMEOUT_MS}ms")
        } else {
            drain.join(500)
            SuOutput(proc.exitValue(), sb.toString())
        }
    } catch (t: Throwable) {
        SuOutput(-1, t.message ?: "su exec failed")
    }

    /** Polls [Process.exitValue] until the process ends or [timeoutMs] elapses (Process.waitFor(long)
     *  is API 26+; DJProxy is minSdk 21). @return true if the process finished within the deadline. */
    private fun waitForBounded(proc: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    /** Finds an up tun interface (name `tun\d+`) — the VpnService's device, present only when connected. */
    private fun detectTunInterface(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
        if (ifaces != null) {
            val match = ifaces.firstOrNull { nif ->
                val name = nif.name.orEmpty()
                val up = runCatching { nif.isUp }.getOrDefault(false)
                up && TUN_REGEX.matches(name)
            }
            if (match != null) return match.name
        }
        // Fallback: /sys/class/net enumeration (works even when NetworkInterface hides the tun).
        return runCatching {
            File("/sys/class/net").listFiles()?.map { it.name }?.firstOrNull { TUN_REGEX.matches(it) }
        }.getOrNull()
    }

    companion object {
        const val FWMARK = "0x1de"
        const val TABLE = 1998
        const val DEFAULT_TUN = "tun0"
        const val FWD_CHAIN = "DJPROXY_FWD"
        const val NAT_CHAIN = "DJPROXY_NAT"
        const val FWD6_CHAIN = "DJPROXY_FWD6"
        private const val SU_TIMEOUT_MS = 8_000L
        /** Where apply() stashes the pre-existing ip_forward value so revert() can restore it. */
        private const val IPFWD_STATE = "/data/local/tmp/djproxy_ipfwd"
        private val TETHER_IFACES = listOf("ap0", "wlan1", "swlan0", "rndis0", "usb0", "bt-pan")
        private val TUN_REGEX = Regex("^tun\\d+$")

        /**
         * Builds the shell script that installs the transparent redirect. Pure — unit-tested. The
         * `2>/dev/null || true` guards make re-application idempotent (adding an existing rule is a
         * no-op error we ignore).
         */
        fun buildApplyScript(tun: String, tetherIfaces: List<String>, fwmark: String, table: Int): String {
            val cmds = mutableListOf<String>()
            // set +e keeps benign idempotent probes (the `-C ... || -A ...` guards) from aborting; every
            // LOAD-BEARING command instead carries an explicit `|| exit 1`, so a mid-script failure makes
            // the whole `su -c` exit non-zero and apply() reports Fail + reverts — never a silent partial
            // redirect that reports Ok while tethered packets leak straight out the real upstream.
            cmds += "set +e"
            // 1. Enable IPv4 forwarding — but first stash the pre-existing value so revert can restore it
            //    (do not leave ip_forward globally enabled after the share is stopped).
            cmds += "cat /proc/sys/net/ipv4/ip_forward > $IPFWD_STATE 2>/dev/null || true"
            cmds += "(sysctl -w net.ipv4.ip_forward=1 || echo 1 > /proc/sys/net/ipv4/ip_forward) || exit 1"
            // 2. A dedicated mangle chain that marks packets forwarded from tether interfaces.
            cmds += "(iptables -t mangle -N DJPROXY_MARK 2>/dev/null || iptables -t mangle -F DJPROXY_MARK) || exit 1"
            for (ifc in tetherIfaces) {
                cmds += "iptables -t mangle -A DJPROXY_MARK -i $ifc -j MARK --set-mark $fwmark || exit 1"
            }
            cmds += "(iptables -t mangle -C PREROUTING -j DJPROXY_MARK 2>/dev/null || " +
                "iptables -t mangle -A PREROUTING -j DJPROXY_MARK) || exit 1"
            // 3. Policy route: marked packets use a table whose default route is the tun.
            cmds += "ip route replace default dev $tun table $table || exit 1"
            cmds += "ip rule del fwmark $fwmark table $table 2>/dev/null || true"
            cmds += "ip rule add fwmark $fwmark table $table priority 11111 || exit 1"
            // 4. Allow the forwarded flows via OUR OWN filter chain (so revert removes only our rules,
            //    never a pre-existing FORWARD -o/-i tun ACCEPT the platform installed).
            cmds += "(iptables -N $FWD_CHAIN 2>/dev/null || iptables -F $FWD_CHAIN) || exit 1"
            cmds += "iptables -A $FWD_CHAIN -o $tun -j ACCEPT || exit 1"
            cmds += "iptables -A $FWD_CHAIN -i $tun -j ACCEPT || exit 1"
            cmds += "(iptables -C FORWARD -j $FWD_CHAIN 2>/dev/null || iptables -I FORWARD 1 -j $FWD_CHAIN) || exit 1"
            // 5. MASQUERADE out the tun via OUR OWN nat chain (same rationale).
            cmds += "(iptables -t nat -N $NAT_CHAIN 2>/dev/null || iptables -t nat -F $NAT_CHAIN) || exit 1"
            cmds += "iptables -t nat -A $NAT_CHAIN -o $tun -j MASQUERADE || exit 1"
            cmds += "(iptables -t nat -C POSTROUTING -j $NAT_CHAIN 2>/dev/null || " +
                "iptables -t nat -A POSTROUTING -j $NAT_CHAIN) || exit 1"
            // 6. IPv6 fail-closed. The hev engine carries IPv4 only, so forwarded IPv6 from tether clients
            //    would bypass the tun and egress on the phone's REAL v6 address — a direct de-anonymising
            //    leak for exactly the tethered-client case this tier protects. There is no v6 tun to route
            //    into, so DROP forwarded v6 from the tether interfaces (fail closed) whenever ip6tables is
            //    present; skip cleanly on kernels with no IPv6 netfilter (where v6 forwarding can't happen).
            cmds += "if ip6tables -L >/dev/null 2>&1; then"
            cmds += "  (ip6tables -N $FWD6_CHAIN 2>/dev/null || ip6tables -F $FWD6_CHAIN) || exit 1"
            for (ifc in tetherIfaces) {
                cmds += "  ip6tables -A $FWD6_CHAIN -i $ifc -j DROP || exit 1"
            }
            cmds += "  (ip6tables -C FORWARD -j $FWD6_CHAIN 2>/dev/null || ip6tables -I FORWARD 1 -j $FWD6_CHAIN) || exit 1"
            cmds += "fi"
            return cmds.joinToString("\n")
        }

        /**
         * Builds the teardown script that removes ONLY the rules [buildApplyScript] installs. Because
         * every accept/masquerade rule lives inside a dedicated DJPROXY_* chain, revert deletes the
         * jump + flushes + drops those chains and never touches a generic built-in FORWARD/POSTROUTING
         * rule that DJProxy did not create. Pure.
         */
        fun buildRevertScript(tun: String, tetherIfaces: List<String>, fwmark: String, table: Int): String {
            val cmds = mutableListOf<String>()
            cmds += "set +e"
            cmds += "ip rule del fwmark $fwmark table $table 2>/dev/null || true"
            cmds += "ip route flush table $table 2>/dev/null || true"
            cmds += "iptables -t mangle -D PREROUTING -j DJPROXY_MARK 2>/dev/null || true"
            cmds += "iptables -t mangle -F DJPROXY_MARK 2>/dev/null || true"
            cmds += "iptables -t mangle -X DJPROXY_MARK 2>/dev/null || true"
            cmds += "iptables -D FORWARD -j $FWD_CHAIN 2>/dev/null || true"
            cmds += "iptables -F $FWD_CHAIN 2>/dev/null || true"
            cmds += "iptables -X $FWD_CHAIN 2>/dev/null || true"
            cmds += "iptables -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null || true"
            cmds += "iptables -t nat -F $NAT_CHAIN 2>/dev/null || true"
            cmds += "iptables -t nat -X $NAT_CHAIN 2>/dev/null || true"
            // Tear down the IPv6 fail-closed DROP chain (mirrors the apply guard).
            cmds += "if ip6tables -L >/dev/null 2>&1; then"
            cmds += "  ip6tables -D FORWARD -j $FWD6_CHAIN 2>/dev/null || true"
            cmds += "  ip6tables -F $FWD6_CHAIN 2>/dev/null || true"
            cmds += "  ip6tables -X $FWD6_CHAIN 2>/dev/null || true"
            cmds += "fi"
            // Restore the pre-existing ip_forward value (do not blindly force 0 — that could break an
            // OS hotspot the user still wants); default to 1 only if the stash is missing. Then clean up.
            cmds += "if [ -f $IPFWD_STATE ]; then " +
                "OLD=\$(cat $IPFWD_STATE 2>/dev/null || echo 1); " +
                "(sysctl -w net.ipv4.ip_forward=\$OLD 2>/dev/null || echo \$OLD > /proc/sys/net/ipv4/ip_forward 2>/dev/null) || true; " +
                "rm -f $IPFWD_STATE; fi"
            return cmds.joinToString("\n")
        }
    }
}
