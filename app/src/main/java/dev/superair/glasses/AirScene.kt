package dev.superair.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.superair.Air
import dev.superair.PanelState
import dev.superair.Pointer

/**
 * The glasses.
 *
 * Black is the whole design. The optics are additive: a black pixel emits no light and the room
 * shows through it, so the backdrop is not a dark theme, it is the absence of one. Nothing is drawn
 * that does not have to be, and nothing is drawn in grey where black would do.
 */
@Composable
fun AirScene() {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        LaunchedEffect(widthPx, heightPx) {
            if (widthPx > 0 && heightPx > 0) Air.canvas = widthPx to heightPx
        }

        val grid = Air.grid
        // A cell is a little over a third of a phone across. 160dpi makes a 1x1 cell read as a
        // small tablet pane rather than as a phone blown up, which is what a panel should be.
        val panelDpi = 160

        for (panel in Air.panels) {
            val bounds = Air.boundsOf(panel)
            Box(
                Modifier
                    .offset { IntOffset(bounds.x, bounds.y) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                    .alpha(if (panel.dragging) 0.4f else 1f)
            ) {
                val error = panel.error
                if (error == null) {
                    PanelSurface(panel, panelDpi, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error, color = Color(0xFFEF4444), fontSize = 19.sp)
                    }
                }
                // Focus is brightness, as it is everywhere else in this family: the focused panel
                // gets the hairline, the rest get nothing. One physical pixel, so it stays a
                // hairline however large the panel is.
                if (Air.focused == panel) {
                    Box(
                        Modifier.fillMaxSize()
                            .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(10.dp))
                    )
                }
            }
        }

        // The cells you are about to drop into, shown only while a panel is being carried.
        if (Pointer.carrying != null) GridGuides()

        if (Air.launcherOpen) LauncherSheet()

        // No bar, no buttons: the glasses show apps and Home. Everything else is on the cover.

        // Last of all, over everything, because a cursor you cannot see is a cursor you have lost.
        Cursor()

        Air.message?.let { message ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)) {
                Text(message, color = Color.White.copy(alpha = 0.72f),
                    fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** The grid, shown while a panel is being placed, so you see the shape you are about to get. */
@Composable
fun GridGuides(modifier: Modifier = Modifier) {
    val grid = Air.grid
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                val bounds = grid.bounds(dev.superair.Place(column, row, 1, 1))
                Box(
                    Modifier
                        .offset { IntOffset(bounds.x, bounds.y) }
                        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                )
            }
        }
    }
}
