package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class LyriconSongStateTest {
    @Test fun `subtitle follows live switches and restores cached translation without reloading`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        val native = Song(id = "1", lyrics = listOf(RichLyricLine(
            text = "Hello", translation = "你好", roma = "hello",
            secondary = "backing", isAlignedRight = true,
        )))
        state.metadata(Song(id = "1"))
        state.lyrics(native, installed = true)
        assertNull(output.last()?.lyrics?.single()?.translation)
        state.auxiliary(LyriconAuxiliarySelection(translation = true))
        assertEquals("你好", output.last()?.lyrics?.single()?.translation)
        state.auxiliary(LyriconAuxiliarySelection())
        assertNull(output.last()?.lyrics?.single()?.translation)
        state.auxiliary(LyriconAuxiliarySelection(pronunciation = true))
        assertEquals("hello", output.last()?.lyrics?.single()?.translation)
        assertNull(output.last()?.lyrics?.single()?.roma)
        state.auxiliary(LyriconAuxiliarySelection(translation = true, pronunciation = true))
        assertEquals("你好", output.last()?.lyrics?.single()?.translation)
        assertTrue(output.last()?.lyrics?.single()?.isAlignedRight == true)
        assertEquals("backing", output.last()?.lyrics?.single()?.secondary)
        assertEquals("你好", native.lyrics?.single()?.translation)
        assertEquals("hello", native.lyrics?.single()?.roma)
    }

    @Test fun `selected pronunciation fills a line without translation and hidden words stay hidden`() {
        val stateOutput = mutableListOf<Song?>()
        val state = LyriconSongState(stateOutput::add)
        state.auxiliary(LyriconAuxiliarySelection(translation = true, pronunciation = true))
        state.metadata(Song(id = "1"))
        state.lyrics(Song(id = "1", lyrics = listOf(RichLyricLine(text = "字", roma = "zi",
            translationWords = listOf(io.github.proify.lyricon.lyric.model.LyricWord(text = "stale"))))))
        assertEquals("zi", stateOutput.last()?.lyrics?.single()?.normalize()?.translation)
        state.auxiliary(LyriconAuxiliarySelection())
        assertNull(stateOutput.last()?.lyrics?.single()?.normalize()?.translation)
        state.metadata(Song(id = "2"))
        assertTrue(stateOutput.last()?.lyrics.isNullOrEmpty())
    }

    @Test fun `new lyric replacement cannot borrow subtitles from an earlier version`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.auxiliary(LyriconAuxiliarySelection(translation = true))
        state.metadata(Song(id = "1"))
        state.lyrics(Song(id = "1", lyrics = listOf(RichLyricLine(text = "old", translation = "旧"))), installed = true)
        state.lyrics(lyrics("1", "replacement"), installed = true)
        assertEquals("replacement", output.last()?.lyrics?.single()?.text)
        assertNull(output.last()?.lyrics?.single()?.translation)
    }

    private fun lyrics(id: String, text: String) = Song(id = id, duration = 3000,
        lyrics = listOf(RichLyricLine(begin = 0, end = 3000, text = text)))

    @Test fun `late previous track lyrics cannot overwrite the current track`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.metadata(Song(id = "1", name = "One"))
        state.metadata(Song(id = "2", name = "Two"))
        state.lyrics(lyrics("1", "old"))
        assertEquals(2, output.size)
        assertEquals("2", output.last()?.id)
        state.lyrics(lyrics("2", "new"))
        assertEquals("new", output.last()?.lyrics?.single()?.text)
        assertEquals("Two", output.last()?.name)
    }

    @Test fun `lyrics arriving before metadata are cached and restored on reconnect`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.lyrics(lyrics("1", "ready"))
        assertTrue(output.isEmpty())
        assertTrue(state.metadata(Song(id = "1", artist = "Artist")))
        assertTrue(state.hasLyrics)
        assertEquals(3000L, output.last()?.duration)
        assertFalse(state.metadata(Song(id = "1", artist = "Corrected")))
        assertEquals("ready", output.last()?.lyrics?.single()?.text)
        assertEquals("Corrected", output.last()?.artist)
        val count = output.size
        state.lyrics(lyrics("1", "ready"))
        assertEquals(count, output.size)
        state.metadata(null)
        assertNull(output.last())
        state.lyrics(lyrics("1", "late"))
        assertNull(output.last())
    }

    @Test fun `older tracks are evicted from the bounded lyric cache`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        for (id in 1..17) state.lyrics(lyrics(id.toString(), "track"))
        state.metadata(Song(id = "1"))
        assertFalse(state.hasLyrics)
        state.metadata(Song(id = "17"))
        assertTrue(state.hasLyrics)
    }

    @Test fun `installed AM replacement wins over a late official download`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.metadata(Song(id = "1"))
        state.lyrics(lyrics("1", "manual"), installed = true)
        state.lyrics(lyrics("1", "official"))
        assertEquals("manual", output.last()?.lyrics?.single()?.text)
        state.lyrics(lyrics("1", "updated translation"), installed = true)
        assertEquals("updated translation", output.last()?.lyrics?.single()?.text)
    }
}
