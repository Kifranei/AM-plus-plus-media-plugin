package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.LyricWord

/** Keep spaces on timed words: Lyricon reconstructs line text by concatenating words. */
internal fun preserveLyriconWordSpacing(text: String?, words: List<LyricWord>): List<LyricWord> {
    if (text.isNullOrEmpty() || words.isEmpty()) return words
    val timed = words.filter { !it.text.isNullOrBlank() }
    if (timed.isEmpty()) return words
    val starts = ArrayList<Int>(timed.size)
    var cursor = 0
    for (word in timed) {
        val value = word.text!!.trim()
        val start = text.indexOf(value, cursor)
        if (start < 0) return emptyList() // Preserve the authoritative whole line if alignment fails.
        starts += start
        cursor = start + value.length
    }
    return timed.mapIndexed { index, word ->
        val start = if (index == 0) 0 else starts[index]
        val end = starts.getOrNull(index + 1) ?: text.length
        word.copy(text = text.substring(start, end))
    }
}
