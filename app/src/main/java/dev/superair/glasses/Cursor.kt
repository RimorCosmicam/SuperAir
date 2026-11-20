package dev.superair.glasses

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.superair.Pointer

/**
 * The pointer, drawn by us because nothing else will.
 *
 * The system draws its own cursor for a real mouse on a real display; a panel's display is one
 * SurfaceFlinger composites into our window, and the system has no idea there is a pointer over it
 * at all. So the arrow is ours, and it is white on black with a dark outline, because on additive
 * optics a white arrow over a bright room is otherwise a smudge.
 */
@Composable
fun Cursor() {
    // Shown at once when it moves, faded out slowly when it has been still for a few seconds.
    val alpha by animateFloatAsState(
        targetValue = if (Pointer.visible) 1f else 0f,
        animationSpec = tween(if (Pointer.visible) 90 else 600),
        label = "cursor",
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            Pointer.tick()
        }
    }
    if (alpha <= 0.01f) return

    val carrying = Pointer.carrying != null
    Canvas(Modifier.fillMaxSize()) {
        val x = Pointer.x
        val y = Pointer.y
        val arrow = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 19f)
            lineTo(x + 4.6f, y + 14.6f)
            lineTo(x + 7.9f, y + 21.6f)
            lineTo(x + 11.2f, y + 20.0f)
            lineTo(x + 7.9f, y + 13.2f)
            lineTo(x + 13.6f, y + 13.0f)
            close()
        }
        // The outline first, so the arrow reads against whatever app is behind it.
        drawPath(arrow, Color.Black.copy(alpha = alpha * 0.85f), style = Stroke(width = 3f))
        drawPath(arrow, if (carrying) Color(0xFFD8A628).copy(alpha = alpha) else Color.White.copy(alpha = alpha))
        if (Pointer.pressed) {
            drawCircle(Color.White.copy(alpha = alpha * 0.3f), radius = 13f, center = Offset(x, y))
        }
    }
}
