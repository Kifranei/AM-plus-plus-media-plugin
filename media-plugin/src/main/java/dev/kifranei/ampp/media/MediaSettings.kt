package dev.kifranei.ampp.media

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

internal data class MediaSettings(
    val lyricon: Boolean = false,
    val iosControls: Boolean = false,
    val lyricsSharing: Boolean = false,
    val qualityGlass: Boolean = false,
) {
    fun encode() = JSONObject().apply {
        put("lyricon", lyricon); put("ios_controls", iosControls)
        put("lyrics_sharing", lyricsSharing); put("quality_glass", qualityGlass)
    }.toString()
    companion object {
        fun decode(text: String): MediaSettings {
            val json = JSONObject(text)
            return MediaSettings(json.opt("lyricon") as? Boolean ?: false,
                json.opt("ios_controls") as? Boolean ?: false, json.opt("lyrics_sharing") as? Boolean ?: false,
                json.opt("quality_glass") as? Boolean ?: false)
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
