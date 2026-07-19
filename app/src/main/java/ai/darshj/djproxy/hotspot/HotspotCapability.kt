package ai.darshj.djproxy.hotspot

import ai.darshj.djproxy.vpn.seams.HotspotCapability
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Honest, self-contained capability detection for the hotspot lane (§0 privilege tiers). It does NOT
 * fake anything:
 *
 *  - A LAN proxy endpoint that other devices point at works on ANY unrooted device, so the floor is
 *    [HotspotCapability.LAN_PROXY_ONLY] — but only if there is a reachable non-loopback address for a
 *    client to dial; with no LAN/hotspot interface at all we report [HotspotCapability.UNAVAILABLE]
 *    rather than pretend a share is reachable.
 *  - A transparent redirect that pulls tethered traffic into the tun requires real root, so
 *    [HotspotCapability.ROOT_TRANSPARENT_AVAILABLE] is only claimed when an `su` binary is present.
 *    The actual root grant is confirmed at start time (a present binary can still be denied), and a
 *    denial is surfaced honestly as a [ai.darshj.djproxy.vpn.seams.ShareResult.Fail].
 *
 * All detection is pure JVM/`File` work (no Android types) so it is exercised directly in unit tests.
 */
object HotspotCapability {

    /** Classifies the tier from the two facts that decide it. Pure — unit-tested. */
    fun classify(hasRootBinary: Boolean, hasReachableLanAddress: Boolean): HotspotCapability = when {
        hasRootBinary && hasReachableLanAddress -> HotspotCapability.ROOT_TRANSPARENT_AVAILABLE
        hasReachableLanAddress -> HotspotCapability.LAN_PROXY_ONLY
        // Root is present but there is no LAN/hotspot interface for a client to reach: neither the
        // LAN proxy nor a transparent redirect has any tethered client, so this is honestly UNAVAILABLE.
        else -> HotspotCapability.UNAVAILABLE
    }

    /** Convenience: detect both facts and classify. */
    fun detect(): HotspotCapability = classify(hasRootBinary(), localLanAddress() != null)

    /** True if a Magisk/KernelSU/legacy `su` binary exists on a well-known path. Fast, no exec. */
    fun hasRootBinary(): Boolean = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * Actually confirms root by running `su -c id` and checking for uid 0. Bounded by [timeoutMs];
     * a phone that shows a grant dialog and is ignored returns false rather than hanging. Never throws.
     */
    fun confirmRoot(timeoutMs: Long = 6_000): Boolean {
        return try {
            val proc = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val done = proc.waitForBounded(timeoutMs)
            if (!done) {
                proc.destroyForcibly()
                return false
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.exitValue() == 0 && out.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * The best LAN/hotspot IPv4 address a client on another device can dial, or null if the device is
     * on no reachable network. Prefers a tether/AP interface (the device is acting as the router), then
     * a Wi-Fi client address (devices on the same Wi-Fi can still use it as a proxy), then any other
     * site-local address. Pure JVM — unit-testable and safe off-device.
     */
    fun localLanAddress(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return null
        var best: String? = null
        var bestScore = -1
        for (nif in ifaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loop = runCatching { nif.isLoopback }.getOrDefault(true)
            if (!up || loop) continue
            val name = nif.name?.lowercase().orEmpty()
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                val host = addr.hostAddress ?: continue
                val score = scoreInterface(name, addr.isSiteLocalAddress)
                if (score > bestScore) {
                    bestScore = score
                    best = host
                }
            }
        }
        return best
    }

    /** Interface-preference scoring, split out so the ordering is unit-tested without real NICs. */
    fun scoreInterface(name: String, siteLocal: Boolean): Int = when {
        // Soft-AP / Wi-Fi hotspot interfaces — the device is literally the router.
        name.startsWith("ap") || name.contains("swlan") || name == "wlan1" -> 100
        // USB / Bluetooth / generic tethering interfaces.
        name.startsWith("rndis") || name.startsWith("usb") ||
            name.contains("tether") || name.contains("bt-pan") -> 90
        // Regular Wi-Fi client address: peers on the same Wi-Fi can point at us as a proxy.
        siteLocal && name.startsWith("wlan") -> 70
        siteLocal -> 50
        else -> 10
    }

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/magisk/.core/bin/su",
        "/debug_ramdisk/su",
    )

    private fun Process.waitForBounded(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAliveCompat()) return true
            Thread.sleep(25)
        }
        return !isAliveCompat()
    }

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
