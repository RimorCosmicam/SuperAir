package dev.superair

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.superair.glasses.Notes

/**
 * The phone's notifications, for Home's list on the glasses and on the cover.
 *
 * Access is granted by the host, which is the shell user and may switch a listener on
 * (`cmd notification allow_listener`), so there is no Settings screen to send anybody to.
 */
class NotificationFeed : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "notification listener connected")
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val list = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        val pm = packageManager
        val notes = list.asSequence()
            .filter { it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
                // An ongoing notice with nothing to read is a service keeping itself alive.
                if (title.isEmpty() && text.isEmpty()) return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                Notes.Note(
                    key = sbn.key,
                    pkg = sbn.packageName,
                    app = label,
                    title = title,
                    text = text,
                    time = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime,
                    clearable = sbn.isClearable,
                    autoCancel = sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
                )
            }
            .sortedByDescending { it.time }
            .toList()
        Notes.update(notes)
    }

    companion object {
        private const val TAG = "SuperAir"

        @Volatile var instance: NotificationFeed? = null
            private set

        fun dismiss(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }

        fun dismissAll() {
            runCatching { instance?.cancelAllNotifications() }
        }
    }
}
