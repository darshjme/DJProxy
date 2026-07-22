package ai.darshj.djproxy.wireguard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global bridge between [WgEngineController] (driver, ui/app process) and [WgDirectVpnService]
 * (the real VpnService that owns the tun in DIRECT-TUN mode). Exact mirror of `Ovpn3Runtime`.
 *
 * WHY A HOLDER (not Intent extras): a WireGuard profile carries the interface `PrivateKey` — a secret
 * that must NEVER cross `system_server` as an Intent string extra (same rule DjVpnService follows with
 * `VpnRuntime.currentConfig`). The controller stages the [WgProfile] here in-process; the service reads
 * it directly in onStartCommand.
 *
 * This is the direct-tun lane's OWN state channel, deliberately independent of core's `VpnRuntime` so
 * the frozen core is never edited to add a WG slot — but the service ALSO mirrors its terminal
 * CONNECTING / CONNECTED / ERROR / IDLE stage into `VpnRuntime` (public `update {}`) so the existing
 * status card renders the direct-tun WARP tunnel with zero UI changes.
 */
object WgDirectRuntime {

    /** The resolved WireGuard profile the service must bring up. Set by the controller before start;
     *  read once by the service in onStartCommand. Never logged. */
    @Volatile
    var pendingProfile: WgProfile? = null

    /** A short human label for the current route ("WARP" / "WireGuard") used only for notification text
     *  and the mirrored `proxyRedacted`. */
    @Volatile
    var pendingLabel: String = "WARP"

    /** The last fatal reason the service surfaced (verbatim), or null on success. */
    @Volatile
    var lastFailure: String? = null

    private val _phase = MutableStateFlow(WgDirectPhase.IDLE)

    /** Terminal-aware lifecycle the controller awaits. The service is the sole writer via [publish]. */
    val phase: StateFlow<WgDirectPhase> = _phase.asStateFlow()

    fun publish(next: WgDirectPhase) {
        _phase.value = next
    }

    /** Reset to a clean pre-connect baseline (controller calls this before each start). */
    fun reset() {
        lastFailure = null
        _phase.value = WgDirectPhase.IDLE
    }
}

/** Coarse direct-tun bring-up lifecycle the controller blocks on. */
enum class WgDirectPhase {
    /** No direct-tun tunnel. */
    IDLE,

    /** Service started, tun established, WireGuard driving the fd, awaiting the first handshake. */
    CONNECTING,

    /** WireGuard completed a handshake (last_handshake_sec > 0); the tun is up and routing. */
    CONNECTED,

    /** Terminal failure; [WgDirectRuntime.lastFailure] explains why. Tun is down. */
    ERROR,
}
