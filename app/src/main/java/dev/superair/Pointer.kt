package dev.superair

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The one cursor, living on the glasses and driven from the folded phone.
 *
 * Its coordinates are the glasses canvas, and every event it produces is injected into whichever
 * panel's display is under it, remapped to that panel's own pixels. miniDex's cursor failed because
 * a UHID mouse belongs to the system rather than to a display; this one belongs to nothing but the
 * panel it is over.
 */
object Pointer {
    var x by mutableFloatStateOf(960f)
    var y by mutableFloatStateOf(540f)
    var visible by mutableStateOf(true)
        private set
    var pressed by mutableStateOf(false)
        private set

    /** The panel being carried to a new cell, if any. */
    var carrying: PanelState? by mutableStateOf(null)
        private set

    private var lastActivity = System.currentTimeMillis()

    fun moveBy(dx: Float, dy: Float) {
        val (w, h) = Air.canvas
        x = (x + dx).coerceIn(0f, w - 1f)
        y = (y + dy).coerceIn(0f, h - 1f)
        wake()
        val panel = carrying
        if (panel != null) {
            val (column, row) = Air.grid.cellAt(x, y)
            Air.place(panel, panel.place.copy(column = column, row = row))
        } else if (!dev.superair.glasses.Targets.hover(x, y)) {
            overPanel()?.let { send(it) { host, id, px, py -> host.hover(id, px, py) } }
        }
    }

    fun moveTo(nx: Float, ny: Float) {
        val (w, h) = Air.canvas
        moveBy(nx.coerceIn(0f, w - 1f) - x, ny.coerceIn(0f, h - 1f) - y)
    }

    /** The topmost panel under the cursor. Panels are drawn in order, so the last one wins. */
    fun overPanel(): PanelState? =
        Air.panels.lastOrNull { Air.boundsOf(it).contains(x to y) }

    /** The press went to SuperAir's own controls on the glasses, not to an app. */
    private var pressedOurs = false

    fun down() {
        wake()
        // Our own buttons first: they are drawn above the panels.
        if (dev.superair.glasses.Targets.click(x, y)) { pressedOurs = true; return }
        pressedOurs = false
        val panel = overPanel() ?: return
        Air.focus(panel)
        pressed = true
        send(panel) { host, id, px, py -> host.touch(id, "down", px, py) }
    }

    fun moveWhilePressed(dx: Float, dy: Float) {
        moveBy(dx, dy)
        if (!pressed) return
        overPanel()?.let { send(it) { host, id, px, py -> host.touch(id, "move", px, py) } }
    }

    fun up() {
        wake()
        if (!pressed) return
        pressed = false
        overPanel()?.let { send(it) { host, id, px, py -> host.touch(id, "up", px, py) } }
    }

    fun tap() {
        down()
        up()
    }

    fun rightClick() {
        wake()
        if (dev.superair.glasses.Targets.hit(x, y) != null) return
        overPanel()?.let { send(it) { host, id, px, py -> host.rightClick(id, px, py) } }
    }

    fun scroll(dx: Float, dy: Float) {
        wake()
        if (dev.superair.glasses.Targets.scroll(x, y, dy)) return
        overPanel()?.let { panel ->
            send(panel) { host, id, px, py -> host.scroll(id, px, py, dx, dy) }
        }
    }

    /** Pick the panel under the cursor up, so moving the cursor moves it between cells. */
    fun grab(): Boolean {
        val panel = overPanel() ?: return false
        Air.focus(panel)
        panel.dragging = true
        carrying = panel
        wake()
        return true
    }

    fun drop() {
        carrying?.dragging = false
        carrying = null
        wake()
    }

    fun type(text: String) {
        wake()
        if (dev.superair.glasses.Home.type(text)) return
        val panel = Air.focused ?: overPanel() ?: return
        if (panel.remoteId >= 0) runCatching { Air.host?.text(panel.remoteId, text) }
    }

    fun key(code: Int, meta: Int = 0) {
        wake()
        if (dev.superair.glasses.Home.key(code)) return
        val panel = Air.focused ?: overPanel() ?: return
        if (panel.remoteId < 0) return
        runCatching {
            Air.host?.key(panel.remoteId, true, code, meta)
            Air.host?.key(panel.remoteId, false, code, meta)
        }
    }

    /** The cursor hides after a few seconds untouched; any move, click or scroll brings it back. */
    fun tick() {
        if (pressed || carrying != null) return
        if (visible && System.currentTimeMillis() - lastActivity > HIDE_AFTER_MILLIS) visible = false
    }

    private fun wake() {
        lastActivity = System.currentTimeMillis()
        visible = true
    }

    /** Glasses coordinates to that panel's own pixels. */
    private inline fun send(panel: PanelState, block: (IAirHost, Int, Float, Float) -> Unit) {
        val host = Air.host ?: return
        if (panel.remoteId < 0) return
        val bounds = Air.boundsOf(panel)
        runCatching { block(host, panel.remoteId, x - bounds.x, y - bounds.y) }
    }

    private const val HIDE_AFTER_MILLIS = 3_000L
}
