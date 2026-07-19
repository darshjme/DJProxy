package ai.darshj.djproxy.vpn.seams

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam (§9.3): the hotspot lane implements this to share the proxied connection with other devices.
 * Honest tiers only — on stock unrooted Android tethered traffic BYPASSES the VpnService, so the
 * default that actually works is a LAN proxy endpoint other devices point at; root enables a
 * transparent redirect. The lane self-observes VpnRuntime.state; core does not drive it.
 *
 * All suspend calls MUST NOT throw (they return a [ShareResult]).
 */
interface HotspotController {
    val capability: StateFlow<HotspotCapability>
    val share: StateFlow<ShareState>

    /** Start the honest default: LAN proxy endpoint bound to the hotspot/LAN iface. MUST NOT throw. */
    suspend fun startLanShare(requireAuth: Boolean): ShareResult

    /** Root-only transparent redirect; only if [capability] allows. MUST NOT throw. */
    suspend fun startRootTransparent(): ShareResult

    fun stopShare()

    /** Encodes phoneIP:port(+cred) for one-tap client setup, or null when not sharing. */
    fun qrPayload(): String?

    fun refreshCapability(ctx: Context)
}

enum class HotspotCapability { LAN_PROXY_ONLY, ROOT_TRANSPARENT_AVAILABLE, UNAVAILABLE }

sealed interface ShareState {
    object Off : ShareState
    data class LanProxy(val addr: String, val port: Int, val cred: String?) : ShareState
    object RootTransparent : ShareState
}

sealed interface ShareResult {
    data class Ok(val state: ShareState) : ShareResult
    data class Fail(val reason: String) : ShareResult
}
