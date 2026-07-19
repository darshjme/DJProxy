package ai.darshj.djproxy.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import ai.darshj.djproxy.ui.theme.DJProxyTheme
import ai.darshj.djproxy.vpn.DjVpnService
import ai.darshj.djproxy.vpn.LogBus

/**
 * The one screen's host. Owns the two pieces of platform ceremony the product needs before the
 * paste-and-connect flow can do anything real:
 *
 *  1. The [VpnService] consent dialog — Android requires this be launched from an Activity via
 *     [android.app.Activity.startActivityForResult]; [ProxyViewModel]/[VpnController] cannot ask
 *     for it themselves. We ask once up front, then bind to the tunnel service either way.
 *  2. POST_NOTIFICATIONS on API 33+, since the tunnel runs as a foreground service and needs a
 *     visible notification while active.
 *
 * Binding to [DjVpnService] hands the view model a live [ai.darshj.djproxy.vpn.VpnController];
 * nothing is routed until the user taps Connect and the controller's own validate-then-apply
 * genuinely succeeds — this activity never calls anything that brings the tunnel up itself.
 *
 * CONTRACT NEEDED FROM THE vpn LANE: `DjVpnService` must expose a public inner class
 * `LocalBinder : Binder()` with `fun controller(): VpnController`, returned from `onBind()`, so
 * this activity can hand the live controller to the view model after binding.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels()

    private var bound = false

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            LogBus.i("UI", "VPN permission granted.")
        } else {
            LogBus.w("UI", "VPN permission was not granted — Connect will fail until it is.")
        }
        // Bind regardless: the controller itself enforces "no route without consent" at apply()
        // time (VpnService.establish() throws without prior consent), so the screen can still
        // show real validation errors even if the user declines here.
        bindVpnService()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            LogBus.w("UI", "Notification permission denied — the tunnel's foreground notification will be silent.")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DjVpnService.LocalBinder ?: return
            viewModel.attachController(localBinder.controller())
            LogBus.i("UI", "Bound to the VPN service.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            LogBus.w("UI", "VPN service connection lost.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        requestVpnConsent()

        setContent {
            DJProxyTheme {
                ProxyScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** [VpnService.prepare] returns a consent [Intent] only if the user hasn't already granted
     *  (or has revoked) permission for this app; null means we're already clear to bind. */
    private fun requestVpnConsent() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnConsentLauncher.launch(consentIntent)
        } else {
            bindVpnService()
        }
    }

    private fun bindVpnService() {
        if (bound) return
        val intent = Intent(this, DjVpnService::class.java)
        bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
}
