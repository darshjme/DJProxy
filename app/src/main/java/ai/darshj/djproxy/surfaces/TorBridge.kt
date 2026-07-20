package ai.darshj.djproxy.surfaces

/**
 * Reflection bridge to the tor lane's ui-seam holder `tor.TorGateway.controller` (a `TorController?`).
 *
 * The surfaces lane deliberately has **no compile-time dependency** on the tor lane: the two are
 * sibling lanes built in parallel, and a QuickSettings tile must keep compiling (and stay honest by
 * reporting Tor "unavailable") whether or not the tor package is present in a given build. So we read
 * the process-global holder + its `StateFlow` values through reflection over the stable public seam
 * shapes the DESIGN_V4 SSOT §8 pins:
 *
 *   object TorGateway { @Volatile var controller: TorController? }         // INSTANCE + getController()
 *   interface TorController {
 *       val active: StateFlow<Boolean>                                     // getActive().getValue()
 *       val bootstrapProgress: StateFlow<Int>                              // getBootstrapProgress().getValue()
 *       fun stop()                                                         // non-suspend
 *   }
 *
 * Only the NON-suspend seam members are touched here (`active`, `bootstrapProgress`, `stop`). Bringing
 * Tor UP is intentionally NOT done from a tile: it needs the VPN consent gate and a visible bootstrap
 * ("Building Tor circuit… 47%") that belongs on the in-app hero ring, so the tile hands ON off to the
 * app. Everything is wrapped so a missing/renamed member degrades to "unavailable", never a crash.
 */
object TorBridge {

    private const val GATEWAY = "ai.darshj.djproxy.tor.TorGateway"

    /** The live `TorController` instance, or null when the tor lane is absent / not yet registered. */
    private fun controller(): Any? = runCatching {
        val cls = Class.forName(GATEWAY)
        val instance = cls.getField("INSTANCE").get(null)
        cls.getMethod("getController").invoke(instance)
    }.getOrNull()

    /** True only when the tor lane is present AND has registered a controller. */
    fun available(): Boolean = controller() != null

    /** Current `active` StateFlow value; false when unavailable or unreadable. */
    fun isActive(): Boolean = runCatching {
        val c = controller() ?: return false
        val flow = c.javaClass.getMethod("getActive").invoke(c) ?: return false
        flow.javaClass.getMethod("getValue").invoke(flow) as? Boolean ?: false
    }.getOrDefault(false)

    /** Current `bootstrapProgress` StateFlow value (0..100); 0 when unavailable or unreadable. */
    fun bootstrapPercent(): Int = runCatching {
        val c = controller() ?: return 0
        val flow = c.javaClass.getMethod("getBootstrapProgress").invoke(c) ?: return 0
        flow.javaClass.getMethod("getValue").invoke(flow) as? Int ?: 0
    }.getOrDefault(0)

    /** Stops Tor (non-suspend seam). Safe no-op when the tor lane is absent. */
    fun stop() {
        runCatching {
            val c = controller() ?: return
            c.javaClass.getMethod("stop").invoke(c)
        }
    }
}
