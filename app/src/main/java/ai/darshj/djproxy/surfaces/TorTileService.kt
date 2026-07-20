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
 * QuickSettings tile for Tor (the tor lane is read via the reflection-decoupled [TorBridge], so this
 * compiles and stays honest whether or not the tor package is present).
 *
 * Turning Tor ON requires the VPN consent gate and a visible bootstrap ("Building Tor circuit… %")
 * that belongs on the in-app hero ring, so ON hands off to the app. Turning OFF is a direct, local
 * teardown: stop Tor, then stop the tunnel (which had been routing through 127.0.0.1:9050).
 *
 * The tile is UNAVAILABLE when the tor lane is absent — honest capability, mirroring how the ui hides
 * the Tor toggle when `TorGateway.controller == null`.
 */
@RequiresApi(Build.VERSION_CODES.N)
class TorTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        WidgetActions.ensureStateObserver(applicationContext)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        // The tunnel state flow also drives Tor-off reflection (tunnel down ⇒ .onion gone); repaint on it.
        s.launch { VpnRuntime.state.collect { render() } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!TorBridge.available()) { render(); return }
        if (TorBridge.isActive()) {
            // Turn OFF: stop Tor, then drop the tunnel that was routing through it.
            TorBridge.stop()
            WidgetActions.stop(this)
        } else {
            // Turn ON: consent + bootstrap progress belong in-app.
            openAppFromTile()
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val v = TorTileMapper.map(TorBridge.available(), TorBridge.isActive(), TorBridge.bootstrapPercent())
        tile.state = when (v.state) {
            TileVisualState.ACTIVE -> Tile.STATE_ACTIVE
            TileVisualState.INACTIVE -> Tile.STATE_INACTIVE
            TileVisualState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.label = v.title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = v.subtitle
        tile.updateTile()
    }

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
