package dev.kifranei.ampp.media

import android.content.Context

/**
 * Lyric Getter API bridge adapted from Halcyon's LyricGetterBridge.
 * Loaded by reflection so the plugin ZIP never bundles cn.lyric.getter; fail-open when absent.
 */
internal class LyricGetterBridge(context: Context) : LiveLyricSink {
    private val packageName = context.packageName
    @Volatile private var enabled = false
    private var lastPayload: String? = null
    private val api: Any? = runCatching {
        Class.forName("cn.lyric.getter.api.API").getDeclaredConstructor().newInstance()
    }.getOrNull()

    fun setEnabled(value: Boolean) {
        enabled = value
        lastPayload = null
        if (!value) clearLyric()
    }

    fun isAvailable(): Boolean = api != null

    override fun onLyric(song: LiveLyricSongMeta?, line: LiveLyricLine?, positionMs: Long, playing: Boolean) {
        if (!enabled) return
        if (!playing || line == null) {
            clearLyric()
            return
        }
        sendLyric(line)
    }

    fun sendLyric(line: LiveLyricLine) {
        val receiver = api ?: return
        if (!enabled) return
        val text = line.text.trim().takeIf { it.isNotBlank() && !it.isMusicSymbolOnly() } ?: return
        val payload = "${line.beginMs}:$text"
        if (payload == lastPayload) return
        lastPayload = payload
        runCatching {
            val extraClass = Class.forName("cn.lyric.getter.api.data.ExtraData")
            val extra = extraClass.getDeclaredConstructor().newInstance()
            runCatching { extraClass.getMethod("setPackageName", String::class.java).invoke(extra, packageName) }
            runCatching { extraClass.getMethod("setBase64Icon", String::class.java).invoke(extra, "") }
            runCatching { extraClass.getMethod("setUseOwnMusicController", Boolean::class.javaPrimitiveType).invoke(extra, false) }
            val delay = (line.endMs - line.beginMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            runCatching { extraClass.getMethod("setDelay", Int::class.javaPrimitiveType).invoke(extra, delay) }
            receiver.javaClass.getMethod("sendLyric", String::class.java, extraClass).invoke(receiver, text, extra)
        }.onFailure {
            MediaRuntime.log("Lyric Getter send failed", it)
            lastPayload = null
        }
    }

    fun clearLyric() {
        lastPayload = null
        val receiver = api ?: return
        runCatching { receiver.javaClass.getMethod("clearLyric").invoke(receiver) }
    }

    private fun String.isMusicSymbolOnly(): Boolean =
        all { char ->
            char.isWhitespace() ||
                char in setOf('♪', '♫', '♬', '♩', '♭', '♯', '♮') ||
                Character.UnicodeBlock.of(char) == Character.UnicodeBlock.MUSICAL_SYMBOLS
        }

    fun destroy() = clearLyric()
}
