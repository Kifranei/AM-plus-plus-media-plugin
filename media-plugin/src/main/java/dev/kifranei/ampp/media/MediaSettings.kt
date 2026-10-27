package dev.kifranei.ampp.media

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Material of the liquid-glass popups (sleep timer, delete confirmation, audio quality); the More menu keeps its own look. */
internal enum class PopupGlassStyle(val key: String) {
    BOTTOM_BAR("bottom_bar"), MORE_MENU("more_menu"), NO_FROST("no_frost");
    companion object {
        val default = BOTTOM_BAR
        /** Applied once when the plugin loads (settings take effect after reopening Apple Music). */
        @Volatile var current: PopupGlassStyle = default
        fun fromKey(key: String?) = values().firstOrNull { it.key == key } ?: default
    }
}

internal data class MediaSettings(
    val lyricon: Boolean = false,
    val iosControls: Boolean = false,
    val sharing: Boolean = false,
    val playerGlass: Boolean = false,
    val playerHandle: Boolean = false,
    val systemCorners: Boolean = false,
    val volumeBar: Boolean = false,
    val contentDownloads: Boolean = false,
    val popupGlassStyle: PopupGlassStyle = PopupGlassStyle.default,
    val autoTransition: Boolean = false,
    val autoTransitionDuration: Int = 12,
    // 变速匹配（Beat Match）默认关闭：setPlaybackParameters 是对整个 ExoPlayer 生效的，
    // crossfade 期间当前歌也被变速，切换瞬间 AudioTrack 重建会导致"音乐被切断+纯杂音"（用户实测）。
    val autoTransitionSpeedMatch: Boolean = false,
    val autoTransitionAnimate: Boolean = true,
) {
    fun encode() = JSONObject().apply {
        put("lyricon", lyricon); put("ios_controls", iosControls)
        put("sharing", sharing); put("player_glass", playerGlass)
        put("player_handle", playerHandle); put("system_corners", systemCorners)
        put("volume_bar", volumeBar)
        put("content_downloads", contentDownloads)
        put("popup_glass_style", popupGlassStyle.key)
        put("auto_transition", autoTransition)
        put("auto_transition_duration", autoTransitionDuration)
        put("auto_transition_speed_match", autoTransitionSpeedMatch)
        put("auto_transition_animate", autoTransitionAnimate)
    }.toString()
    companion object {
        fun decode(text: String): MediaSettings {
            val json = JSONObject(text)
            val legacyChrome = json.opt("player_chrome") as? Boolean ?: false
            val legacyGlass = listOf("quality_glass", "more_glass", "confirmation_glass", "sleep_glass")
                .any { json.opt(it) == true }
            return MediaSettings(json.opt("lyricon") as? Boolean ?: false,
                json.opt("ios_controls") as? Boolean ?: false,
                json.opt("sharing") as? Boolean ?: (json.opt("lyrics_sharing") as? Boolean ?: false),
                json.opt("player_glass") as? Boolean ?: legacyGlass,
                json.opt("player_handle") as? Boolean ?: legacyChrome,
                json.opt("system_corners") as? Boolean ?: legacyChrome,
                json.opt("volume_bar") as? Boolean ?: false,
                json.opt("content_downloads") as? Boolean ?: false,
                PopupGlassStyle.fromKey(json.opt("popup_glass_style") as? String),
                json.opt("auto_transition") as? Boolean ?: false,
                (json.opt("auto_transition_duration") as? Int ?: 12).coerceIn(2, 20),
                json.opt("auto_transition_speed_match") as? Boolean ?: false,
                json.opt("auto_transition_animate") as? Boolean ?: true,
            )
        }
    }
}
internal class MediaSettingsStore(private val directory: File) {
    private val file get() = File(directory, "settings.json")
    fun read(): MediaSettings = if (file.isFile) MediaSettings.decode(file.readText(Charsets.UTF_8)) else MediaSettings()
    @Synchronized fun write(value: MediaSettings) {
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "settings.json.tmp")
        try {
            temporary.outputStream().use { output -> output.write(value.encode().toByteArray(Charsets.UTF_8)); output.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
}
