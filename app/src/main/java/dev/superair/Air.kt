package dev.superair

import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList

/** One app on the glasses: a placement in the grid, and the display behind it. */
class PanelState(
    val app: AppEntry,
    place: Place,
) {
    /** The host's id for this panel, or -1 until its surface exists and the app has been opened. */
    var remoteId: Int by mutableStateOf(-1)
    var place: Place by mutableStateOf(place)
    var error: String? by mutableStateOf(null)
    /** Set while the panel is being dragged, so the scene can draw it leaving its cell. */
    var dragging: Boolean by mutableStateOf(false)
    val key: Long = nextKey++

    private companion object { var nextKey = 1L }
}

data class AppEntry(val label: String, val pkg: String, val activity: String) {
    companion object {
        /** Label, package and activity, as the host sends them. */
        fun parse(line: String): AppEntry? {
            val parts = line.split('\t')
            return if (parts.size == 3) AppEntry(parts[0], parts[1], parts[2]) else null
        }
    }
}

/**
 * The desk: what is on the glasses, and the one binder that puts it there.
 *
 * A single object rather than a view model because both screens read it — the glasses draw it and
 * the folded phone in your hand drives it, and they are different windows on different displays.
 */
object Air {
    private const val TAG = "SuperAir"

    var host: IAirHost? by mutableStateOf(null)
        private set
    var apps: List<AppEntry> by mutableStateOf(emptyList())
        private set

    val panels = mutableStateListOf<PanelState>()

    /** The panel the cover screen's input goes to. */
    var focused: PanelState? by mutableStateOf(null)
        private set

    // Open from the start: an empty desk with nothing to pick is the one state that must never be shown.
    /** The cover's screen is off while the phone stays awake for the glasses: a touch wakes it. */
    @Volatile var coverDark = false

    var launcherOpen: Boolean by mutableStateOf(true)
    var message: String? by mutableStateOf(null)

    /** The pixel size of the glasses, as the scene measured it. */
    var canvas: Pair<Int, Int> by mutableStateOf(1920 to 1080)

    val grid: Grid get() = Grid(canvas.first, canvas.second)

    private val client = object : IAirClient.Stub() {
        override fun panelGone(panelId: Int) {
            post {
                if (fullscreen?.remoteId == panelId) fullscreen = null
                panels.removeAll { it.remoteId == panelId }
                if (focused?.remoteId == panelId) focused = panels.lastOrNull()
                if (panels.isEmpty()) launcherOpen = true
            }
        }

        override fun panelResized(panelId: Int, width: Int, height: Int) = Unit

        override fun coverDark(dark: Boolean) { coverDark = dark }
    }

    fun attach(binder: IBinder) {
        // The host hands its binder over every few seconds, so a restarted app finds it again.
        // The same live binder again is not news: setting everything up again each time was.
        if (host?.asBinder() == binder && binder.isBinderAlive) return
        val host = IAirHost.Stub.asInterface(binder) ?: return
        runCatching { binder.linkToDeath({ post { detach() } }, 0) }
        this.host = host
        runCatching { host.hello(client) }.onFailure { Log.w(TAG, "hello failed", it) }
        runCatching { apps = host.apps().mapNotNull(AppEntry::parse) }
            .onFailure { Log.w(TAG, "could not list apps", it) }
        Log.i(TAG, "host attached, ${apps.size} apps")
        post { dev.superair.glasses.Home.reconcile() }
        // The scene may have looked for a screen before there was a host to ask. A stand-in can
        // only exist once the binder does, so the search is worth repeating now.
        dev.superair.glasses.GlassesPresenter.refresh()
        dev.superair.glasses.GlassesPresenter.ensureService()
        allowNotifications()
    }

    /** Home's notification list needs the listener; the host can switch it on without Settings. */
    private fun allowNotifications() {
        val context = dev.superair.glasses.GlassesPresenter.appContext ?: return
        val component = android.content.ComponentName(context, NotificationFeed::class.java)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (nm.isNotificationListenerAccessGranted(component)) return
        val host = host ?: return
        Thread { runCatching { host.allowNotificationListener(component.flattenToString()) } }.start()
    }

