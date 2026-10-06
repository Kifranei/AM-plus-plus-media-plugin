package dev.kifranei.ampp.media

internal data class TabletPlayerActionsPlacement(val outputLeft: Int, val lyricsLeft: Int, val queueLeft: Int, val top: Int)

/** Only use the iPad arrangement when the native player actually has a second column. */
internal object TabletPlayerActionsGeometry {
    data class Modes(val shuffleLeft: Int, val repeatLeft: Int, val size: Int)
    fun modes(columnLeft: Int, columnWidth: Int, previousLeft: Int, nextRight: Int, preferredSize: Int, edge: Int, gap: Int): Modes? {
        if (columnWidth <= 0 || preferredSize <= 0 || previousLeft < columnLeft || nextRight > columnLeft + columnWidth) return null
        val available = minOf(previousLeft - columnLeft, columnLeft + columnWidth - nextRight) - gap
        val size = minOf(preferredSize, available)
        if (size < preferredSize * 3 / 4) return null
        val inset = minOf(edge, available - size).coerceAtLeast(0)
        return Modes(columnLeft + inset, columnLeft + columnWidth - inset - size, size)
    }
    fun place(rootWidth: Int, rootHeight: Int, columnLeft: Int, columnWidth: Int, contentLeft: Int,
        top: Int, height: Int, lyricsWidth: Int, queueWidth: Int, edge: Int, gap: Int): TabletPlayerActionsPlacement? {
        if (rootWidth <= 0 || columnWidth <= 0 || height <= 0 || lyricsWidth <= 0 || queueWidth <= 0 ||
            top < rootHeight / 2 || top + height > rootHeight || contentLeft < columnLeft) return null
        val queue = rootWidth - edge - queueWidth
        val lyrics = queue - gap - lyricsWidth
        if (lyrics < columnLeft + columnWidth + gap) return null
        return TabletPlayerActionsPlacement(contentLeft, lyrics, queue, top)
    }
}
