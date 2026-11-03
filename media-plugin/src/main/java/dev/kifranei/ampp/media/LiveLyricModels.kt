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

/** Resolve the active line (and optional word progress) from a Lyricon [Song]. */
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

    /** Halcyon karaokeProgress: fraction of the active word that has been sung. */
    fun wordProgress(words: List<LiveLyricWord>, positionMs: Long): Float {
        if (words.isEmpty()) return 0f
        var completed = 0
        for (word in words) {
            when {
                positionMs < word.beginMs -> return completed.toFloat() / words.size
                positionMs >= word.endMs -> completed++
                else -> {
                    val span = (word.endMs - word.beginMs).coerceAtLeast(1L)
                    val local = ((positionMs - word.beginMs).toFloat() / span).coerceIn(0f, 1f)
                    return (completed + local) / words.size
                }
            }
        }
        return 1f
    }

    /** Cumulative width-style progress across timed words (for Canvas clip karaoke). */
    fun fillProgress(words: List<LiveLyricWord>, positionMs: Long, fallbackBegin: Long, fallbackEnd: Long): Float {
        if (words.isEmpty()) {
            val span = (fallbackEnd - fallbackBegin).coerceAtLeast(1L)
            return ((positionMs - fallbackBegin).toFloat() / span).coerceIn(0f, 1f)
        }
        val total = words.sumOf { (it.endMs - it.beginMs).coerceAtLeast(1L).toDouble() }.toFloat().coerceAtLeast(1f)
        var sung = 0f
        for (word in words) {
            val span = (word.endMs - word.beginMs).coerceAtLeast(1L).toFloat()
            when {
                positionMs <= word.beginMs -> break
                positionMs >= word.endMs -> sung += span
                else -> {
                    sung += span * ((positionMs - word.beginMs).toFloat() / span).coerceIn(0f, 1f)
                    break
                }
            }
        }
        return (sung / total).coerceIn(0f, 1f)
    }
}
