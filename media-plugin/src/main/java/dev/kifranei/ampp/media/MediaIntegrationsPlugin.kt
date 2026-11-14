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
/** Shown in diagnostics; kept in sync with media-plugin/plugin.json by PluginProfileSelectionTest. */
internal const val PLUGIN_BUILD = "1.3.6/18"

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
        val hostBuild = "${context.hostVersionName}/${context.hostVersionCode}"
        runCatching { PluginProfiles.load(context) }.onFailure {
            runCatching { MediaDiag.start(context.dataDirectory, hostBuild, "none", PLUGIN_BUILD) }
            MediaDiag.event("startup", "no usable profile; plugin refuses this host", it, key = null)
            throw it
        }
        MediaDiag.start(runCatching { context.dataDirectory }.getOrNull(), hostBuild, PluginProfiles.selectedAsset ?: "?", PLUGIN_BUILD)
        store = MediaSettingsStore(context.dataDirectory)
        configured = store.read()
        PopupGlassStyle.current = configured.popupGlassStyle
        context.onClose { writer.shutdown() }
        // Resolve contracts before any capability registers; all host knowledge stays in this ZIP.
        // A profile without these contracts only loses Lyricon (its install reports Unsupported); never the whole plugin.
        val needsLyricContracts = configured.lyricon || configured.meizuTicker ||
            configured.mediaNotificationLyrics || configured.superLyric || configured.lyricGetter
        if (needsLyricContracts) runCatching {
            listOf("lyrics-install-method", "player-metadata-publish-method", "metadata-to-playback-item-method")
                .forEach(PluginProfiles::method)
        }.onFailure { context.log("Lyric contracts unavailable for ${context.hostVersionName} (${context.hostVersionCode})", it) }
        context.log("SDK v1 media plugin prepared for ${context.hostVersionName} (${context.hostVersionCode})", null)
    }
    override fun onStart() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = runtime.application
        val loader = runtime.hostClassLoader
        val build = TargetBuild(runtime.hostPackageName, runtime.hostVersionName, runtime.hostVersionCode)
        val text = MediaSettingsText.forLanguage(app.resources.configuration.locales[0].language)
        // Contract self-check off the main thread: one log line lists every profile symbol this host lacks.
        Thread({
            val broken = PluginProfiles.selfCheck()
            MediaDiag.event("self-check", if (broken.isEmpty()) "all profile contracts resolve"
                else "${broken.size} unresolved contract(s): ${broken.joinToString(", ")}", key = null)
        }, "AM media plugin self-check").apply { isDaemon = true }.start()
        fun install(name: String, enabled: Boolean, claim: String, action: () -> TargetCapabilityInstall) {
            if (!enabled) { status += "$name: ${text.disabled}"; return }
            MediaDiag.capability = name
            try {
                runCatching {
                    val resource = runtime.claimResource(claim, true)
                    val result = try { action() } catch (error: Throwable) { resource.close(); throw error }
                    if (result is TargetCapabilityInstall.Unsupported) resource.close()
                    status += "$name: ${result.message}"
                    runtime.log("$name: ${result.message}", null)
                    MediaDiag.event("capability", "$name -> ${if (result is TargetCapabilityInstall.Active) "ACTIVE" else "UNSUPPORTED"}: ${result.message}", key = null)
                }.onFailure {
                    // Fail open: one capability's install failure never stops the others or the host.
                    status += "$name: ${it.message ?: text.startupFailed}"
                    runtime.log("$name startup failed", it)
                    MediaDiag.event("capability", "$name -> FAILED (skipped, host unchanged)", it, key = null)
                }
            } finally { MediaDiag.capability = null }
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
        val systemLyricOptions = SystemLyricDeliveryIntegration.Options(
            meizuTicker = configured.meizuTicker,
            mediaNotificationLyrics = configured.mediaNotificationLyrics,
            superLyric = configured.superLyric,
            lyricGetter = configured.lyricGetter,
        )
        install(text.systemLyricDelivery, systemLyricOptions.anyEnabled, "shared:applemusic-system-lyric-delivery") {
            SystemLyricDeliveryIntegration(app, loader, build, systemLyricOptions).install()
        }
        install(text.iosControls, configured.iosControls, "shared:applemusic-player-output-button") {
            IosMediaControls(app, loader, build).install()
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
        // OPlus ROMs (ColorOS / realme UI / OxygenOS) ship a larger default DPI that breaks the volume bar.
        if (configured.volumeBar && OplusRomDetector.current) {
            status += "${text.volumeBar}: ${text.volumeBarOplusUnsupported}"
            runtime.log("${text.volumeBar}: force-disabled on OPlus ROM", null)
        } else install(text.volumeBar, configured.volumeBar, "shared:applemusic-player-volume") {
            PlayerVolumeIntegration(build).install()
        }
        install(text.playerAppearance, configured.playerHandle || configured.systemCorners, "shared:applemusic-player-page-chrome") {
            PlayerPageChromeIntegration(build, configured.playerHandle, configured.systemCorners).install()
        }
        install(text.autoTransition, configured.autoTransition, "shared:applemusic-auto-transition") {
            AutoTransitionIntegration(build, loader,
                enabled = { store.read().autoTransition },
                defaultDurationSec = { store.read().autoTransitionDuration },
                speedMatch = { store.read().autoTransitionSpeedMatch },
                animate = { store.read().autoTransitionAnimate },
            ).install()
        }
        runCatching {
            FileLogger.init(app)
        }
    }
    override fun onStop() { runtime.log("Media plugin stopped; registered callbacks and provider released", null) }

    override fun createSettings(context: Context): PluginSettingsSession {
        // v2 host chrome: title and scroll contract are provided by the host page.
        val settingsText = MediaSettingsText.forLanguage(context.resources.configuration.locales[0].language)
        runCatching {
            val bridge = Class.forName("dev.amenhancer.module.ui.PluginSettingsScrollBridge")
            bridge.getMethod("updateTitle", String::class.java).invoke(null, settingsText.name)
            bridge.getMethod("updateLargeTitle", String::class.java).invoke(null, settingsText.name)
            bridge.getMethod("updateSupportsScroll", Boolean::class.javaPrimitiveType).invoke(null, true)
        }
        val closed = AtomicBoolean()
        val text = MediaSettingsText.forLanguage(context.resources.configuration.locales[0].language)
        val initial = store.read()
        fun persist(next: MediaSettings) {
            writer.execute {
                val result = runCatching { store.write(next) }
                Handler(Looper.getMainLooper()).post {
                    if (closed.get()) return@post
                    result.onSuccess {
                        Toast.makeText(context, text.saved, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, text.saveFailed, Toast.LENGTH_SHORT).show()
                        runtime.log("Plugin settings write failed", it)
                    }
                }
            }
        }
        val view = PluginGlassHostView(context, bleedDp = 0).apply {
            content {
                MiuixSettingsPage(
                    text = text,
                    initial = initial,
                    hostVersionCode = runtime.hostVersionCode.toInt(),
                    oplusUnsupported = OplusRomDetector.current,
                    status = ArrayList(status),
                    onPersist = ::persist,
                )
            }
        }
        return object : PluginSettingsSession {
            override fun getView(): View = view
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
            }
        }
    }
}
