package dev.kifranei.ampp.media

import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginUnsupportedException
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class TargetBuild(val packageName: String, val versionName: String, val versionCode: Long) {
    val displayName get() = "$versionName ($versionCode)"
}
internal object PluginProfiles {
    lateinit var document: JSONObject
    private lateinit var loader: ClassLoader
    fun load(context: PluginContext) {
        if (context.hostPackageName != "com.apple.android.music" || context.hostVersionName != "7.0.0-beta" || context.hostVersionCode != 1606L)
            throw PluginUnsupportedException("仅适配 Apple Music 7.0.0-beta / 1606")
        loader = context.hostClassLoader
        document = context.openAsset("applemusic-1606.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    fun find(packageName: String, versionName: String, versionCode: Long): PluginProfiles? =
        takeIf { packageName == "com.apple.android.music" && versionName == "7.0.0-beta" && versionCode == 1606L }
    fun type(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "int" -> Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        else -> loader.loadClass(name)
    }
    fun method(key: String): Method {
        val contract = document.getJSONObject("indexed").getJSONObject("methodContracts").getJSONObject(key)
        val parameters = contract.getJSONArray("parameters")
        return type(contract.getString("owner")).getDeclaredMethod(contract.getString("name"),
            *(0 until parameters.length()).map { type(parameters.getString(it)) }.toTypedArray()).apply {
            check(returnType == type(contract.getString("returns")) && Modifier.isStatic(modifiers) == contract.getBoolean("static"))
            isAccessible = true
        }
    }
    fun field(key: String): java.lang.reflect.Field {
        val contract = document.getJSONObject("indexed").getJSONObject("fieldContracts").getJSONObject(key)
        return type(contract.getString("owner")).getDeclaredField(contract.getString("name")).apply {
            check(type == PluginProfiles.type(contract.getString("type")) && !Modifier.isStatic(modifiers))
            isAccessible = true
        }
    }
}
internal sealed interface TargetCapabilityInstall {
    val message: String
    data class Active(override val message: String) : TargetCapabilityInstall
    data class Unsupported(override val message: String) : TargetCapabilityInstall
}
internal fun interface LyriconTarget { fun install(): TargetCapabilityInstall }
internal fun interface PlayerAudioOutputTarget { fun install(): TargetCapabilityInstall }
internal fun interface AudioQualityDialogTarget { fun install(present: (AudioQualityDialogSurface) -> Boolean): TargetCapabilityInstall }
internal fun interface CardSharingTarget { fun install(export: (NativeShareImage, Boolean) -> Unit): TargetCapabilityInstall }

internal data class SongIdentity(val appleMusicId: Long)
internal data class CurrentSong(val item: Any, val details: SongIdentity)
internal class CurrentSongCache {
    @Volatile private var song: CurrentSong? = null
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun current() = song
    fun publish(item: Any, id: Long) {
        if (id <= 0L) return
        song = CurrentSong(item, SongIdentity(id))
        listeners.forEach { it() }
    }
    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        if (song != null) listener()
        return AutoCloseable { listeners -= listener }
    }
}
