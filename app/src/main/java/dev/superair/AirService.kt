package dev.superair

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dev.superair.glasses.GlassesPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the host up and the glasses attached.
 *
 * adbd restarts around sleep and wake on this phone and takes the host with it, so this checks
 * every few seconds and starts it again. The phone is folded the whole time it is in use, so the
 * locks are not an optimisation: without them the desk goes dark in your pocket.
 */
class AirService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "SuperAir", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("SuperAir")
            .setContentText("Keeping the glasses running")
            .setContentIntent(open)
            .setOngoing(true)
            .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "superair:watchdog").apply { acquire() }
        wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "superair:watchdog").apply { acquire() }

        GlassesPresenter.start(this)

        val launcher = HostLauncher.of(this)
        scope.launch {
            while (isActive) {
                if (launcher.enabled) keepUp(launcher)
                delay(CHECK_MILLIS)
            }
        }
    }

    private suspend fun keepUp(launcher: HostLauncher) {
        if (launcher.alive()) {
            launcher.report(HostLauncher.RUNNING)
            return
        }
        launcher.report("Starting the host…")
        if (launcher.launch()) {
            repeat(20) {
                if (launcher.alive()) {
                    launcher.report(HostLauncher.RUNNING)
                    return
                }
                delay(500)
            }
            launcher.report(HostLauncher.FAILED)
        } else {
            launcher.report(if (launcher.paired) HostLauncher.WAITING else "Not paired yet")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        GlassesPresenter.stop()
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "glasses"
        private const val CHECK_MILLIS = 5000L
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launcher = HostLauncher.of(context)
        if (launcher.enabled && launcher.paired) {
            context.startForegroundService(Intent(context, AirService::class.java))
        }
    }
}
