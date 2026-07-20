package ai.darshj.djproxy.vpn

import android.net.VpnService
import android.os.Build
import ai.darshj.djproxy.core.ProxyConfig

/** Fixed addressing for the tun. Aligned with the engine's hev config (tunnel.ipv4 = 198.18.0.1). */
object TunConfig {
    /** The tun interface's own IPv4 (also the source the guest stack sees). Matches EngineConfig YAML. */
    const val TUN_ADDRESS = "198.18.0.1"
    const val TUN_PREFIX = 32

    /**
     * A ULA IPv6 address so the `::/0` capture route is accepted by the builder on every OEM. No
     * IPv6 is ever forwarded — the engine is IPv4-only and the router drops v6 in-tun (blackhole).
     */
    const val TUN_V6_ADDRESS = "fd00:6969::1"
    const val TUN_V6_PREFIX = 128

    /** In-tun DNS sentinel handed to the OS resolver; only queries to THIS address:53 are tunnelled. */
    const val DNS_SENTINEL = "198.18.0.2"

    const val MTU = 1500
    const val SESSION = "DJProxy"
}

/**
 * Builds the tun exactly per the leak table (DESIGN §3). This is the single place routing is
 * configured, so the CI greps that enforce the leak model (full v4 + v6 capture, no per-app
 * bypass, in-tun DNS sentinel) all point here.
 *
 * Leak vectors closed here:
 *  - #1 traffic escaping the tunnel: addRoute 0.0.0.0/0 and NO per-app allow/deny list, NO tunnel bypass.
 *  - #2 IPv6: addRoute("::/0") captures ALL v6 into the tun where it is blackholed.
 *  - #3 DNS: addDnsServer(sentinel) so the OS resolver's queries land in the tun and get tunnelled.
 */
object TunBuilder {

    fun configure(b: VpnService.Builder, config: ProxyConfig, selfPackage: String) {
        b.setSession(TunConfig.SESSION)
        b.setMtu(TunConfig.MTU)

        // Exclude ONLY our own app from the tun. This is the robust, OS-enforced way (what Postern
        // does) to keep DJProxy's own sockets — the LocalSocksServer's dial to the upstream proxy and
        // the native engine — OUT of the tunnel. Per-socket protect() is unreliable on some OEMs
        // (observed on Samsung/Android 16): the proxy socket re-entered the tun and every flow looped
        // back to the proxy address forever ("connected but no internet"). This excludes ONLY our
        // package, so EVERY OTHER installed app still egresses fully through the tun — no leak.
        try {
            b.addDisallowedApplication(selfPackage)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            // Can't happen for our own package, but the API declares it; ignore if it ever does.
        }

        // Interface addresses (v4 for real traffic; v6 ULA only to make the v6 capture route valid).
        b.addAddress(TunConfig.TUN_ADDRESS, TunConfig.TUN_PREFIX)
        b.addAddress(TunConfig.TUN_V6_ADDRESS, TunConfig.TUN_V6_PREFIX)

        // Leak vector #1: capture every IPv4 destination.
        b.addRoute("0.0.0.0", 0)

        // Leak vector #2: capture every IPv6 destination — i.e. addRoute("::/0") — then blackhole it.
        b.addRoute("::", 0)

        // Leak vector #3: the only DNS server the OS knows is our in-tun sentinel.
        b.addDnsServer(TunConfig.DNS_SENTINEL)

        // Blocking reads: the router pumps one packet per read on a dedicated thread.
        b.setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false)
        }

        // No tunnel-bypass and no per-app ALLOW list. The only exclusion is our OWN app (above), which
        // is required so the proxy dial goes direct; every OTHER installed app is fully captured.
    }
}
