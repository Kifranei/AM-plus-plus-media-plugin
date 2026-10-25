package dev.kifranei.ampp.media

import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginUnsupportedException
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class TargetBuild(val packageName: String, val versionName: String, val versionCode: Long) {
    val displayName get() = "$versionName ($versionCode)"
}
/** One shipped host profile: the exact host build it describes and the asset holding its contracts. */
internal data class ProfileEntry(val packageName: String, val versionName: String, val versionCode: Long, val asset: String) {
    val displayName get() = "$versionName / $versionCode"
}

/** assets/profiles.json lists every shipped profile; adding a host version is one JSON file plus one entry. */
internal object ProfileIndex {
    const val ASSET = "profiles.json"
    fun parse(text: String): List<ProfileEntry> {
        val root = JSONObject(text)
        val packageName = root.getString("packageName")
        val array = root.getJSONArray("profiles")
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ProfileEntry(packageName, item.getString("versionName"), item.getLong("versionCode"), item.getString("asset"))
        }
        check(entries.map { it.versionCode to it.versionName }.toSet().size == entries.size) { "Duplicate profile version" }
        return entries
    }
    /** Exact (package, versionName, versionCode) match only; no range or nearest-version fallback. */
    fun select(entries: List<ProfileEntry>, packageName: String, versionName: String, versionCode: Long): ProfileEntry? =
        entries.firstOrNull { it.packageName == packageName && it.versionName == versionName && it.versionCode == versionCode }
    fun unsupportedMessage(entries: List<ProfileEntry>) =
        "仅适配 Apple Music " + entries.joinToString("、") { it.displayName }
}

internal object PluginProfiles {
    lateinit var document: JSONObject
    private lateinit var loader: ClassLoader
    private var selected: ProfileEntry? = null
    /** Choose the profile for the host build; unsupported builds are refused before any hook is installed. */
    fun load(context: PluginContext) {
        val entries = context.openAsset(ProfileIndex.ASSET).bufferedReader().use { ProfileIndex.parse(it.readText()) }
        val entry = ProfileIndex.select(entries, context.hostPackageName, context.hostVersionName, context.hostVersionCode)
            ?: throw PluginUnsupportedException(ProfileIndex.unsupportedMessage(entries) +
                "；当前为 ${context.hostVersionName} / ${context.hostVersionCode}")
        val loaded = context.openAsset(entry.asset).bufferedReader().use { JSONObject(it.readText()) }
        check(loaded.getString("packageName") == entry.packageName && loaded.getString("versionName") == entry.versionName &&
            loaded.getLong("versionCode") == entry.versionCode) { "Profile ${entry.asset} does not describe ${entry.displayName}" }
        loader = context.hostClassLoader
        document = loaded
        selected = entry
    }
    fun find(packageName: String, versionName: String, versionCode: Long): PluginProfiles? =
        takeIf { selected?.let { it.packageName == packageName && it.versionName == versionName && it.versionCode == versionCode } == true }
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
