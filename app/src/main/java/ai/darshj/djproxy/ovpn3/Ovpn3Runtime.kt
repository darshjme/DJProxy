package ai.darshj.djproxy.ovpn3

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global bridge between [Ovpn3EngineController] (driver, lives in the ui/app process) and
 * [Ovpn3VpnService] (the real VpnService that owns the tun + protect + the OpenVPN3 client thread).
 *
 * WHY A HOLDER (not Intent extras): a VPN Gate `.ovpn` embeds the inline `<ca>/<cert>/<key>` and,
 * for some servers, `auth-user-pass` credentials. Those must NEVER cross `system_server` as an
 * Intent string extra (identical rule to DjVpnService using `VpnRuntime.currentConfig`). The
 * controller stages the profile here in-process; the service reads it directly.
 *
 * This is the ovpn3 lane's OWN state channel. It is deliberately independent of core's `VpnRuntime`
 * so the frozen core is never edited to add an ovpn3 slot — but the service ALSO mirrors its terminal
 * CONNECTED / ERROR / IDLE stage into `VpnRuntime` (public `update {}`) so the existing status card
 * renders the OpenVPN3 tunnel with zero UI changes.
 */
object Ovpn3Runtime {

    /** The `.ovpn` profile text the service must bring up. Set by the controller before start; read
     *  once by the service in onStartCommand. Never logged. */
    @Volatile
    var pendingOvpn: String? = null

    /** A short human label for the current server (host name) used only for the notification text. */
    @Volatile
    var pendingLabel: String = "VPN Gate"

    /** The last fatal reason the service surfaced (verbatim OpenVPN3 event/error), or null on success. */
    @Volatile
    var lastFailure: String? = null

    private val _phase = MutableStateFlow(Ovpn3Phase.IDLE)

    /** Terminal-aware lifecycle the controller awaits. The service is the sole writer via [publish]. */
    val phase: StateFlow<Ovpn3Phase> = _phase.asStateFlow()

    fun publish(next: Ovpn3Phase) {
        _phase.value = next
    }

    /** Reset to a clean pre-connect baseline (controller calls this before each start). */
    fun reset() {
        lastFailure = null
        _phase.value = Ovpn3Phase.IDLE
    }
}

/** Coarse OpenVPN3 bring-up lifecycle the controller blocks on. */
enum class Ovpn3Phase {
    /** No OpenVPN3 tunnel. */
    IDLE,

    /** Service started, OpenVPN3 `connect()` running the handshake (tun not up yet). */
    CONNECTING,

    /** OpenVPN3 emitted the CONNECTED event and the tun is established + routing. */
    CONNECTED,

    /** Terminal failure; [Ovpn3Runtime.lastFailure] explains why. Tun is down. */
    ERROR,
}
