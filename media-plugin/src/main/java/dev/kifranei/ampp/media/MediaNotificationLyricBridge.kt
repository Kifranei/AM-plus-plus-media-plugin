package dev.kifranei.ampp.media

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Halcyon's "媒体通知歌词" (settings key bluetooth lyric): write the current lyric into the
 * media session title (and secondary into artist) so Bluetooth / car / system media centers
 * and the host media notification show lyrics.
 *
 * Adapted from Halcyon's MediaNotificationLyricPatchPolicy throttling + SessionPresentationPlayer
 * overlay. Here we re-publish a patched [MediaMetadata] on the live [MediaSession] Apple Music
 * already owns — fail-open, never throw into AM.
 */
internal class MediaNotificationLyricBridge : LiveLyricSink {
    companion object {
        const val MIN_PATCH_INTERVAL_MS = 750L
        private const val PATCH_MARKER = "ampp_media_notification_lyric"
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    @Volatile private var session: MediaSession? = null
    @Volatile private var baseMetadata: MediaMetadata? = null
    private var lastPayload: Pair<String?, String?>? = null
    private var lastPatchElapsedMs = Long.MIN_VALUE
    private var applyingPatch = false
    private var pending: Runnable? = null

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) restore() else lastPayload = null
    }

    fun attachSession(owner: MediaSession?, metadata: MediaMetadata?) {
        if (owner == null) return
        if (applyingPatch) return
        // Ignore our own re-entrant metadata writes (custom key stamped in applyPatch).
        if (metadata?.getString("ampp.lyric.marker") == "1") return
        session = owner
        if (metadata != null) baseMetadata = metadata
        if (enabled) lastPayload = null
    }

    override fun onLyric(song: LiveLyricSongMeta?, line: LiveLyricLine?, positionMs: Long, playing: Boolean) {
        if (!enabled) return
        if (!playing || line == null || line.text.isBlank()) {
            restore()
            return
        }
        patch(line.text, line.secondary)
    }

    private fun patch(text: String, secondary: String?) {
        val payload = text to secondary
        if (payload == lastPayload) return
        val now = SystemClock.elapsedRealtime()
        val wait = if (lastPatchElapsedMs == Long.MIN_VALUE) 0L
        else (MIN_PATCH_INTERVAL_MS - (now - lastPatchElapsedMs)).coerceAtLeast(0L)
        pending?.let(main::removeCallbacks)
        if (wait > 0L) {
            val task = Runnable {
                pending = null
                applyPatch(text, secondary)
            }
            pending = task
            main.postDelayed(task, wait)
            return
        }
        applyPatch(text, secondary)
    }

    private fun applyPatch(text: String, secondary: String?) {
        val owner = session ?: return
        val base = baseMetadata ?: return
        val payload = text to secondary
        if (payload == lastPayload) return
        try {
            val builder = MediaMetadata.Builder(base)
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, text)
            if (!secondary.isNullOrBlank()) {
                builder.putString(MediaMetadata.METADATA_KEY_ARTIST, secondary)
            }
            // Marker so attachSession ignores the echo.
            val extras = android.os.Bundle(base.description.extras ?: android.os.Bundle())
            extras.putBoolean(PATCH_MARKER, true)
            // MediaMetadata.Builder has no putBundle for description extras on all APIs; stash via
            // a custom string key that receivers ignore.
            builder.putString("ampp.lyric.marker", "1")
            val patched = builder.build()
            applyingPatch = true
            owner.setMetadata(patched)
            lastPayload = payload
            lastPatchElapsedMs = SystemClock.elapsedRealtime()
        } catch (error: Throwable) {
            MediaRuntime.log("media notification lyric patch failed", error)
        } finally {
            applyingPatch = false
        }
    }

    fun restore() {
        pending?.let(main::removeCallbacks)
        pending = null
        if (lastPayload == null) return
        lastPayload = null
        val owner = session ?: return
        val base = baseMetadata ?: return
        try {
            applyingPatch = true
            owner.setMetadata(base)
        } catch (error: Throwable) {
            MediaRuntime.log("media notification lyric restore failed", error)
        } finally {
            applyingPatch = false
        }
    }

    fun destroy() {
        pending?.let(main::removeCallbacks)
        restore()
        session = null
        baseMetadata = null
    }
}