    private fun detach() {
        Log.w(TAG, "the host went away")
        host = null
        panels.clear()
        focused = null
    }

    /** Put an app on the desk at the first free two-by-three, or wherever there is room. */
    fun open(app: AppEntry): PanelState? {
        val taken = panels.map { it.place }
        // Two columns by all three rows: a phone app's natural shape, and three of them fill the desk.
        val place = grid.freeSpot(taken, 2, 3)
            ?: grid.freeSpot(taken, 2, 2)
            ?: grid.freeSpot(taken, 1, 1)
            ?: run { message = "No room on the desk"; return null }
        val panel = PanelState(app, place)
        panels.add(panel)
        focused = panel
        launcherOpen = false
        dev.superair.glasses.Home.endSearch()
        return panel
    }

    /**
     * The panel's SurfaceView has a surface: open the app on it, or follow it to a new size.
     *
     * Everything about a panel's geometry flows through here. The scene lays the SurfaceView out in
     * its grid cell, the view reports the size it actually got, and that size becomes the display's
     * — so an app is always laid out at exactly the pixels it occupies, and never scaled.
     */
    fun surfaceReady(panel: PanelState, surface: Surface, width: Int, height: Int, dpi: Int) {
        val host = host ?: return
        if (panel.remoteId < 0) {
            runCatching {
                host.openPanel(panel.app.pkg, panel.app.activity, panel.app.label, surface, width, height, dpi)
            }.onSuccess {
                panel.remoteId = it
                panel.error = null
            }.onFailure {
                panel.error = it.message ?: "could not open ${panel.app.label}"
                Log.w(TAG, "openPanel failed", it)
            }
        } else {
            runCatching { host.resizePanel(panel.remoteId, surface, width, height, dpi) }
                .onFailure { Log.w(TAG, "resizePanel failed", it) }
        }
    }

    fun surfaceGone(panel: PanelState) {
        val host = host ?: return
        if (panel.remoteId < 0) return
        // Null parks the display on a tiny reader. Never leave it with no surface at all.
        runCatching { host.setPanelSurface(panel.remoteId, null) }
    }

    fun close(panel: PanelState) {
        runCatching { host?.closePanel(panel.remoteId) }
        panels.remove(panel)
        if (fullscreen == panel) fullscreen = null
        if (focused == panel) {
            focused = panels.lastOrNull()
        }
        if (panels.isEmpty()) launcherOpen = true
    }

    fun focus(panel: PanelState) {
        focused = panel
        if (panel.remoteId >= 0) runCatching { host?.focusPanel(panel.remoteId) }
        // Draw it last, so a panel you just picked up is the one on top.
        if (panels.lastOrNull() != panel && panels.remove(panel)) panels.add(panel)
    }

    /**
     * The app filling the whole glasses, if any. Its place in the grid is kept, so leaving
     * fullscreen puts it back exactly where it was.
     */
    var fullscreen: PanelState? by mutableStateOf(null)

    /** Where a panel is drawn: its cells, or the whole canvas while it is fullscreen. */
    fun boundsOf(panel: PanelState): Rect =
        if (fullscreen == panel) Rect(0, 0, canvas.first, canvas.second) else grid.bounds(panel.place)

    fun toggleFullscreen(panel: PanelState) {
        fullscreen = if (fullscreen == panel) null else panel
        focus(panel)
    }

    fun place(panel: PanelState, place: Place) {
        panel.place = grid.clamp(place)
    }

    /** Set a panel's footprint outright, which is what the size chooser asks for. */
    fun resizeTo(panel: PanelState, spanX: Int, spanY: Int) {
        panel.place = grid.clamp(panel.place.copy(spanX = spanX, spanY = spanY))
    }

    /** Grow or shrink the focused panel inside the grid. */
    fun resize(panel: PanelState, dx: Int, dy: Int) {
        val current = panel.place
        panel.place = grid.clamp(current.copy(
            spanX = (current.spanX + dx).coerceIn(1, Grid.COLUMNS),
            spanY = (current.spanY + dy).coerceIn(1, Grid.ROWS),
        ))
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
