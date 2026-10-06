package dev.kifranei.ampp.media

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

internal data class MediaSettings(
    val lyricon: Boolean = false,
    val iosControls: Boolean = false,
    val sharing: Boolean = false,
    val playerGlass: Boolean = false,
    val playerHandle: Boolean = false,
    val systemCorners: Boolean = false,
    val volumeBar: Boolean = false,
    val contentDownloads: Boolean = false,
) {
    fun encode() = JSONObject().apply {
        put("lyricon", lyricon); put("ios_controls", iosControls)
        put("sharing", sharing); put("player_glass", playerGlass)
        put("player_handle", playerHandle); put("system_corners", systemCorners)
        put("volume_bar", volumeBar)
        put("content_downloads", contentDownloads)
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
                json.opt("content_downloads") as? Boolean ?: false)
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
