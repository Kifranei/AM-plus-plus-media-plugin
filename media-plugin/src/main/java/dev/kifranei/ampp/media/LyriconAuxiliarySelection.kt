package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.Song

/** Route the selected Apple Music text into Lyricon's single subtitle slot. */
internal data class LyriconAuxiliarySelection(
    val translation: Boolean = false,
    val pronunciation: Boolean = false,
) {
    fun apply(song: Song): Song = song.copy(lyrics = song.lyrics?.map { line ->
        val subtitle = when {
            translation && !line.translation.isNullOrBlank() -> line.translation
            pronunciation -> line.roma?.takeIf { it.isNotBlank() }
            else -> null
        }
        line.copy(translation = subtitle, translationWords = null, roma = null)
    })
}
