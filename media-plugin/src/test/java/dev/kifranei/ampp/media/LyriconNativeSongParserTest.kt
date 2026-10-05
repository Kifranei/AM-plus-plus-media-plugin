package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class LyriconNativeSongParserTest {
    @Test fun `native long-index vectors preserve timing translation background and duet`() {
        val line = Line()
        val native = NativeSong(123, Vector(Section(Vector(line))))
        val song = LyriconNativeSongParser { it.replace("<b>", "").replace("</b>", "") }
            .parse(Ptr(native))!!
        assertEquals("123", song.id)
        assertEquals(4000L, song.duration)
        val mapped = song.lyrics!!.single()
        assertEquals(1000L, mapped.begin)
        assertEquals(2000L, mapped.end)
        assertEquals("Hello world", mapped.text)
        assertEquals("你好", mapped.translation)
        assertEquals("backing", mapped.secondary)
        assertEquals("hello", mapped.roma)
        assertTrue(mapped.isAlignedRight)
        assertEquals(listOf("Hello ", "world"), mapped.words!!.map { it.text })
        assertEquals(1000L, mapped.words!!.first().begin)
        assertEquals(250L, mapped.words!!.first().duration)
        assertEquals("backing", mapped.secondaryWords!!.single().text)
        line.value = "changed"
        assertEquals("Hello world", mapped.text)
    }

    @Test fun `invalid identity and null native pointers are ignored`() {
        val parser = LyriconNativeSongParser()
        assertNull(parser.parse(Ptr(null)))
        assertNull(parser.parse(Ptr(NativeSong(0, Vector()))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `corrupt native vector sizes are rejected before allocation`() {
        LyriconNativeSongParser().parse(Ptr(NativeSong(123, OversizedVector())))
    }

    class Ptr(private val value: Any?) { fun get() = value }
    class Vector(vararg values: Any) {
        private val items = values.toList()
        fun size(): Long = items.size.toLong()
        fun get(index: Long): Ptr = Ptr(items[index.toInt()])
    }
    class OversizedVector { fun size(): Long = Long.MAX_VALUE }
    class NativeSong(private val id: Long, private val sections: Any) {
        fun getAdamId() = id
        fun getDuration() = 4000
        fun getAgents() = Vector(Agent("v1"), Agent("v2"))
        fun getSections() = sections
    }
    class Agent(private val id: String) { fun getId() = id; fun getType_() = 1L }
    class Section(private val lines: Vector) { fun getLines() = lines }
    open class Timing {
        fun getBegin() = 1000
        fun getEnd() = 2000
        fun getDuration() = 1000
        fun getAgent() = "v2"
    }
    class Line : Timing() {
        var value = "<b>Hello</b> world"
        fun getHtmlLineText() = value
        fun getWords() = Vector(Word("Hello"), Word(" "), Word("world"))
        fun getBackgroundWords(parenthesis: Boolean) = Vector(Word("backing"))
        fun getHtmlBackgroundVocalsLineText() = "backing"
        fun getHtmlTranslationLineText() = "你好"
        fun getHtmlTranslatedBackgroundVocalsLineText() = ""
        fun getHtmlPronunciationLineText() = "hello"
    }
    class Word(private val text: String) {
        fun getHtmlLineText() = text
        fun getBegin() = 1000
        fun getEnd() = 1250
        fun getDuration() = 250
    }
}
