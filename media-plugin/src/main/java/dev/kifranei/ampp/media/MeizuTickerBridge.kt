package dev.kifranei.ampp.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Flyme / Meizu status-bar ticker lyrics, adapted from Halcyon's [TickerBridge].
 *
 * Choice: post a **separate** ongoing notification (Halcyon's standalone path), not Apple Music's
 * own media notification. Halcyon prefers hiding the standalone notification and writing ticker
 * flags onto its app-owned media card (`PlaybackTickerState` + `NoArtworkMediaNotificationProvider`),
 * but this LSPosed plugin does not own AM's MediaStyle notification without fragile host hooks.
 * The standalone notification with `FLAG_ALWAYS_SHOW_TICKER` / `FLAG_ONLY_UPDATE_TICKER` is the
 * same Flyme surface Halcyon uses when not in "hide notification" mode, and still drives the
 * native status-bar lyric on Meizu. Flyme broadcasts are also sent for ROMs that listen to them.
 */
internal class MeizuTickerBridge(private val context: Context) : LiveLyricSink {
    companion object {
        private const val CHANNEL_ID = "ampp_flyme_ticker_lyrics_v1"
        private const val NOTIFICATION_ID = 0x414d544b // 'AMTK'
        private const val FLAG_ALWAYS_SHOW_TICKER_FALLBACK = 0x01000000
        private const val FLAG_ONLY_UPDATE_TICKER_FALLBACK = 0x02000000
        private const val ACTION_SEND_LYRIC = "com.meizu.flyme.ticker.ACTION_SEND"
        private const val ACTION_CLEAR_LYRIC = "com.meizu.flyme.ticker.ACTION_CLEAR"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        fun isFlymeDevice(): Boolean {
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val brand = Build.BRAND.orEmpty()
            val display = Build.DISPLAY.orEmpty()
            return manufacturer.contains("meizu", ignoreCase = true) ||
                brand.contains("meizu", ignoreCase = true) ||
                display.contains("flyme", ignoreCase = true)
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    private var lastPayload: Pair<String, String?>? = null

    private val flagAlwaysShowTicker: Int by lazy { notificationFlag("FLAG_ALWAYS_SHOW_TICKER", FLAG_ALWAYS_SHOW_TICKER_FALLBACK) }
    private val flagOnlyUpdateTicker: Int by lazy { notificationFlag("FLAG_ONLY_UPDATE_TICKER", FLAG_ONLY_UPDATE_TICKER_FALLBACK) }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clearLyric()
    }

    override fun onLyric(song: LiveLyricSongMeta?, line: LiveLyricLine?, positionMs: Long, playing: Boolean) {
        if (!enabled) return
        if (!playing || line == null || line.text.isBlank()) {
            clearLyric()
            return
        }
        sendLyric(line.text, line.secondary)
    }

    fun sendLyric(text: String?, translation: String? = null) {
        if (!enabled) return
        try {
            if (text.isNullOrBlank()) {
                clearLyric()
                return
            }
            val cleanTranslation = translation?.takeIf { it.isNotBlank() }
            val payload = text to cleanTranslation
            if (payload == lastPayload) return
            lastPayload = payload

            val intent = Intent(ACTION_SEND_LYRIC).apply {
                putExtra("ticker_text", text)
                putExtra("lyric", text)
                putExtra("text", text)
                putExtra("content", text)
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
                putExtra("ticker_app_name", "Apple Music")
                putExtra("app_name", "Apple Music")
                if (cleanTranslation != null) putExtra("translation", cleanTranslation)
            }
            sendFlymeBroadcast(intent)
            postTickerNotification(text, cleanTranslation)
        } catch (error: Throwable) {
            MediaRuntime.log("meizu ticker send failed", error)
        }
    }

    fun clearLyric() {
        lastPayload = null
        try {
            val intent = Intent(ACTION_CLEAR_LYRIC).apply {
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
            }
            sendFlymeBroadcast(intent)
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    private fun sendFlymeBroadcast(intent: Intent) {
        runCatching { context.sendBroadcast(intent) }
        runCatching { context.sendBroadcast(Intent(intent).setPackage(SYSTEM_UI_PACKAGE)) }
    }

    @Suppress("DEPRECATION")
    private fun postTickerNotification(text: String, translation: String?) {
        ensureChannel()
        val flyme = isFlymeDevice() && flagAlwaysShowTicker > 0 && flagOnlyUpdateTicker > 0
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(text)
            .setContentText(translation.orEmpty())
            .setTicker(if (flyme) text else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setDefaults(0)
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        if (flyme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                notification.extras.putBoolean("ticker_icon_switch", false)
                notification.extras.putString("ticker_text", text)
                notification.extras.putString("lyric", text)
                translation?.let { notification.extras.putString("ticker_translation", it) }
            }
            notification.flags = notification.flags or flagAlwaysShowTicker or flagOnlyUpdateTicker
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flyme 状态栏歌词",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "用于向 Flyme 状态栏推送歌词"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun notificationFlag(name: String, fallback: Int): Int = try {
        val field = Notification::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.getInt(null)
    } catch (_: Throwable) {
        fallback
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        clearLyric()
    }
}
