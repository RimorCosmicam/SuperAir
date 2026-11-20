package dev.superair.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.superair.Air
import dev.superair.AppEntry
import dev.superair.NotificationFeed

/** The phone's notifications, shown by Home behind its battery button. */
object Notes {
    data class Note(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val clearable: Boolean,
        val autoCancel: Boolean,
    ) {
        /** Enough of an app for RoundIcon, even when it has no launcher entry. */
        val entry: AppEntry get() = Air.apps.firstOrNull { it.pkg == pkg } ?: AppEntry(app, pkg, "")
        fun ago(): String = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

    var list by mutableStateOf<List<Note>>(emptyList())
        private set

    private val main = Handler(Looper.getMainLooper())

    fun update(notes: List<Note>) = main.post { list = notes }

    /** A tap: the app comes up on the glasses, and the notice goes if it was meant to. */
    fun open(note: Note) {
        Air.apps.firstOrNull { it.pkg == note.pkg }?.let { Home.launch(it) }
        if (note.autoCancel) NotificationFeed.dismiss(note.key)
        Home.notificationsOpen = false
    }

    fun dismiss(note: Note) = NotificationFeed.dismiss(note.key)
    fun dismissAll() = NotificationFeed.dismissAll()
}

/** The battery, for the button that opens the notifications. */
object Battery {
    var level by mutableIntStateOf(-1)
        private set
    var charging by mutableStateOf(false)
        private set

    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(intent)
        }
        context.applicationContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)
    }

    private fun read(intent: Intent) {
        val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0 && s > 0) level = l * 100 / s
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }
}
