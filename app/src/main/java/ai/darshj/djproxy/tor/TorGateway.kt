package ai.darshj.djproxy.tor

/**
 * The tor lane's ui-facing holder (SSOT §0.3 / §8). `FeatureRegistry` is a **core** file and may not
 * be edited to add a `torController` slot, so — exactly like `FeatureRegistry.locationController` but
 * living in this lane — the tor lane exposes its own process-global nullable holder. The ui lane
 * READS this and nothing else from tor:
 *
 *   - `TorGateway.controller == null`  → the tor lane is absent; the ui hides the Tor toggle entirely
 *     (honest capability, same rule as location/hotspot).
 *   - non-null                          → the ui reads `bootstrapProgress` / `active` / `phase`, drives
 *     the synthetic `PREPARING_TOR` arc, and on ready applies `controller.proxyConfig()` through the
 *     EXISTING `VpnController.apply` — no core file touched.
 *
 * Set once by [TorRegistrar] (an androidx.startup Initializer) before any Activity/Service. A plain
 * `@Volatile` write is enough — registration is idempotent and single-writer.
 */
object TorGateway {
    @Volatile
    var controller: TorController? = null
}
