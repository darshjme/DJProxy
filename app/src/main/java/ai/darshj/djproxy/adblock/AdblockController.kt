package ai.darshj.djproxy.adblock

import android.content.Context
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The adblock lane's controller (mirrors the shape of `TorController`/`LocationControllerImpl`): a
 * tiny state holder that owns the single `@Volatile` enabled flag both seams read and persists the
 * user's choice across launches. Toggling is instant — a plain volatile write plus a StateFlow emit —
 * with NO tunnel re-plumb, because blocking is enforced live on the CONNECT path by
 * `FeatureRegistry.blockedHostPredicate` (which closes over [isBlockingEnabled]).
 *
 * Two readers of "enabled" by design:
 *   - the hot CONNECT path calls [isBlockingEnabled] — a bare `@Volatile` read, zero allocation;
 *   - the ui collects [enabled] (a StateFlow) to drive the toggle chip + settings switch.
 *
 * Default OFF: the curated list is conservative, but ad-blocking is a user-visible behavioral change
 * (a brief connect-time spinner then host-unreachable for blocked hosts), so it never turns itself on.
 * No method throws.
 */
class AdblockController(
    private val appContext: Context,
    /** The shared blocklist — exposed so the settings panel can show an honest entry count. */
    val blocklist: Blocklist,
) {

    /** Hot-path flag: read on every CONNECT via the predicate. `@Volatile` so a toggle is seen at once. */
    @Volatile
    private var enabledFlag: Boolean = false

    private val _enabled = MutableStateFlow(false)

    /** Live toggle state for the ui (chip + settings switch collect this). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Number of domains in the active blocklist (for honest settings copy). */
    val blocklistSize: Int get() = blocklist.size

    init {
        val persisted = prefs().getBoolean(KEY_ENABLED, false)
        enabledFlag = persisted
        _enabled.value = persisted
    }

    /**
     * The bare, allocation-free read the CONNECT-path predicate uses. Kept separate from the StateFlow
     * so the hot path never touches coroutine machinery.
     */
    fun isBlockingEnabled(): Boolean = enabledFlag

    /** Turn ad-blocking on/off, persist it, and publish to the ui. Idempotent; never throws. */
    fun setEnabled(on: Boolean) {
        if (enabledFlag == on) return
        enabledFlag = on
        _enabled.value = on
        runCatching { prefs().edit().putBoolean(KEY_ENABLED, on).apply() }
        LogBus.i(TAG, if (on) "Ad-blocking enabled (${blocklist.size} domains)" else "Ad-blocking disabled")
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "djproxy_adblock_pref"
        const val KEY_ENABLED = "adblock_enabled"
        const val TAG = "Adblock"
    }
}
