package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class LyriconWordSpacingTest {
    private fun word(text: String, begin: Long) = LyricWord(begin = begin, end = begin + 100, text = text)
    @Test fun `missing inter-span spaces survive Lyricon normalization`() {
        val input = listOf(word("I", 100), word("promise", 200), word("that", 300), word("you'll", 400))
        val output = preserveLyriconWordSpacing("I promise that you'll", input)
        val normalized = RichLyricLine(begin = 100, end = 500, text = "I promise that you'll", words = output).normalize()
        assertEquals("I promise that you'll", normalized.text)
        assertEquals(listOf("I ", "promise ", "that ", "you'll"), output.map { it.text })
        assertEquals(input.map { it.begin to it.end }, output.map { it.begin to it.end })
        assertEquals("I", input.first().text)
    }
    @Test fun `empty decoded spaces punctuation and background gaps are preserved`() {
        val input = listOf(word("Hey", 100), word(" ", 150), word("kids!", 200))
        val output = preserveLyriconWordSpacing(" Hey  kids! ", input)
        assertEquals(" Hey  kids! ", output.joinToString("") { it.text.orEmpty() })
        assertEquals(2, output.size)
    }
    @Test fun `CJK syllables remain adjacent without invented spaces`() {
        val output = preserveLyriconWordSpacing("你好世界", listOf(word("你", 0), word("好", 100), word("世界", 200)))
        assertEquals(listOf("你", "好", "世界"), output.map { it.text })
    }
    @Test fun `unmatched words fall back to the full line rather than dropping text`() {
        val output = preserveLyriconWordSpacing("Different text", listOf(word("wrong", 0)))
        assertTrue(output.isEmpty())
        assertEquals("Different text", RichLyricLine(text = "Different text", words = output).normalize().text)
    }
}
