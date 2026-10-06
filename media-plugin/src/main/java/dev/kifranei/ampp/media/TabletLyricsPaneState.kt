package dev.kifranei.ampp.media

/** A temporary native-animation restore must not discard the user's pane choice. */
internal class TabletLyricsPaneState {
    var expanded = true
        private set
    fun lyricsClick(songVisible: Boolean): Boolean {
        if (!songVisible) { expanded = true; return false }
        expanded = !expanded
        return true
    }
}

internal object TabletLyricsPaneGeometry {
    fun centeredTranslation(parentWidth: Int, columnLeft: Int, columnWidth: Int, nativeTranslation: Float): Float? {
        if (parentWidth <= 0 || columnWidth <= 0 || columnLeft < 0 ||
            columnLeft.toLong() + columnWidth > parentWidth || columnWidth > parentWidth / 2) return null
        return nativeTranslation + (parentWidth - columnWidth) / 2f - columnLeft
    }
}
