package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/** One timed word for karaoke / SuperLyric sinks. */
internal data class LiveLyricWord(
    val text: String,
    val beginMs: Long,
    val endMs: Long,
)

/** Current lyric line snapshot shared by every system lyric sink. */
internal data class LiveLyricLine(
    val text: String,
    val translation: String?,
    val pronunciation: String?,
    val beginMs: Long,
    val endMs: Long,
    val words: List<LiveLyricWord>,
) {
    val secondary: String?
        get() = pronunciation?.takeIf { it.isNotBlank() } ?: translation?.takeIf { it.isNotBlank() }
}

internal data class LiveLyricSongMeta(
    val id: String?,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
)

internal fun interface LiveLyricSink {
    fun onLyric(song: LiveLyricSongMeta?, line: LiveLyricLine?, positionMs: Long, playing: Boolean)
}

/** Resolve the active line (including word timings for SuperLyric) from a Lyricon [Song]. */
internal object LiveLyricIndexer {
    fun lineAt(song: Song?, positionMs: Long): LiveLyricLine? {
        val lines = song?.lyrics.orEmpty()
        if (lines.isEmpty()) return null
        val index = indexAt(lines, positionMs)
        if (index < 0) return null
        return lines[index].toLiveLine()
    }

    fun indexAt(lines: List<RichLyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var candidate = -1
        for (i in lines.indices) {
            val line = lines[i]
            val begin = line.begin
            val end = line.end.takeIf { it > begin }
                ?: lines.getOrNull(i + 1)?.begin?.takeIf { it > begin }
                ?: (begin + 8_000L)
            if (positionMs < begin) break
            if (positionMs < end || i == lines.lastIndex) candidate = i
            if (positionMs < end) break
        }
        return candidate
    }

    fun RichLyricLine.toLiveLine(): LiveLyricLine {
        val text = text?.takeIf { it.isNotBlank() } ?: secondary?.takeIf { it.isNotBlank() }.orEmpty()
        val mappedWords = (words?.takeIf { it.isNotEmpty() } ?: secondaryWords?.takeIf { text == secondary })
            .orEmpty()
            .map { it.toLiveWord() }
            .filter { it.text.isNotEmpty() }
        val begin = begin.takeIf { it >= 0 } ?: mappedWords.minOfOrNull { it.beginMs } ?: 0L
        val end = end.takeIf { it > begin }
            ?: mappedWords.maxOfOrNull { it.endMs }?.takeIf { it > begin }
            ?: (begin + 4_000L)
        return LiveLyricLine(
            text = text.ifBlank { "♪" },
            translation = translation?.takeIf { it.isNotBlank() },
            pronunciation = roma?.takeIf { it.isNotBlank() },
            beginMs = begin,
            endMs = end,
            words = mappedWords,
        )
    }

    private fun LyricWord.toLiveWord() = LiveLyricWord(
        text = text.orEmpty(),
        beginMs = begin,
        endMs = end.takeIf { it > begin } ?: (begin + duration).takeIf { it > begin } ?: (begin + 300L),
    )
}
