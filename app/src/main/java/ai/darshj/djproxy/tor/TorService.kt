package ai.darshj.djproxy.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import ai.darshj.djproxy.vpn.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The tor lane's foreground [Service] — the branded, user-visible presence that keeps the embedded Tor
 * alive independent of the Activity (SSOT §8). Declared by the **platform** lane with FGS type
 * `specialUse` subtype "Tor onion routing" (it reuses the app's existing FGS permissions — no new
 * permission needed).
 *
 * Division of labour (kept clean): the *engine* lifecycle is owned by [TorControllerImpl] +
 * [OnionProxyManager]; this service only (a) holds the OS foreground slot + a notification that mirrors
 * the live Tor state, and (b) self-stops once Tor is no longer running. It does not speak to tor
 * directly — it observes [TorGateway.controller]'s StateFlows. That keeps the state machine unit-testable
 * with zero Android and this class a thin, honest UI surface.
 */
class TorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Foreground immediately (within the 5 s window) with a "starting" notification.
        startForegroundCompat(buildNotification(NotificationCopy.bootstrapping(0)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { TorGateway.controller?.stop() }
            stopSelfSafely()
            return START_NOT_STICKY
        }
        observeState()
        return START_STICKY
    }

    /** Mirror the controller's live state into the notification and self-stop when Tor is down. */
    private fun observeState() {
        observer?.cancel()
        val controller = TorGateway.controller ?: run {
            // No controller (lane somehow absent) — nothing to keep alive.
            stopSelfSafely()
            return
        }
        observer = scope.launch {
            combine(
                controller.active,
                controller.bootstrapProgress,
                controller.phase,
            ) { active, pct, phase -> Triple(active, pct, phase) }
                .collect { (active, pct, phase) ->
                    updateNotification(
                        when {
                            active -> NotificationCopy.active()
                            phase == TorPhase.FAILED -> NotificationCopy.failed()
                            else -> NotificationCopy.bootstrapping(pct)
                        },
                    )
                    // Once Tor has genuinely stopped (user disabled, or it failed), drop the FGS.
                    if (phase == TorPhase.FAILED || (!active && phase == TorPhase.IDLE)) {
                        stopSelfSafely()
                    }
                }
        }
    }

    private fun updateNotification(copy: NotificationCopy) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(copy))
        }
    }

    private fun stopSelfSafely() {
        observer?.cancel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when DJProxy is routing through the Tor network."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(copy: NotificationCopy): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .apply { openAppIntent()?.let { setContentIntent(it) } }
            .build()
    }

    /** Launch the app on tap without importing MainActivity (decoupled from the ui lane). */
    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return runCatching { PendingIntent.getActivity(this, 0, launch, flags) }.getOrNull()
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { LogBus.w(TAG, "Tor foreground start failed: ${it.message}") }
    }

    /** Copy for the three states the notification can show. Color is never the sole signal — words are. */
    private data class NotificationCopy(val title: String, val text: String) {
        companion object {
            fun bootstrapping(pct: Int) =
                NotificationCopy("DJProxy · Tor", "Building Tor circuit… ${pct.coerceIn(0, 100)}%")

            fun active() = NotificationCopy("DJProxy · Tor", "Tor active — .onion enabled")

            fun failed() = NotificationCopy("DJProxy · Tor", "Tor could not start")
        }
    }

    companion object {
        private const val TAG = "Tor"
        private const val CHANNEL_ID = "djproxy_tor"
        private const val CHANNEL_NAME = "Tor routing"
        private const val NOTIFICATION_ID = 0x707 // "TOR"

        const val ACTION_START = "ai.darshj.djproxy.tor.action.START"
        const val ACTION_STOP = "ai.darshj.djproxy.tor.action.STOP"

        /** Start the branded foreground presence. Called by the controller's [foreground] hook. */
        fun start(context: Context) {
            val i = Intent(context, TorService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }

        /** Ask the service to tear down (also stops Tor via the controller). */
        fun stop(context: Context) {
            val i = Intent(context, TorService::class.java).setAction(ACTION_STOP)
            // Best-effort: if the service is already gone this is a harmless no-op.
            runCatching { context.startService(i) }
        }
    }
}
