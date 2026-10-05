package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Snapshot native smart pointers while their owner is alive; retain only SDK values. */
internal class LyriconNativeSongParser(private val plainText: (String) -> String = { it }) {
    private val methods = ConcurrentHashMap<Triple<Class<*>, String, List<Class<*>>>, Method>()

    fun parse(pointer: Any): Song? {
        val native = call(pointer, "get") ?: return null
        val id = (call(native, "getAdamId") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
        val agents = vector(call(native, "getAgents"))
            .filter { number(it, "getType_") == 1L }.mapNotNull { call(it, "getId") as? String }
        val rightAgent = agents.getOrNull(1)
        val lines = vector(call(native, "getSections")).flatMap { section ->
            vector(call(section, "getLines")).map { line ->
                val primary = text(line, "getHtmlLineText")
                val secondary = text(line, "getHtmlBackgroundVocalsLineText")
                val translation = text(line, "getHtmlTranslationLineText")
                val backgroundTranslation = text(line, "getHtmlTranslatedBackgroundVocalsLineText")
                RichLyricLine(
                    begin = number(line, "getBegin"), end = number(line, "getEnd"),
                    duration = number(line, "getDuration"),
                    text = primary,
                    words = preserveLyriconWordSpacing(primary, words(call(line, "getWords"))),
                    secondary = secondary,
                    secondaryWords = preserveLyriconWordSpacing(secondary, words(call(line, "getBackgroundWords", false))),
                    translation = listOfNotNull(translation?.takeIf { it.isNotBlank() },
                        backgroundTranslation?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" ").ifBlank { null },
                    roma = text(line, "getHtmlPronunciationLineText"),
                    isAlignedRight = rightAgent != null && call(line, "getAgent") == rightAgent,
                )
            }
        }.sortedBy { it.begin }
        return Song(id = id.toString(), duration = number(native, "getDuration"), lyrics = lines)
    }

    private fun words(vector: Any?): List<LyricWord> = vector(vector).map { word ->
        LyricWord(text = text(word, "getHtmlLineText"), begin = number(word, "getBegin"),
            end = number(word, "getEnd"), duration = number(word, "getDuration"))
    }
    private fun text(native: Any, name: String): String? = (call(native, name) as? String)?.let {
        if ('<' !in it && '&' !in it) it else plainText(it)
    }
    private fun number(native: Any, name: String): Long = (call(native, name) as? Number)?.toLong() ?: 0L
    private fun vector(value: Any?): List<Any> {
        if (value == null) return emptyList()
        val size = (call(value, "size") as? Number)?.toLong() ?: return emptyList()
        require(size in 0..100_000) { "Invalid native vector size: $size" }
        return (0 until size.toInt()).mapNotNull { index ->
            call(value, "get", index.toLong())?.let { call(it, "get") }
        }
    }
    private fun call(value: Any, name: String, vararg args: Any): Any? {
        val types = args.map { when (it) {
            is Int -> Integer.TYPE
            is Long -> java.lang.Long.TYPE
            is Boolean -> java.lang.Boolean.TYPE
            else -> it.javaClass
        } }
        val key = Triple(value.javaClass, name, types)
        val member = methods.getOrPut(key) {
            generateSequence(value.javaClass as Class<*>?) { it.superclass }.firstNotNullOfOrNull {
                runCatching { it.getDeclaredMethod(name, *types.toTypedArray()).apply { isAccessible = true } }.getOrNull()
            } ?: throw NoSuchMethodException("${value.javaClass.name}#$name")
        }
        return member.invoke(value, *args)
    }
}
