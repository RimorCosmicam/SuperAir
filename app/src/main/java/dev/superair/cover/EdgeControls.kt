package dev.superair.cover

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * TheHud's single-hand edge controls, on the touchpad: a scroll rail down the left edge, a click
 * corner at the top right and a back corner at the bottom right.
 *
 * Every touch belongs to one zone from the moment it lands, as in TheHud's TrackpadHost, so a thumb
 * on the rail or on the click corner and a finger on the pad can be down together. Holding the
 * click corner is a held mouse button: move the cursor with another finger and it drags.
 */
class EdgeZones(
    private val density: Float,
    private val onScroll: (Float) -> Unit,
    private val onClickDown: () -> Unit,
    private val onClickUp: () -> Unit,
    private val onBack: () -> Unit,
    private val onTick: () -> Unit,
) {
    var width by mutableStateOf(0f)
    var height by mutableStateOf(0f)

    var railActive by mutableStateOf(false)
        private set
    var clickPressed by mutableStateOf(false)
        private set
    var backPressed by mutableStateOf(false)
        private set

    private var railId = -1
    private var clickId = -1
    private var backId = -1
    private var backStart = Offset.Zero
    private var lastRailY = 0f
    private var railRemainder = 0f

    private val railHit get() = RAIL_HIT_DP * density
    /** TheHud's corner where the pad is tall enough; never more than 42% of it, as it holds two. */
    val cornerHit get() = minOf(CORNER_MAX_DP * density, height * 0.42f).coerceAtLeast(48 * density)

    /** The event with every zone-owned finger taken out, for the gesture recognizer; or null. */
    fun route(ev: MotionEvent): MotionEvent? {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = ev.actionIndex
            claim(ev.getPointerId(i), ev.getX(i), ev.getY(i))
        }
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id == railId) railMoved(ev.getY(i))
            if (id == backId && backPressed && hypot(ev.getX(i) - backStart.x, ev.getY(i) - backStart.y) > SLOP_DP * density) {
                backPressed = false
            }
        }
        val out = without(ev)
        when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(ev.getPointerId(ev.actionIndex), tapped = true)
            MotionEvent.ACTION_CANCEL -> listOf(railId, clickId, backId).forEach { release(it, tapped = false) }
        }
        return out
    }

    private fun claim(id: Int, x: Float, y: Float) {
        when {
            x < railHit && railId < 0 -> {
                railId = id; lastRailY = y; railRemainder = 0f; railActive = true
            }
            x > width - cornerHit && y < cornerHit && clickId < 0 && inCorner(x, y, 0f) -> {
                clickId = id; clickPressed = true; onTick(); onClickDown()
            }
            x > width - cornerHit && y > height - cornerHit && backId < 0 && inCorner(x, y, height) -> {
                backId = id; backStart = Offset(x, y); backPressed = true; onTick()
            }
        }
    }

    /** The quarter disc, a little generous: a thumb aims at the corner, not inside a curve. */
    private fun inCorner(x: Float, y: Float, cornerY: Float) =
        hypot(width - x, cornerY - y) <= cornerHit * 1.12f

    private fun railMoved(y: Float) {
        val dy = y - lastRailY
        lastRailY = y
        // The two-finger scroll's own rule, natural direction: 0.035 of a notch per pixel.
        val target = dy * 0.035f * -1f + railRemainder
        val ticks = target.toInt()
        railRemainder = target - ticks
        if (ticks != 0) onScroll(-ticks.toFloat())
    }

    private fun release(id: Int, tapped: Boolean) {
        if (id < 0) return
        when (id) {
            railId -> { railId = -1; railActive = false }
            clickId -> { clickId = -1; clickPressed = false; onClickUp() }
            backId -> {
                backId = -1
                if (tapped && backPressed) onBack()
                backPressed = false
            }
        }
    }

    private fun without(ev: MotionEvent): MotionEvent? {
        val owned = { id: Int -> id == railId || id == clickId || id == backId }
        val keep = (0 until ev.pointerCount).filter { !owned(ev.getPointerId(it)) }
        if (keep.isEmpty()) return null
        if (keep.size == ev.pointerCount) return MotionEvent.obtain(ev)
        val masked = ev.actionMasked
        val action = when (masked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (owned(ev.getPointerId(ev.actionIndex))) MotionEvent.ACTION_MOVE
                else {
                    val index = keep.indexOf(ev.actionIndex)
                    val base = when {
                        keep.size > 1 -> masked
                        masked == MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_DOWN
                        else -> MotionEvent.ACTION_UP
                    }
                    base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }
            else -> masked
        }
        val props = Array(keep.size) { MotionEvent.PointerProperties().also { p -> ev.getPointerProperties(keep[it], p) } }
        val coords = Array(keep.size) { MotionEvent.PointerCoords().also { c -> ev.getPointerCoords(keep[it], c) } }
        return MotionEvent.obtain(ev.downTime, ev.eventTime, action, keep.size, props, coords,
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags)
    }

    companion object {
        /** TheHud's hit regions are 52 x 1.32 for the rail and 104 x 1.28 for the corner. The
         *  cover's pad holds two corners, and is as short as 135dp with an app open. */
        const val RAIL_HIT_DP = 68f
        const val CORNER_MAX_DP = 84f
        const val SLOP_DP = 24f
    }
}

