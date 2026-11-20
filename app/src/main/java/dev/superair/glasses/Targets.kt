package dev.superair.glasses

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * What the cursor can press on the glasses.
 *
 * The glasses window takes no touches — nobody can touch a picture floating in the room — so an
 * ordinary `clickable` there never fires. Everything the cursor should be able to press registers
 * its bounds here instead, and [dev.superair.Pointer] asks here first, before it hands the press
 * to an app's panel underneath. The scene fills the display from its top left corner, so root
 * coordinates are the cursor's coordinates.
 */
object Targets {
    class Target(
        val layer: Int,
        var bounds: Rect,
        var onClick: () -> Unit,
        var onHover: (() -> Unit)?,
        var onScroll: ((Float) -> Unit)?,
        val order: Long,
    )

    private val targets = LinkedHashMap<Any, Target>()
    private var counter = 0L

    /** The target under the cursor, so it can light up. */
    var hovered: Any? by mutableStateOf(null)
        private set

    fun hit(x: Float, y: Float): Pair<Any, Target>? =
        targets.entries.filter { it.value.bounds.contains(androidx.compose.ui.geometry.Offset(x, y)) }
            .maxWithOrNull(compareBy({ it.value.layer }, { it.value.order }))
            ?.let { it.key to it.value }

    fun hover(x: Float, y: Float): Boolean {
        val hit = hit(x, y)
        if (hovered != hit?.first) {
            hovered = hit?.first
            hit?.second?.onHover?.invoke()
        }
        return hit != null
    }

    /** True when the press was ours, so it must not reach the app underneath. */
    fun click(x: Float, y: Float): Boolean {
        val hit = hit(x, y) ?: return false
        hit.second.onClick()
        return true
    }

    fun scroll(x: Float, y: Float, dy: Float): Boolean {
        val hit = targets.values.filter { it.onScroll != null && it.bounds.contains(androidx.compose.ui.geometry.Offset(x, y)) }
            .maxWithOrNull(compareBy({ it.layer }, { it.order })) ?: return hit(x, y) != null
        hit.onScroll?.invoke(dy)
        return true
    }

    internal fun put(key: Any, target: Target) { targets[key] = target }
    internal fun remove(key: Any) { targets.remove(key); if (hovered == key) hovered = null }
    internal fun next() = counter++

    /** Layers: the bar is above the sheets, which are above anything else drawn on the glasses. */
    const val SHEET = 10
    const val SHEET_ITEM = 11
    const val FOLDER = 14
    const val FOLDER_ITEM = 15
    const val BAR = 20
}

/**
 * Pressable by the cursor. Also an ordinary clickable is not needed: nothing touches the glasses.
 * [key] identifies the target for hover highlighting; pass something stable and unique.
 */
fun Modifier.cursorTarget(
    key: Any,
    layer: Int,
    onHover: (() -> Unit)? = null,
    onScroll: ((Float) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val target = remember(key) {
        Targets.Target(layer, Rect.Zero, onClick, onHover, onScroll, Targets.next())
    }
    target.onClick = onClick
    target.onHover = onHover
    target.onScroll = onScroll
    DisposableEffect(key) {
        Targets.put(key, target)
        onDispose { Targets.remove(key) }
    }
    onGloballyPositioned { target.bounds = it.boundsInRoot() }
}

/** Whether the cursor is over [key]. */
@Composable
fun hovered(key: Any): Boolean = Targets.hovered == key
