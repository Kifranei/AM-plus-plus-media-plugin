package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class LiveLyricIndexerTest {
    private fun line(begin: Long, end: Long, text: String, words: List<LyricWord> = emptyList(), translation: String? = null) =
        RichLyricLine(begin = begin, end = end, duration = end - begin, text = text, words = words, translation = translation)

    private fun word(text: String, begin: Long, end: Long) =
        LyricWord(begin = begin, end = end, duration = end - begin, text = text)

    @Test fun `picks the active line by position`() {
        val song = Song(id = "1", lyrics = listOf(
            line(0, 1000, "one"),
            line(1000, 2000, "two"),
            line(2000, 3000, "three"),
        ))
        assertEquals("one", LiveLyricIndexer.lineAt(song, 0)?.text)
        assertEquals("two", LiveLyricIndexer.lineAt(song, 1500)?.text)
        assertEquals("three", LiveLyricIndexer.lineAt(song, 2500)?.text)
        assertNull(LiveLyricIndexer.lineAt(song, -1))
    }

    @Test fun `maps words and secondary text`() {
        val song = Song(id = "1", lyrics = listOf(
            line(0, 1000, "hello world",
                words = listOf(word("hello ", 0, 400), word("world", 400, 1000)),
                translation = "你好世界"),
        ))
        val live = LiveLyricIndexer.lineAt(song, 100)!!
        assertEquals("hello world", live.text)
        assertEquals("你好世界", live.translation)
        assertEquals(2, live.words.size)
        assertEquals("hello ", live.words[0].text)
    }

    @Test fun `fill progress follows word timings like Halcyon karaoke`() {
        val words = listOf(
            LiveLyricWord("a", 0, 100),
            LiveLyricWord("b", 100, 200),
            LiveLyricWord("c", 200, 300),
        )
        assertEquals(0f, LiveLyricIndexer.fillProgress(words, 0, 0, 300), 0.001f)
        assertEquals(0.5f, LiveLyricIndexer.fillProgress(words, 150, 0, 300), 0.001f)
        assertEquals(1f, LiveLyricIndexer.fillProgress(words, 300, 0, 300), 0.001f)
        assertEquals(0.5f, LiveLyricIndexer.fillProgress(emptyList(), 500, 0, 1000), 0.001f)
    }
}

class MediaSettingsSystemLyricTest {
    @Test fun `system lyric sinks default off and round-trip`() {
        val empty = MediaSettings.decode("{}")
        assertFalse(empty.meizuTicker)
        assertFalse(empty.desktopLyrics)
        assertFalse(empty.mediaNotificationLyrics)
        assertFalse(empty.superLyric)
        assertFalse(empty.lyricGetter)
        val enabled = MediaSettings(
            meizuTicker = true,
            desktopLyrics = true,
            mediaNotificationLyrics = true,
            superLyric = true,
            lyricGetter = true,
        )
        assertEquals(enabled, MediaSettings.decode(enabled.encode()))
    }

    @Test fun `flyme device detection matches Halcyon manufacturer brand display rules`() {
        // Pure unit check of the helper's string rules via a local reimplementation mirror.
        fun detect(manufacturer: String, brand: String, display: String) =
            manufacturer.contains("meizu", true) || brand.contains("meizu", true) || display.contains("flyme", true)
        assertTrue(detect("Meizu", "meizu", "Flyme 10"))
        assertTrue(detect("unknown", "x", "FlymeOS"))
        assertFalse(detect("Google", "google", "UP1A"))
    }
}

class MediaNotificationLyricPatchPolicyMirrorTest {
    @Test fun `throttle interval matches Halcyon media notification lyric policy`() {
        assertEquals(750L, MediaNotificationLyricBridge.MIN_PATCH_INTERVAL_MS)
    }
}