private val accentCyan = Color(0xFFD8F8FF)
private val railCyan = Color(0xFFCBF7FF)

/** The rail and the two corners, in TheHud's crystal: faint gradients and split hairlines. */
@Composable
fun BoxScope.EdgeControlsOverlay(zones: EdgeZones) {
    // The system's back swipe starts at the same screen edges: switch it off over the rail and
    // the corners, or sweeping the rail goes back instead of scrolling.
    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EdgeZones.RAIL_HIT_DP.dp).systemGestureExclusion())
    Rail(zones.railActive, Modifier.align(Alignment.CenterStart))
    val box = with(LocalDensity.current) { zones.cornerHit.toDp() }
    Corner(box, top = true, pressed = zones.clickPressed, back = false, modifier = Modifier.align(Alignment.TopEnd))
    Corner(box, top = false, pressed = zones.backPressed, back = true, modifier = Modifier.align(Alignment.BottomEnd))
}

@Composable
private fun Rail(active: Boolean, modifier: Modifier) {
    val boost = if (active) 1.75f else 1f
    Canvas(modifier.fillMaxHeight().width((if (active) 46 else 42).dp)) {
        drawRect(Brush.horizontalGradient(listOf(
            Color.White.copy(alpha = 0.018f * boost),
            Color.White.copy(alpha = 0.038f * boost),
            railCyan.copy(alpha = 0.026f * boost),
            Color.Transparent,
            Color.White.copy(alpha = 0.014f * boost),
        )))
        drawRect(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (active) 0.10f else 0.045f),
            Color.Transparent,
            Color.Transparent,
            Color.White.copy(alpha = if (active) 0.08f else 0.035f),
        )))
        val inner = size.width
        val out = 1.4.dp.toPx()
        fun line(x: Float, color: Color) = drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        line(inner - 0.5f, Color.White.copy(alpha = if (active) 0.24f else 0.12f))
        line(inner - out, Color.White.copy(alpha = if (active) 0.20f else 0.09f))
        line(inner + out - 1f, accentCyan.copy(alpha = if (active) 0.16f else 0.065f))
    }
}

/**
 * A corner in the rail's own material: the rail's gradient stops run out from the corner the way
 * they run across the rail from the screen edge, and its three hairlines sit on the rim, white
 * inside and cyan outside, brightened by the same 1.75 when held.
 */
@Composable
private fun Corner(box: Dp, top: Boolean, pressed: Boolean, back: Boolean, modifier: Modifier) {
    Box(modifier.size(box).clipToBounds().systemGestureExclusion()) {
        Canvas(Modifier.fillMaxSize()) {
            val boost = if (pressed) 1.75f else 1f
            val hair = 1.dp.toPx()
            val out = 1.4.dp.toPx()
            val r = size.width - out - hair
            val center = Offset(size.width, if (top) 0f else size.height)
            drawCircle(Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.018f * boost),
                0.35f to Color.White.copy(alpha = 0.038f * boost),
                0.62f to railCyan.copy(alpha = 0.026f * boost),
                0.86f to Color.Transparent,
                1f to Color.White.copy(alpha = 0.014f * boost),
                center = center, radius = r), r, center)
            // The rail is lit at its two ends; the corner is lit where its arc meets the pad edges.
            val start = if (top) 90f else 180f
            drawArc(Brush.sweepGradient(
                0f to Color.White.copy(alpha = if (pressed) 0.10f else 0.045f),
                0.07f to Color.Transparent, 0.18f to Color.Transparent,
                0.25f to Color.White.copy(alpha = if (pressed) 0.08f else 0.035f),
                0.26f to Color.Transparent, 1f to Color.White.copy(alpha = if (pressed) 0.10f else 0.045f),
                center = center),
                startAngle = start, sweepAngle = 90f, useCenter = true,
                topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2))
            fun ring(radius: Float, color: Color) =
                drawCircle(color, radius, center, style = Stroke(hair))
            ring(r, Color.White.copy(alpha = if (pressed) 0.24f else 0.12f))
            ring(r - out, Color.White.copy(alpha = if (pressed) 0.20f else 0.09f))
            ring(r + out, accentCyan.copy(alpha = if (pressed) 0.16f else 0.065f))
        }
        if (back) {
            Image(Icons.Rounded.ChevronLeft, contentDescription = "Back",
                colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (pressed) 0.9f else 0.5f)),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .offset(x = -box * 0.2f, y = -box * 0.2f).size(box * 0.34f))
        }
    }
}
