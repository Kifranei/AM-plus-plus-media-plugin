package dev.kifranei.ampp.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.*
import android.view.View
import dev.amenhancer.plugin.api.AmppPlugin
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginSettingsSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** SDK v1 entry; registration, resources and configuration belong to this ZIP. */
class MediaIntegrationsPlugin : AmppPlugin() {
    private lateinit var runtime: PluginContext
    private lateinit var store: MediaSettingsStore
    private lateinit var configured: MediaSettings
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AM media plugin settings").apply { isDaemon = true }
    }
    private val status = ArrayList<String>()
    override fun onLoad(context: PluginContext) {
        runtime = context
        MediaRuntime.context = context
        PluginProfiles.load(context)
        store = MediaSettingsStore(context.dataDirectory)
        configured = store.read()
        context.onClose { writer.shutdown() }
        // Resolve contracts before any capability registers; all host knowledge stays in this ZIP.
        if (configured.lyricon) listOf("lyrics-install-method", "player-metadata-publish-method", "metadata-to-playback-item-method")
            .forEach(PluginProfiles::method)
        context.log("SDK v1 media plugin prepared for ${context.hostVersionName} (${context.hostVersionCode})", null)
    }
    override fun onStart() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = runtime.application
        val loader = runtime.hostClassLoader
        val build = TargetBuild(runtime.hostPackageName, runtime.hostVersionName, runtime.hostVersionCode)
        val text = MediaSettingsText.forLanguage(app.resources.configuration.locales[0].language)
        fun install(name: String, enabled: Boolean, claim: String, action: () -> TargetCapabilityInstall) {
            if (!enabled) { status += "$name: ${text.disabled}"; return }
            runCatching {
                val resource = runtime.claimResource(claim, true)
                val result = try { action() } catch (error: Throwable) { resource.close(); throw error }
                if (result is TargetCapabilityInstall.Unsupported) resource.close()
                status += "$name: ${result.message}"
                runtime.log("$name: ${result.message}", null)
            }.onFailure {
                status += "$name: ${it.message ?: text.startupFailed}"
                runtime.log("$name startup failed", it)
            }
        }
        install("Player artwork recovery", true, "shared:applemusic-player-artwork-measurement") {
            PlayerArtworkRecovery(build).install()
        }
        install("Player background recovery", true, "shared:applemusic-player-background-recovery") {
            PlayerBackgroundRecovery(build).install()
        }
        var downloads: ContentDownloadsIntegration? = null
        install(text.contentDownloads, configured.contentDownloads, "shared:applemusic-content-downloads") {
            val integration = ContentDownloadsIntegration(app, loader, build)
            val result = integration.install()
            if (result is TargetCapabilityInstall.Active) {
                downloads = integration
                runtime.onClose { integration.close() }
            }
            result
        }
        install("Player TTML download", downloads != null, "shared:applemusic-player-ttml-download") {
            PlayerContentDownloadsIntegration(build, checkNotNull(downloads)).install()
        }
        install(text.lyricon, configured.lyricon, "shared:applemusic-lyricon-provider") {
            LyriconIntegration(app, loader, build, CurrentSongCache()).install()
        }
        install(text.iosControls, configured.iosControls, "shared:applemusic-player-output-button") {
            IosMediaControls(app, loader, build).install()
        }
        install("Tablet player controls", configured.iosControls, "shared:applemusic-tablet-player-actions") {
            TabletPlayerActionsIntegration(build).install()
        }
        install(text.sharing, configured.sharing, "shared:applemusic-lyric-share-image") {
            CardSharingFeature().install(CardSharingIntegration(app, loader, build))
        }
        install(text.qualityGlass, configured.playerGlass, "shared:applemusic-quality-disclosure") {
            AudioQualityGlassFeature().install(AudioQualityIntegration(app, build))
        }
        install(text.moreGlass, configured.playerGlass, "shared:applemusic-player-more-menu") {
            PlayerMoreMenuIntegration(build, downloads).install()
        }
        install(text.confirmationGlass, configured.playerGlass, "shared:applemusic-delete-confirmation") {
            PlayerDialogsIntegration(build, confirmation = true, sleep = false).install()
        }
        install(text.sleepGlass, configured.playerGlass, "shared:applemusic-sleep-timer") {
            PlayerDialogsIntegration(build, confirmation = false, sleep = true).install()
        }
        install(text.volumeBar, configured.volumeBar, "shared:applemusic-player-volume") {
            PlayerVolumeIntegration(build, compactTablet = configured.iosControls).install()
        }
        install(text.playerAppearance, configured.playerHandle || configured.systemCorners, "shared:applemusic-player-page-chrome") {
            PlayerPageChromeIntegration(build, configured.playerHandle, configured.systemCorners).install()
        }
    }
    override fun onStop() { runtime.log("Media plugin stopped; registered callbacks and provider released", null) }

    override fun createSettings(context: Context): PluginSettingsSession {
        val closed = AtomicBoolean()
        val main = Handler(Looper.getMainLooper())
        val density = context.resources.displayMetrics.density
        val text = MediaSettingsText.forLanguage(context.resources.configuration.locales[0].language)
        fun dp(n: Int) = (n * density).toInt()
        var draft = store.read()
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        layout.addView(TextView(context).apply {
            this.text = "${text.name}\n${text.introduction}"
            textSize = 16f
        })
        val switches = ArrayList<Switch>()
        val saveButton = Button(context).apply { this.text = text.save }
        fun persist() {
            val next = draft
            switches.forEach { it.isEnabled = false }
            saveButton.isEnabled = false
            writer.execute {
                val result = runCatching { store.write(next) }
                main.post {
                    if (closed.get()) return@post
                    switches.forEach { it.isEnabled = true }
                    saveButton.isEnabled = true
                    result.onSuccess { Toast.makeText(context, text.saved, Toast.LENGTH_SHORT).show() }
                        .onFailure {
                            Toast.makeText(context, text.saveFailed, Toast.LENGTH_SHORT).show()
                            runtime.log("Plugin settings write failed", it)
                        }
                }
            }
        }
        fun option(title: String, checked: Boolean, description: String? = null, change: (MediaSettings, Boolean) -> MediaSettings) {
            val control = Switch(context).apply { this.text = title; isChecked = checked; minHeight = dp(56) }
            switches += control
            control.setOnCheckedChangeListener { _, enabled ->
                draft = change(draft, enabled)
                persist()
            }
            layout.addView(control)
            if (description != null) layout.addView(TextView(context).apply {
                this.text = description; textSize = 13f
                setTextColor(textColors.withAlpha(180))
                setPadding(0, 0, dp(40), dp(12))
            })
        }
        option(text.lyricon, draft.lyricon) { state, enabled -> state.copy(lyricon = enabled) }
        option(text.iosControls, draft.iosControls, text.iosControlsDescription) { state, enabled -> state.copy(iosControls = enabled) }
        option(text.sharing, draft.sharing, text.sharingDescription) { state, enabled -> state.copy(sharing = enabled) }
        option(text.playerGlass, draft.playerGlass, text.playerGlassDescription) { state, enabled -> state.copy(playerGlass = enabled) }
        option(text.playerHandle, draft.playerHandle) { state, enabled -> state.copy(playerHandle = enabled) }
        option(text.systemCorners, draft.systemCorners) { state, enabled -> state.copy(systemCorners = enabled) }
        option(text.volumeBar, draft.volumeBar) { state, enabled -> state.copy(volumeBar = enabled) }
        option(text.contentDownloads, draft.contentDownloads, text.contentDownloadsDescription) { state, enabled -> state.copy(contentDownloads = enabled) }
        saveButton.setOnClickListener { persist() }
        layout.addView(saveButton)
        layout.addView(TextView(context).apply { this.text = text.currentRun + "\n" + status.joinToString("\n"); textSize = 14f; setPadding(0, dp(16), 0, 0) })
        val page = ScrollView(context).apply { addView(layout) }
        return object : PluginSettingsSession {
            override fun getView(): View = page
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                switches.forEach { it.setOnCheckedChangeListener(null) }
                saveButton.setOnClickListener(null)
                main.removeCallbacksAndMessages(null)
            }
        }
    }
}
