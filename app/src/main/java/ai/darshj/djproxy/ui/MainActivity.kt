package ai.darshj.djproxy.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import ai.darshj.djproxy.freeproxy.RemoteFreeProxySource
import ai.darshj.djproxy.store.SharedPreferencesProxyStore
import ai.darshj.djproxy.store.ValidatorStatusChecker
import ai.darshj.djproxy.ui.theme.DJProxyTheme
import ai.darshj.djproxy.vpn.DjVpnService
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Branded splash hold: androidx.core.splashscreen paints [Theme.DJProxy.Splash]'s emblem
     *  instantly, but on a fast device Compose's first frame can land inside a few ms — long
     *  enough that the brand art would never actually be seen. Hold the system splash on screen
     *  for a short, deliberate beat so the DJProxy mark registers, then release it. */
    private var keepSplashOnScreen = true

    /** Flips true the moment the system splash has been released, so the Compose [SplashHandoff]
     *  begins its branded ring/wordmark/attribution sequence AFTER the system emblem lifts — never
     *  hidden underneath it (the §10 hand-off was previously torn down behind the opaque system
     *  splash and was effectively invisible on a fast device). */
    private val systemSplashGone = mutableStateOf(false)

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
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        Handler(Looper.getMainLooper()).postDelayed({
            keepSplashOnScreen = false
            // Hand off to the Compose branded sequence the instant the system splash releases.
            systemSplashGone.value = true
        }, SPLASH_HOLD_MS)

        requestNotificationPermissionIfNeeded()
        requestVpnConsent()
        attachVaultSeams()

        // Ingest any launch intent (deep link / share / .ovpn open) — parses and raises the Import
        // sheet with the target for one confirmation tap; never auto-connects silently (§11).
        handleIntent(intent)

        setContent {
            DJProxyTheme {
                // The branded Compose hand-off (§10) plays over the app for its first ~900 ms so the
                // DJProxy mark registers, then cross-fades away into the live control surface. Saved
                // so it does not replay across a configuration change.
                var showBrandSplash by rememberSaveable { mutableStateOf(true) }
                Box(modifier = Modifier.fillMaxSize()) {
                    ProxyScreen(viewModel)
                    if (showBrandSplash) {
                        // start gated on the system splash having lifted, so the branded ring/wordmark/
                        // attribution actually plays on screen instead of under the system emblem (§10).
                        SplashHandoff(
                            onFinished = { showBrandSplash = false },
                            start = systemSplashGone.value,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Extracts a raw proxy string from a launch/redelivered intent and routes it through the single
     * [ProxyViewModel.ingestExternal] facade. Wrapped in runCatching so a malformed intent can never
     * crash the activity, and never auto-connects from an untrusted SEND/VIEW without the user's
     * explicit confirmation tap in the Import sheet (only `djproxy://connect` pre-emphasises Connect).
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        // Untrusted external share → parse locally only, never a silent network fetch.
                        viewModel.ingestExternal(text, autoConnect = false, allowNetwork = false)
                    }
                }
                Intent.ACTION_VIEW -> {
                    val data = intent.data ?: return@runCatching
                    when (data.scheme?.lowercase()) {
                        "djproxy" -> {
                            val payload = runCatching {
                                data.getQueryParameter("url") ?: data.getQueryParameter("uri")
                            }.getOrNull() ?: data.toString()
                            // A deep link is untrusted: no unconsented subscription GET (allowNetwork=false).
                            // autoConnect only pre-emphasises the confirm button; it never bypasses the tap.
                            viewModel.ingestExternal(
                                payload,
                                autoConnect = data.host.equals("connect", true),
                                allowNetwork = false,
                            )
                        }
                        "content", "file" -> {
                            // Read the (attacker-supplyable) stream OFF the main thread and hard-capped,
                            // so a huge/slow content:// provider cannot OOM or ANR the activity on open.
                            lifecycleScope.launch {
                                val text = withContext(Dispatchers.IO) { readTextFromUri(data) }
                                if (text != null) {
                                    viewModel.ingestExternal(text, autoConnect = false, allowNetwork = false)
                                }
                            }
                        }
                        else -> viewModel.ingestExternal(data.toString(), autoConnect = false, allowNetwork = false)
                    }
                }
            }
        }
    }

    /**
     * Reads a `content://` / `file://` import stream, bounded by [MAX_IMPORT_BYTES]. A `.ovpn`/proxy
     * import is a few hundred bytes; anything past the cap is a hostile or accidental oversized stream,
     * so we abort with null rather than growing a String until OutOfMemoryError. Runs on an IO
     * dispatcher (see caller) — never on the main thread.
     */
    private fun readTextFromUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val reader = input.bufferedReader()
            val sb = StringBuilder()
            val buf = CharArray(8192)
            var total = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) return@runCatching null
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    }.getOrNull()

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    /**
     * The user typically grants the Developer-Options "mock location app" permission by leaving
     * this activity (into system Settings) and coming back — refresh the location lane's
     * capability right here so [SettingsScreen]'s "Granted" status flips the moment the app
     * regains focus, on top of its own periodic poll. Wrapped so a missing/faulty location lane
     * can never crash the activity lifecycle (mirrors the runCatching invariant every core→lane
     * seam call already uses).
     */
    override fun onResume() {
        super.onResume()
        runCatching { FeatureRegistry.locationController?.refreshCapability(this) }
    }

    /**
     * v6: construct the three process-simple singletons the vault surface needs — the vault store
     * (SharedPreferences + reused CredentialStore), the pre-flight status checker (reuses
     * PreflightValidator via [VpnRuntime.protector]), and the free public list source — and hand them
     * to the view model, mirroring [attachController]. Wrapped in runCatching so a construction failure
     * can never crash launch; the Servers surface simply shows its empty states until attached.
     */
    private fun attachVaultSeams() {
        runCatching {
            val store = SharedPreferencesProxyStore.fromContext(applicationContext)
            val checker = ValidatorStatusChecker()
            val freeSource = RemoteFreeProxySource.create(applicationContext)
            viewModel.attachVault(store, checker, freeSource)
            LogBus.i("UI", "Proxy vault, status checker, and free-list source attached.")
        }.onFailure {
            LogBus.w("UI", "Vault seams unavailable: ${it.message}")
        }
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

    private companion object {
        /** Deliberate branded hold so the DJProxy splash emblem is actually seen, not just
         *  flashed for a frame. Trimmed to keep the whole launch moment ~1.2–1.4s (system hold +
         *  Compose hand-off) so it reads as snappy, never a stall. */
        const val SPLASH_HOLD_MS = 500L

        /** Hard cap on an imported config stream (a .ovpn / proxy line is tiny). Mirrors
         *  SubscriptionFetcher's MAX_BYTES intent — abort rather than OOM on a hostile stream. */
        const val MAX_IMPORT_BYTES = 256 * 1024
    }
}
