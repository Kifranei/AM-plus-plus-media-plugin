package dev.kifranei.ampp.media

import android.os.SystemClock

/**
 * SuperLyric API bridge adapted from Halcyon's SuperLyricBridge.
 * Reflection-only against com.hchen.superlyricapi so the plugin stays fail-open without that jar.
 */
internal class SuperLyricBridge : LiveLyricSink {
    @Volatile private var enabled = false
    private var registered = false
    private var lastKey: String? = null
    private var lastSongId: String? = null
    private var consecutiveFailures = 0
    private var retryAfterElapsedMs = 0L

    private val helperClass: Class<*>? = runCatching {
        Class.forName("com.hchen.superlyricapi.SuperLyricHelper")
    }.getOrNull()

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        lastKey = null
        lastSongId = null
        resetRetry()
        if (enabled) register() else {
            sendStop()
            unregister()
        }
    }

    fun isAvailable(): Boolean = helperClass != null

    override fun onLyric(song: LiveLyricSongMeta?, line: LiveLyricLine?, positionMs: Long, playing: Boolean) {
        if (!enabled) return
        if (song != null && song.id != lastSongId) {
            lastSongId = song.id
            lastKey = null
            register()
        }
        if (!playing || line == null) {
            sendStop()
            return
        }
        sendLyric(song, line, positionMs)
    }

    private fun sendLyric(song: LiveLyricSongMeta?, line: LiveLyricLine, positionMs: Long) {
        val key = "${song?.id}:${line.beginMs}:${line.endMs}:${line.text}:${line.secondary.orEmpty()}"
        if (key == lastKey) return
        if (!register()) return
        val helper = helperClass ?: return
        runCatching {
            val data = buildData(song, line, positionMs) ?: return@runCatching
            helper.getMethod("sendLyric", data.javaClass).invoke(null, data)
            lastKey = key
            resetRetry()
        }.onFailure { recordFailure("send", it) }
    }

    private fun buildData(song: LiveLyricSongMeta?, line: LiveLyricLine, positionMs: Long): Any? {
        val dataClass = Class.forName("com.hchen.superlyricapi.SuperLyricData")
        val data = dataClass.getDeclaredConstructor().newInstance()
        fun call(name: String, type: Class<*>, value: Any?) {
            dataClass.getMethod(name, type).invoke(data, value)
        }
        val longType = java.lang.Long.TYPE
        call("setTitle", String::class.java, song?.title)
        call("setArtist", String::class.java, song?.artist)
        call("setDuration", longType, song?.durationMs ?: 0L)
        call("setPosition", longType, positionMs.coerceAtLeast(0L))
        val lineClass = Class.forName("com.hchen.superlyricapi.SuperLyricLine")
        val wordClass = Class.forName("com.hchen.superlyricapi.SuperLyricWord")
        val words: Any? = if (line.words.isEmpty()) null else {
            val array = java.lang.reflect.Array.newInstance(wordClass, line.words.size)
            line.words.forEachIndexed { index, word ->
                val w = wordClass.getConstructor(String::class.java, longType, longType)
                    .newInstance(word.text, word.beginMs, word.endMs)
                java.lang.reflect.Array.set(array, index, w)
            }
            array
        }
        val lyricLine = try {
            lineClass.getConstructor(String::class.java, words?.javaClass ?: arrayOf<Any>().javaClass, longType, longType)
                .newInstance(line.text, words, line.beginMs, line.endMs)
        } catch (_: Throwable) {
            lineClass.getConstructor(String::class.java, longType, longType)
                .newInstance(line.text, line.beginMs, line.endMs)
        }
        call("setLyric", lineClass, lyricLine)
        line.secondary?.takeIf { it.isNotBlank() }?.let { secondary ->
            val translation = lineClass.getConstructor(String::class.java, longType, longType)
                .newInstance(secondary, line.beginMs, line.endMs)
            runCatching { call("setTranslation", lineClass, translation) }
        }
        return data
    }

    private fun sendStop() {
        val helper = helperClass
        if (!registered || helper == null) {
            lastKey = null
            return
        }
        runCatching {
            val dataClass = Class.forName("com.hchen.superlyricapi.SuperLyricData")
            helper.getMethod("sendStop", dataClass).invoke(null, dataClass.getDeclaredConstructor().newInstance())
        }
        lastKey = null
    }

    private fun register(): Boolean {
        val helper = helperClass ?: return false
        if (!enabled) return false
        if (registered) return true
        if (SystemClock.elapsedRealtime() < retryAfterElapsedMs) return false
        val available = runCatching {
            helper.getMethod("isAvailable").invoke(null) as? Boolean
        }.getOrElse {
            recordFailure("availability", it)
            return false
        }
        if (available != true) {
            recordFailure("attach")
            return false
        }
        return runCatching {
            helper.getMethod("registerPublisher").invoke(null)
            runCatching {
                helper.getMethod("setSystemPlayStateListenerEnabled", Boolean::class.javaPrimitiveType).invoke(null, true)
            }
            registered = true
            true
        }.getOrElse {
            recordFailure("register", it)
            false
        }
    }

    private fun unregister() {
        val helper = helperClass ?: return
        if (!registered) return
        runCatching { helper.getMethod("unregisterPublisher").invoke(null) }
        registered = false
    }

    private fun recordFailure(op: String, cause: Throwable? = null) {
        registered = false
        consecutiveFailures++
        val exp = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(4)
        val delay = (30_000L * (1L shl exp)).coerceAtMost(5 * 60_000L)
        retryAfterElapsedMs = SystemClock.elapsedRealtime() + delay
        MediaRuntime.log("SuperLyric $op unavailable; retry in ${delay / 1000}s", cause)
    }

    private fun resetRetry() {
        consecutiveFailures = 0
        retryAfterElapsedMs = 0L
    }

    fun destroy() {
        sendStop()
        unregister()
        resetRetry()
    }
}
