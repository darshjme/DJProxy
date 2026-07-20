package ai.darshj.djproxy.vpngate

import ai.darshj.djproxy.store.OvpnVault

/**
 * The vpngate lane's ui-facing holder (mirror of `tor.TorGateway`). `FeatureRegistry` is a **core** file
 * and may not be edited to add a `vpnGateController` slot, so — exactly like `TorGateway.controller` —
 * the lane exposes its own process-global nullable holder. The servers-tab READS this and nothing else
 * from vpngate:
 *
 *   - `VpnGateGateway.controller == null` → the lane is absent; the Servers screen HIDES the VPN Gate tab
 *     entirely (honest capability, same rule as tor/location/hotspot).
 *   - non-null                            → the tab reads [VpnGateController.servers] /
 *     [VpnGateController.refreshState] / [VpnGateController.lastFetchedAt] and drives Refresh /
 *     Export-Share / (dialable-only) Use through the controller — no core file touched.
 *
 * Set once by [VpnGateRegistrar] (an androidx.startup Initializer) before any Activity/Service. A plain
 * `@Volatile` write is enough — registration is idempotent and single-writer.
 *
 * v2 adds the [ovpnVault] holder alongside [controller], published by the SAME [VpnGateRegistrar] pass:
 * the saved-`.ovpn` vault ([OvpnVault]) is a lane-owned singleton (like [controller]), because core's
 * `FeatureRegistry` may not gain an `ovpnVault` slot. The ui/ViewModel READ this single instance the
 * same idempotent single-writer way they read [controller] — no core file touched.
 */
object VpnGateGateway {
    @Volatile
    var controller: VpnGateController? = null

    /** The saved-`.ovpn` vault (VPN Gate v2). Null until [VpnGateRegistrar] publishes it. */
    @Volatile
    var ovpnVault: OvpnVault? = null
}
