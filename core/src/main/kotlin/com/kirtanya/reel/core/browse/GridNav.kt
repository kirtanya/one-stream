package com.kirtanya.reel.core.browse

/**
 * Remote moves in a poster grid of [columns] across and [size] items, so up and down land somewhere sensible even where
 * the default geometric search would not (a short last row, a row that isn't composed yet).
 */
object GridNav {
    enum class Dir { Up, Down, Left, Right }

    /**
     * The index the remote should go to, or null to leave the key to the page: left from the first column (the side nav),
     * up from the first row (the tabs above), right past the last column, down from the last row.
     */
    fun move(index: Int, columns: Int, size: Int, dir: Dir): Int? {
        if (index !in 0 until size || columns < 1) return null
        val col = index % columns
        return when (dir) {
            Dir.Left -> if (col == 0) null else index - 1
            Dir.Right -> if (col == columns - 1 || index + 1 >= size) null else index + 1
            Dir.Up -> if (index < columns) null else index - columns
            // A short last row has no card under the far columns: land on its last card, not nowhere.
            Dir.Down -> when {
                index + columns < size -> index + columns
                (index / columns + 1) * columns < size -> size - 1
                else -> null
            }
        }
    }

    /** The card to put the remote on when a page comes back with [wanted] focused before and [size] cards loaded now. */
    fun restore(wanted: Int, size: Int): Int = if (size <= 0) 0 else wanted.coerceIn(0, size - 1)
}
