package ai.darshj.djproxy.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The :engine sidecar. A bound + foreground [Service] that owns the native hev loop in an isolated
 * process. The main process (vpn lane's DjVpnService/watchdog) binds [IEngineControl], dup()s the
 * tun fd, and hands it here; if hev crashes, only THIS process dies and the tun/routes stay up in
 * main ⇒ traffic drops, never leaks.
 *
 * The vpn lane must declare this in AndroidManifest.xml with:
 *   android:process=":engine", android:exported="false",
 *   android:foregroundServiceType="specialUse".
 */
class EngineService : Service() {

    private val controller: EngineController = HevTunnel()

    /** The tun fd we currently own (received across the Binder). Closed on stop/replace. */
    private var ownedTun: ParcelFileDescriptor? = null

    private val binder = object : IEngineControl.Stub() {
        override fun start(
            tun: ParcelFileDescriptor,
            socksPort: Int,
            mtu: Int,
            logLevel: String?,
        ) {
            synchronized(this@EngineService) {
                // Take ownership of the passed fd. hev reads/writes it and does not close it,
                // so WE are responsible for closing it when the loop ends.
                closeTun()
                ownedTun = tun
                val config = TunnelConfig.engineConfig(
                    tunFd = tun.fd,
                    socksPort = socksPort,
                    mtu = mtu,
                    logLevel = TunnelConfig.normalizeLogLevel(logLevel),
                )
                Log.i(TAG, "start(socksPort=$socksPort, mtu=$mtu)")
                controller.start(config)
            }
        }

        override fun stop() {
            synchronized(this@EngineService) {
                Log.i(TAG, "stop()")
                controller.stop()
                closeTun()
            }
        }

        override fun state(): Int = TunnelConfig.encode(controller.state.value)

        override fun stats(): LongArray {
            val s = controller.stats()
            return longArrayOf(s.txPackets, s.txBytes, s.rxPackets, s.rxBytes)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(this) {
            controller.stop()
            closeTun()
        }
        super.onDestroy()
    }

    private fun closeTun() {
        try {
            ownedTun?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing tun fd failed", t)
        } finally {
            ownedTun = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(TunnelConfig.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            TunnelConfig.NOTIFICATION_CHANNEL_ID,
            TunnelConfig.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps the DJProxy tunnel engine alive."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, TunnelConfig.NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DJProxy engine")
            .setContentText("Tunnel transport running")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TunnelConfig.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(TunnelConfig.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "EngineService"
    }
}
