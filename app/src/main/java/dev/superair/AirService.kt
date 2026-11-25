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
import android.util.Log
import dev.superair.glasses.GlassesPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the host up and the glasses attached, while the glasses are plugged in.
 *
 * adbd restarts around sleep and wake on this phone and takes the host with it, so this checks
 * every few seconds and starts it again. The phone is folded the whole time it is in use, so the
 * locks are not an optimisation: without them the desk goes dark in your pocket.
 *
 * They are also why this must not outlive the glasses. It used to run from boot to shutdown and
 * kept the phone awake for ten hours with the glasses in a drawer. Now it stops itself shortly
 * after the glasses go, and PairingService starts it again when they come back.
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
            var goneSince = 0L
            while (isActive) {
                if (!launcher.enabled) break
                if (GlassesPresenter.wanted(this@AirService)) {
                    goneSince = 0L
                    keepUp(launcher)
                } else {
                    val now = System.currentTimeMillis()
                    if (goneSince == 0L) goneSince = now
                    else if (now - goneSince > GONE_MILLIS) {
                        Log.i(TAG, "no glasses; stopping")
                        launcher.report("Waiting for the glasses")
                        break
                    }
                }
                delay(CHECK_MILLIS)
            }
            stopSelf()
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
        private const val TAG = "SuperAir"
        private const val CHANNEL = "glasses"
        private const val CHECK_MILLIS = 5000L
        /** As long as the host waits: a cable wiggle should not end the session. */
        private const val GONE_MILLIS = 10_000L

        /** Starts the service if SuperAir is on, paired, and the glasses are plugged in. */
        fun startIfWanted(context: Context) {
            val launcher = HostLauncher.of(context)
            if (!launcher.enabled || !launcher.paired || !GlassesPresenter.wanted(context)) return
            runCatching { context.startForegroundService(Intent(context, AirService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the service", it) }
        }
    }
}

/** The glasses are rarely plugged in at boot, but if they are, start at once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AirService.startIfWanted(context)
}
