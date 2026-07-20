package ai.darshj.djproxy.surfaces

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import ai.darshj.djproxy.vpn.VpnRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * QuickSettings tile that toggles the DJProxy tunnel using the LAST validated config, without opening
 * the app — while never routing without consent or validation (all enforced in [WidgetActions]).
 *
 * State is a pure function of the read-only [VpnRuntime.state] via [ConnectTileMapper]; while the QS
 * panel is open we collect the flow and repaint live, and [WidgetActions.ensureStateObserver] nudges
 * us to re-listen when state changes while the panel is closed.
 *
 * API 24+ only (TileService); declared by the platform lane, harmless meta on older OS versions.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ConnectTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        WidgetActions.ensureStateObserver(applicationContext)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { VpnRuntime.state.collect { render() } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val hasConfig = VpnRuntime.currentConfig != null
        // Consent missing OR nothing to connect to → hand off to the app (never route without consent).
        if (!WidgetActions.hasVpnConsent(this) || !hasConfig) {
            openAppFromTile()
            return
        }
        WidgetActions.toggle(this)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val v = ConnectTileMapper.map(VpnRuntime.state.value, VpnRuntime.currentConfig != null)
        tile.state = when (v.state) {
            TileVisualState.ACTIVE -> Tile.STATE_ACTIVE
            TileVisualState.INACTIVE -> Tile.STATE_INACTIVE
            TileVisualState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.label = v.title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = v.subtitle
        tile.updateTile()
    }

    /** Launch MainActivity the API-correct way for a tile (PendingIntent on 34+, Intent below). */
    private fun openAppFromTile() {
        val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK,
        ) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
