package ai.darshj.djproxy.adblock

/**
 * The adblock lane's ui-facing holder (mirror of `tor/TorGateway`). `FeatureRegistry` is a **core**
 * file and may not be edited to add an `adblockController` slot, so — exactly like `TorGateway.controller`
 * — the lane exposes its own process-global nullable holder. The ui lane READS this and nothing else
 * from adblock:
 *
 *   - `AdblockGateway.controller == null` → the adblock lane is absent; the ui hides the Block-ads
 *     toggle entirely (honest capability, same rule as Tor/location).
 *   - non-null                            → the ui collects `controller.enabled` for the chip/switch
 *     state and calls `controller.setEnabled(..)` on toggle. No core file is touched.
 *
 * Set once by [AdblockRegistrar] (an androidx.startup Initializer) before any Activity/Service. A plain
 * `@Volatile` write is enough — registration is idempotent and single-writer.
 */
object AdblockGateway {
    @Volatile
    var controller: AdblockController? = null
}
