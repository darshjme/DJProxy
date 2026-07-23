package ai.darshj.djproxy.ovpn3

import android.content.Context
import android.content.Intent
import android.os.Build
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ovpn3 lane's controller — mirrors the CONTRACT of [ai.darshj.djproxy.ovpnengine.OvpnEngineController]
 * (suspend [start] returning success/failure, [stop], an [active] StateFlow, a surfaced [lastFailure],
 * a 30 s bounded connect) but DIVERGES on the mechanism:
 *
 *   ovpnengine lane:  minivpn brings a tunnel up and exposes a loopback SOCKS5; the controller returns a
 *                     `ProxyConfig(SOCKS5, 127.0.0.1:port)` that rides the EXISTING DjVpnService.
 *   ovpn3 lane (THIS): the OpenVPN3 C++ core establishes the app's tun DIRECTLY (via [Ovpn3VpnService]'s
 *                     tun_builder callbacks). There is NO SOCKS5 and NO ProxyConfig — [start] returns a
 *                     plain success/failure boolean, and the tunnel is already device-wide when it does.
 *
 * WHY OpenVPN3 (root cause of the minivpn failure, CONFIRMED): minivpn v0.0.3 silently discarded inline
 * `<ca>/<cert>/<key>` and had no tls-auth/tls-crypt/NCP, so EVERY VPN Gate server failed with
 * "vpn connect: EOF". OpenVPN3 (the core behind OpenVPN Connect / ics-openvpn) honours the profile
 * UNCHANGED — inline PKI, tls-auth/tls-crypt, and cipher negotiation all included.
 *
 * Contract: never throws (the service seam is guarded). [start] suspends until OpenVPN3 emits CONNECTED
 * (success) or a fatal event / the 30 s bound (failure). On failure [lastFailure] carries the verbatim
 * reason for the ui.
 */
class Ovpn3EngineController(private val appContext: Context) {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The real reason the last [start] failed (verbatim OpenVPN3 event/error, or a timeout), else null. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Bring the VPN Gate server up as a device-wide OpenVPN3 tunnel from [ovpn] profile text (passed to
     * the core UNCHANGED). [label] is a short host name for the notification only. Returns true once the
     * tunnel is CONNECTED (tun established + routing), false on any failure/timeout. Suspends. Never throws.
     *
     * Bounded by [CONNECT_TIMEOUT_MS]: OpenVPN3's `connect()` runs the whole handshake and, for an
     * unreachable / UDP-blocked server, could otherwise leave the ui spinner stuck. On timeout we tear the
     * half-started service down and fail with an honest reason.
     */
    suspend fun start(ovpn: String, label: String = "VPN Gate"): Boolean {
        stopInternal()                       // ensure a clean slate; only one OpenVPN3 tunnel at a time
        Ovpn3Runtime.reset()
        lastFailure = null
        Ovpn3Runtime.pendingOvpn = ovpn
        Ovpn3Runtime.pendingLabel = label

        startService(Ovpn3VpnService.ACTION_CONNECT)

        val terminal = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            Ovpn3Runtime.phase.first { it == Ovpn3Phase.CONNECTED || it == Ovpn3Phase.ERROR }
        }
        // The staged profile has done its job; clear it so it does not linger in process memory.
        Ovpn3Runtime.pendingOvpn = null

        return when (terminal) {
            Ovpn3Phase.CONNECTED -> {
                _active.value = true
                lastFailure = null
                LogBus.i(TAG, "OpenVPN3 tunnel up (device-wide) → $label")
                true
            }
            Ovpn3Phase.ERROR -> {
                lastFailure = Ovpn3Runtime.lastFailure ?: "the OpenVPN handshake failed"
                LogBus.w(TAG, "OpenVPN3 connect failed: $lastFailure")
                stopInternal()
                _active.value = false
                false
            }
            else -> {
                // Timed out with no terminal phase: the handshake never completed.
                lastFailure = "timed out after ${CONNECT_TIMEOUT_MS / 1000}s — the server is unreachable " +
                    "or not answering (try a different VPN Gate server, ideally a nearer one)"
                LogBus.w(TAG, "OpenVPN3 $lastFailure")
                stopInternal()
                _active.value = false
                false
            }
        }
    }

    /** Tear the OpenVPN3 tunnel down. Idempotent; never throws. */
    fun stop() { runCatching { stopInternal() } }

    private fun stopInternal() {
        startService(Ovpn3VpnService.ACTION_STOP)
        _active.value = false
    }

    private fun startService(action: String) {
        val i = Intent(appContext, Ovpn3VpnService::class.java).setAction(action)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == Ovpn3VpnService.ACTION_CONNECT) {
                appContext.startForegroundService(i)
            } else {
                appContext.startService(i)
            }
        }.onFailure { LogBus.w(TAG, "service $action failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Ovpn3Engine"
        /** Upper bound on the whole OpenVPN3 bring-up. Generous for a real handshake (VPN Gate nodes
         *  take 5–15 s), short enough that an unreachable server fails instead of hanging. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
