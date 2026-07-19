package ai.darshj.djproxy.vpn.seams

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam (§9.2): the location lane implements this to mock the device location to match the proxy exit
 * geo. Core calls [onProxyConnected] on CONNECTED with the resolved exit IP (may be null) and
 * [onProxyDisconnected] on teardown — both wrapped in runCatching and both MUST NOT throw.
 *
 * Honest capability tiers are exposed via [capability]; the UI copy is driven by it so the app never
 * claims GPS is spoofed when the Dev-Options mock-app grant is absent.
 */
interface LocationController {
    /** UNAVAILABLE | READY_MOCK | READY_ROOT | READY_EMULATOR. */
    val capability: StateFlow<LocationCapability>

    /** What we are currently publishing (null = nothing). */
    val current: StateFlow<SpoofedLocation?>

    /** Called by core on CONNECTED with the resolved exit IP (may be null). MUST NOT throw. */
    suspend fun onProxyConnected(exitIp: String?)

    /** Called by core on teardown. MUST NOT throw. */
    fun onProxyDisconnected()

    /** Manual override; takes precedence over exit-geo lookup. */
    fun setManualLocation(lat: Double, lng: Double)

    fun clearManual()

    fun refreshCapability(ctx: Context)
}

data class SpoofedLocation(
    val lat: Double,
    val lng: Double,
    val label: String,
    val source: String,
)

enum class LocationCapability { UNAVAILABLE, READY_MOCK, READY_ROOT, READY_EMULATOR }
