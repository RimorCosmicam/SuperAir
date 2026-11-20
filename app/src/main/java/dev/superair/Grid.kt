package dev.superair

/**
 * The 6 x 3 grid the glasses are divided into: six columns, so three apps two cells wide sit side by side.
 *
 * Sizing by cells rather than by pixels is what makes resizing cheap and sharp at once. miniMont's
 * open question was whether another app's task could be resized at the speed a dragging finger
 * produces; snapping to cells means the question never has to be answered, because a size change
 * happens once, on release, and is a real display resize rather than a scale.
 */
data class Grid(
    val width: Int,
    val height: Int,
    val columns: Int = COLUMNS,
    val rows: Int = ROWS,
    val margin: Int = (width * 0.025f).toInt(),
    val gap: Int = (width * 0.009f).toInt(),
) {
    val cellWidth: Int get() = ((width - 2 * margin) - (columns - 1) * gap) / columns
    val cellHeight: Int get() = ((height - 2 * margin) - (rows - 1) * gap) / rows

    fun bounds(place: Place): Rect {
        val p = clamp(place)
        return Rect(
            x = margin + p.column * (cellWidth + gap),
            y = margin + p.row * (cellHeight + gap),
            width = p.spanX * cellWidth + (p.spanX - 1) * gap,
            height = p.spanY * cellHeight + (p.spanY - 1) * gap,
        )
    }

    /** A placement that fits, whatever was asked for. Spans come first; the origin gives way. */
    fun clamp(place: Place): Place {
        val spanX = place.spanX.coerceIn(1, columns)
        val spanY = place.spanY.coerceIn(1, rows)
        return Place(
            column = place.column.coerceIn(0, columns - spanX),
            row = place.row.coerceIn(0, rows - spanY),
            spanX = spanX,
            spanY = spanY,
        )
    }

    /** The cell under a point, for dropping a window where the pointer is. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val column = ((x - margin) / (cellWidth + gap)).toInt().coerceIn(0, columns - 1)
        val row = ((y - margin) / (cellHeight + gap)).toInt().coerceIn(0, rows - 1)
        return column to row
    }

    /** The first placement of this size with nothing already in it, or null when the desk is full. */
    fun freeSpot(taken: List<Place>, spanX: Int, spanY: Int): Place? {
        for (row in 0..rows - spanY) {
            for (column in 0..columns - spanX) {
                val candidate = Place(column, row, spanX, spanY)
                if (taken.none { it overlaps candidate }) return candidate
            }
        }
        return null
    }

    companion object {
        const val COLUMNS = 6
        const val ROWS = 3
    }
}

/** Where a panel sits, in cells. */
data class Place(val column: Int, val row: Int, val spanX: Int, val spanY: Int) {
    infix fun overlaps(other: Place): Boolean =
        column < other.column + other.spanX && other.column < column + spanX &&
            row < other.row + other.spanY && other.row < row + spanY
}

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    operator fun contains(point: Pair<Float, Float>): Boolean =
        point.first >= x && point.first < x + width && point.second >= y && point.second < y + height
}
