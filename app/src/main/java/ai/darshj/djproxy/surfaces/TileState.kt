package ai.darshj.djproxy.surfaces

import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.VpnState

/**
 * Pure, Android-free presentation model for the QuickSettings tiles and the home-screen widget.
 *
 * Everything a surface needs to paint itself is derived HERE from the read-only [VpnState] (and, for
 * Tor, from the reflection-decoupled [TorBridge]) so the mapping is a plain function of state that
 * unit tests can pin without a device, a Robolectric shadow, or the frozen tunnel core.
 *
 * The three states map 1:1 to `android.service.quicksettings.Tile.STATE_*`; the surface classes do
 * the tiny enum→int translation so this file stays pure JVM.
 */
enum class TileVisualState { ACTIVE, INACTIVE, UNAVAILABLE }

/**
 * @param toggleable false while the tunnel is mid-transition (VALIDATING/CONNECTING/STOPPING) so a
 *   surface can debounce taps exactly like the in-app ConnectButton busy-guard — a second tap must
 *   never race a bring-up or a teardown.
 */
data class TileVisual(
    val state: TileVisualState,
    val title: String,
    val subtitle: String,
    val toggleable: Boolean,
)

/**
 * Maps the tunnel's [VpnState] to what the Connect tile / widget shows. [hasConfig] is
 * `VpnRuntime.currentConfig != null` — the presence of a last-validated proxy to (re)connect to.
 */
object ConnectTileMapper {

    const val TITLE = "DJProxy"

    fun map(vpn: VpnState, hasConfig: Boolean): TileVisual = when (vpn.stage) {
        // Up: the tile is ACTIVE and the subtitle carries the (redacted) exit endpoint as "region".
        VpnStage.CONNECTED ->
            TileVisual(TileVisualState.ACTIVE, TITLE, regionOf(vpn.proxyRedacted) ?: "Connected", true)

        VpnStage.RECONNECTING ->
            TileVisual(TileVisualState.ACTIVE, TITLE, "Reconnecting…", false)

        // Transitions: shown INACTIVE but NOT toggleable (busy-guard).
        VpnStage.VALIDATING ->
            TileVisual(TileVisualState.INACTIVE, TITLE, "Validating…", false)

        VpnStage.CONNECTING ->
            TileVisual(TileVisualState.INACTIVE, TITLE, "Connecting…", false)

        VpnStage.STOPPING ->
            TileVisual(TileVisualState.INACTIVE, TITLE, "Stopping…", false)

        // Down with a remembered config: one tap re-applies it (through validate-then-apply).
        VpnStage.ERROR ->
            TileVisual(
                TileVisualState.INACTIVE, TITLE,
                if (hasConfig) "Error · tap to retry" else "Error", hasConfig,
            )

        VpnStage.IDLE ->
            if (hasConfig) {
                TileVisual(TileVisualState.INACTIVE, TITLE, "Tap to connect", true)
            } else {
                // No proxy yet: still toggleable, but the surface routes the tap to open the app.
                TileVisual(TileVisualState.INACTIVE, TITLE, "No proxy configured", true)
            }
    }

    /**
     * Reduce a redacted proxy string to its human "region" endpoint for a subtitle.
     * `"socks5://•••@1.2.3.4:1080"` -> `"1.2.3.4:1080"`. Null/blank-safe.
     */
    fun regionOf(redacted: String?): String? {
        if (redacted.isNullOrBlank()) return null
        val afterScheme = redacted.substringAfter("://", redacted)
        val afterAuth = afterScheme.substringAfterLast('@', afterScheme)
        return afterAuth.ifBlank { null }
    }
}

/**
 * Maps the Tor lane's state (read via [TorBridge]) to the Tor tile. When the tor lane is absent the
 * tile is UNAVAILABLE — honest capability, the same rule the ui uses to hide the Tor toggle.
 */
object TorTileMapper {

    const val TITLE = "Tor"

    fun map(available: Boolean, active: Boolean, bootstrapPct: Int): TileVisual = when {
        !available ->
            TileVisual(TileVisualState.UNAVAILABLE, TITLE, "Not installed", false)

        active ->
            TileVisual(TileVisualState.ACTIVE, TITLE, ".onion enabled", true)

        bootstrapPct in 1..99 ->
            TileVisual(TileVisualState.INACTIVE, TITLE, "Building circuit… $bootstrapPct%", false)

        else ->
            TileVisual(TileVisualState.INACTIVE, TITLE, "Tap to enable", true)
    }
}
