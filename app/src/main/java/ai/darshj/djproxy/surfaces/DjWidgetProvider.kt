package ai.darshj.djproxy.surfaces

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import ai.darshj.djproxy.R
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.VpnRuntime
import ai.darshj.djproxy.vpn.VpnStage
import ai.darshj.djproxy.vpn.VpnState

/**
 * Home-screen widget: connected/idle state + a connect/disconnect button + the current proxy region,
 * plus a Tor button (hidden when the tor lane is absent). Classic [RemoteViews] so it works down to
 * minSdk 21 with no new dependency.
 *
 * Reactivity without touching the frozen core: [WidgetActions.ensureStateObserver] starts a
 * process-lived collector on [VpnRuntime.state] that calls [refreshAll] on every change; button taps
 * broadcast back to this provider (an [AppWidgetProvider] is a BroadcastReceiver) and are dispatched
 * to [WidgetActions], which owns the consent/validation invariant.
 */
class DjWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WidgetActions.ensureStateObserver(context)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetActions.ensureStateObserver(context)
        ids.forEach { updateAppWidget(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isCustom = action?.startsWith("ai.darshj.djproxy.surfaces") == true
        // Defence-in-depth on top of android:exported="false": the privileged VPN-control actions
        // (TOGGLE / STOP / TOR_TOGGLE / OPEN) are only ever dispatched by THIS app's own widget
        // PendingIntents, which target this package explicitly. Refuse a custom action that did not
        // originate from our own package, so even if the receiver were ever mis-declared as exported a
        // foreign process still cannot use it as a tunnel kill-switch. The AppWidget framework's own
        // APPWIDGET_UPDATE (system-delivered, no package set) is always honoured.
        if (isCustom) {
            val pkg = intent.`package` ?: intent.component?.packageName
            if (pkg != null && pkg != context.packageName) {
                super.onReceive(context, intent)
                return
            }
        }
        when (action) {
            WidgetActions.ACTION_TOGGLE -> WidgetActions.toggle(context)
            WidgetActions.ACTION_STOP -> WidgetActions.stop(context)
            WidgetActions.ACTION_TOR_TOGGLE -> onTorToggle(context)
            WidgetActions.ACTION_OPEN -> WidgetActions.openApp(context)
        }
        super.onReceive(context, intent)
        // Repaint straight after one of our own actions (state may not have flipped yet, but the
        // button label / busy state should reflect the intent immediately).
        if (isCustom) {
            refreshAll(context)
        }
    }

    private fun onTorToggle(context: Context) {
        if (!TorBridge.available()) { WidgetActions.openApp(context); return }
        if (TorBridge.isActive()) {
            TorBridge.stop()
            WidgetActions.stop(context)
        } else {
            // Bootstrap progress + consent belong in-app.
            WidgetActions.openApp(context)
        }
    }

    companion object {

        /** Repaint every placed instance of this widget from the current [VpnRuntime] snapshot. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val cn = ComponentName(context, DjWidgetProvider::class.java)
            val ids = runCatching { mgr.getAppWidgetIds(cn) }.getOrDefault(IntArray(0))
            ids.forEach { updateAppWidget(context, mgr, it) }
        }

        private fun updateAppWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            val st = VpnRuntime.state.value
            val visual = ConnectTileMapper.map(st, VpnRuntime.currentConfig != null)
            val up = st.stage == VpnStage.CONNECTED || st.stage == VpnStage.RECONNECTING

            val rv = RemoteViews(context.packageName, R.layout.widget_djproxy)
            rv.setTextViewText(R.id.widget_status, visual.subtitle)
            rv.setTextViewText(R.id.widget_region, regionLine(st))
            rv.setTextViewText(R.id.widget_button, if (up) "Disconnect" else "Connect")

            // Primary toggle (connect/disconnect) — consent/validation enforced in WidgetActions.
            rv.setOnClickPendingIntent(
                R.id.widget_button,
                WidgetActions.broadcast(context, WidgetActions.ACTION_TOGGLE, REQ_TOGGLE),
            )
            // Tapping the header / body opens the app.
            rv.setOnClickPendingIntent(
                R.id.widget_root,
                WidgetActions.openAppPending(context, REQ_OPEN),
            )

            // Tor button: hidden entirely when the tor lane is absent (honest capability).
            val torAvailable = TorBridge.available()
            if (torAvailable) {
                rv.setViewVisibility(R.id.widget_tor, View.VISIBLE)
                rv.setTextViewText(R.id.widget_tor, if (TorBridge.isActive()) "Tor ✓" else "Tor")
                rv.setOnClickPendingIntent(
                    R.id.widget_tor,
                    WidgetActions.broadcast(context, WidgetActions.ACTION_TOR_TOGGLE, REQ_TOR),
                )
            } else {
                rv.setViewVisibility(R.id.widget_tor, View.GONE)
            }

            mgr.updateAppWidget(id, rv)
        }

        /** Prefer the location lane's human label ("Frankfurt, DE"); fall back to the redacted host. */
        private fun regionLine(st: VpnState): String {
            val label = runCatching {
                FeatureRegistry.locationController?.current?.value?.label
            }.getOrNull()
            if (!label.isNullOrBlank()) return label
            return ConnectTileMapper.regionOf(st.proxyRedacted) ?: "No proxy configured"
        }

        private const val REQ_TOGGLE = 101
        private const val REQ_OPEN = 102
        private const val REQ_TOR = 103
    }
}
