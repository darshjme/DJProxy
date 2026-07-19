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

    fun configure(b: VpnService.Builder, config: ProxyConfig) {
        b.setSession(TunConfig.SESSION)
        b.setMtu(TunConfig.MTU)

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

        // Deliberately absent: no tunnel-bypass, no per-app allow list, no per-app deny list.
        // Every installed app egresses through this one tun with no exceptions.
    }
}
