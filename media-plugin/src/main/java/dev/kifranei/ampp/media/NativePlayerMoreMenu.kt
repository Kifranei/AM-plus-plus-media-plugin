package dev.kifranei.ampp.media

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal data class NativePlayerMenuEntry(
    val action: PlayerMenuAction,
    val title: String,
    val subtitle: String?,
    val icon: Drawable?,
    val enabled: Boolean,
    val checked: Boolean,
    val perform: (View) -> Unit,
) {
    val signature get() = listOf(action.key, title, subtitle, enabled, checked)
}

/** Read the native data source and dispatch with its original adapter index and model. */
internal class NativePlayerMoreMenu(private val activity: Activity, private val owner: Any, private val list: ViewGroup,
    private val downloads: ContentDownloadsIntegration? = null, private val dismiss: () -> Unit = {}) {
    private val adapter = checkNotNull(PluginProfiles.field("more-fragment-adapter").get(owner))
    private val source = checkNotNull(PluginProfiles.field("more-adapter-source").get(adapter))
    private val dispatcher = checkNotNull(PluginProfiles.field("more-fragment-actions").get(owner))
    private val commands = PluginProfiles.field("more-source-commands")
    private val footer = PluginProfiles.field("more-source-footer")
    private val sourceItem = PluginProfiles.field("more-source-item")
    private val getCount = PluginProfiles.method("more-source-count")
    private val getItem = PluginProfiles.method("more-source-at")
    private val makeItem = PluginProfiles.method("more-source-make-item")
    private val click = PluginProfiles.method("more-action-click")
    private val title = PluginProfiles.method("more-item-title")
    private val subtitle = PluginProfiles.method("more-item-subtitle")
    private val icon = PluginProfiles.method("more-item-icon")
    private val enabled = PluginProfiles.method("more-item-enabled")
    private val checked = PluginProfiles.method("more-item-checked")
    private val headerType = title.declaringClass
    private var navigation: List<NativePlayerMenuEntry> = emptyList()
    private val navigationText = mutableMapOf<String, TextView>()

    fun entries(): List<NativePlayerMenuEntry> {
        val main = commands.get(source) as List<*>
        val pinned = footer.get(source) as? List<*> ?: emptyList<Any>()
        val offset = (getCount.invoke(source) as Int) - main.size
        check(offset in 0..1)
        val result = (main + pinned).mapIndexedNotNull { index, command ->
            command ?: return@mapIndexedNotNull null
            val model = if (index < main.size) getItem.invoke(source, index + offset)
                else makeItem.invoke(source, activity, command, sourceItem.get(source))
            if (model == null || !headerType.isInstance(model)) return@mapIndexedNotNull null
            val key = (command as? Enum<*>)?.name ?: "${command.javaClass.name}:$index"
            val identity = PlayerMenuAction(key, index + offset)
            NativePlayerMenuEntry(identity, title.invoke(model) as? String ?: "", subtitle.invoke(model) as? String,
                icon.invoke(model) as? Drawable, enabled.invoke(model) as Boolean, checked.invoke(model) as Boolean) { view ->
                // Resolve again so a changed native list cannot execute a stale index.
                val current = entries().firstOrNull { it.action.key == key } ?: return@NativePlayerMenuEntry
                if (current.enabled) {
                    val currentMain = commands.get(source) as List<*>
                    val currentOffset = (getCount.invoke(source) as Int) - currentMain.size
                    val position = current.action.index
                    val item = if (position - currentOffset < currentMain.size) getItem.invoke(source, position)
                        else makeItem.invoke(source, activity,
                            (footer.get(source) as List<*>)[position - currentOffset - currentMain.size], sourceItem.get(source))
                    click.invoke(dispatcher, position, view, item)
                }
            }
        }
        val download = downloads?.let { integration ->
            val item = sourceItem.get(source)
            val id = item?.let { PluginProfiles.method("content-download-item-id").invoke(it) as? String }?.toLongOrNull()
            val name = item?.let { PluginProfiles.method("content-download-item-title").invoke(it) as? String }
            if (id == null || name.isNullOrBlank()) emptyList() else listOf(
                NativePlayerMenuEntry(PlayerMenuAction("DOWNLOAD_TTML", -1), ContentExportKind.TTML_LYRICS.label(activity.resources.configuration.locales[0].language),
                    null, activity.getDrawable(android.R.drawable.stat_sys_download), true, false) {
                    dismiss()
                    integration.downloadTtml(activity, id, name)
                })
        } ?: emptyList()
        return result + navigation.map { entry ->
            val text = checkNotNull(navigationText[entry.action.key])
            entry.copy(subtitle = text.text.toString(), enabled = text.isEnabled)
        } + download
    }

    fun prepareNavigation(hiddenParent: ViewGroup) {
        val main = commands.get(source) as List<*>
        if ((getCount.invoke(source) as Int) == main.size) return
        val type = PluginProfiles.method("more-adapter-item-type").invoke(adapter, 0)
        val holder = PluginProfiles.method("more-adapter-create-holder").invoke(adapter, list, type)
        val view = PluginProfiles.field("more-holder-view").get(holder) as View
        hiddenParent.addView(view, ViewGroup.LayoutParams(0, 0))
        PluginProfiles.method("more-adapter-bind-holder").invoke(adapter, holder, 0, emptyList<Any>())
        // DataBinding clears XML binding tags. Read its verified view fields instead.
        val binding = PluginProfiles.field("more-holder-binding").get(holder)
        navigation = listOf("OPEN_ALBUM" to "more-header-album", "OPEN_ARTIST" to "more-header-artist").mapNotNull { (key, field) ->
            val text = (PluginProfiles.field(field).get(binding) as TextView)
                .takeIf { it.text.isNotBlank() && it.hasOnClickListeners() } ?: return@mapNotNull null
            val label = activity.getString(activity.resources.getIdentifier(
                if (key == "OPEN_ALBUM") "player_go_to_album" else "player_go_to_artist", "string", activity.packageName).takeIf { it != 0 }
                ?: android.R.string.untitled)
            navigationText[key] = text
            NativePlayerMenuEntry(PlayerMenuAction(key, 0), label, text.text.toString(), null, text.isEnabled, false) { text.performClick() }
        }
    }
}
