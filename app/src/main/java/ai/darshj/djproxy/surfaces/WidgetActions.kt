package ai.darshj.djproxy.surfaces

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.VpnControllerImpl
import ai.darshj.djproxy.vpn.VpnRuntime
import ai.darshj.djproxy.vpn.VpnStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one place the tile + widget share their action logic and PendingIntent plumbing, and where the
 * consent/validation invariant lives.
 *
 * INVARIANT (never routes without consent OR validation):
 *  - CONNECT never fires `DjVpnService` ACTION_CONNECT directly. It goes through the EXISTING
 *    [VpnControllerImpl.apply] — the app's own validate-then-up path — so a surface can only bring the
 *    tunnel up on a genuine pre-flight success, exactly like the in-app Connect button.
 *  - Before calling apply we check [VpnService.prepare]: a non-null result means VPN consent is not
 *    granted, so we open the app (which owns the consent dialog) instead of routing.
 *  - No last-validated config → open the app. STOP needs no consent and just tears down.
 */
object WidgetActions {

    const val ACTION_TOGGLE = "ai.darshj.djproxy.surfaces.TOGGLE"
    const val ACTION_STOP = "ai.darshj.djproxy.surfaces.STOP"
    const val ACTION_TOR_TOGGLE = "ai.darshj.djproxy.surfaces.TOR_TOGGLE"
    const val ACTION_OPEN = "ai.darshj.djproxy.surfaces.OPEN"

    private const val TAG = "surfaces"

    /** Process-lived scope: apply() suspends across a network pre-flight; the work must outlive the
     *  short-lived tile/receiver that triggered it. The tunnel service does the heavy lifting once
     *  apply() hands off; this scope only needs to survive the validation window. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var observerStarted = false

    // ---- consent ---------------------------------------------------------------------------------

    /** True when the OS has already granted VPN consent for this app (`prepare()` returns null). */
    fun hasVpnConsent(context: Context): Boolean =
        runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    // ---- primary actions -------------------------------------------------------------------------

    private fun controller(context: Context) = VpnControllerImpl(context.applicationContext)

    /** State-aware primary action shared by the Connect tile + widget button. */
    fun toggle(context: Context) {
        when (VpnRuntime.state.value.stage) {
            VpnStage.CONNECTED, VpnStage.RECONNECTING -> stop(context)
            // Busy: swallow the tap (busy-guard) — never race a bring-up / teardown.
            VpnStage.VALIDATING, VpnStage.CONNECTING, VpnStage.STOPPING -> Unit
            VpnStage.IDLE, VpnStage.ERROR -> connect(context)
        }
    }

    fun connect(context: Context) {
        val app = context.applicationContext
        val config: ProxyConfig = VpnRuntime.currentConfig ?: run { openApp(app); return }
        if (!hasVpnConsent(app)) { openApp(app); return } // consent gate → the app owns the dialog
        scope.launch {
            runCatching { controller(app).apply(config) }
                .onFailure { LogBus.e(TAG, "surface connect failed: ${it.message}") }
        }
    }

    fun stop(context: Context) {
        runCatching { controller(context).stop() }
            .onFailure { LogBus.e(TAG, "surface stop failed: ${it.message}") }
    }

    /** Bring the app to the foreground (consent, config entry, or Tor bootstrap all live there). */
    fun openApp(context: Context) {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        runCatching { context.startActivity(i) }
    }

    // ---- PendingIntents for the widget RemoteViews ------------------------------------------------

    fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(context, DjWidgetProvider::class.java)
            .setAction(action)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, requestCode, i, piFlags())
    }

    fun openAppPending(context: Context, requestCode: Int): PendingIntent {
        val i = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, i, piFlags())
    }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    // ---- reactive updates ------------------------------------------------------------------------

    /**
     * Idempotently starts a process-lived collector on the read-only [VpnRuntime.state] that pushes
     * changes out to the home-screen widgets and nudges the tiles to re-render. This is how surfaces
     * stay live without touching the frozen core: core publishes state, we observe and repaint. The
     * collector lives as long as the process; when the process dies the widget simply keeps its last
     * frame until the next interaction / system update.
     */
    fun ensureStateObserver(context: Context) {
        if (observerStarted) return
        synchronized(this) {
            if (observerStarted) return
            observerStarted = true
        }
        val app = context.applicationContext
        scope.launch {
            var last: Pair<VpnStage, String?>? = null
            VpnRuntime.state.collect { st ->
                val key = st.stage to st.proxyRedacted
                if (key != last) {
                    last = key
                    runCatching { DjWidgetProvider.refreshAll(app) }
                    requestTileUpdate(app)
                }
            }
        }
    }

    /** Ask the system to re-listen to our tiles so they repaint on a state change (API 24+). */
    private fun requestTileUpdate(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            TileService.requestListeningState(
                context, ComponentName(context, ConnectTileService::class.java),
            )
        }
        runCatching {
            TileService.requestListeningState(
                context, ComponentName(context, TorTileService::class.java),
            )
        }
    }
}
