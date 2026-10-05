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
        fun install(name: String, enabled: Boolean, claim: String, action: () -> TargetCapabilityInstall) {
            if (!enabled) { status += "$name：已关闭"; return }
            runCatching {
                val resource = runtime.claimResource(claim, true)
                val result = try { action() } catch (error: Throwable) { resource.close(); throw error }
                if (result is TargetCapabilityInstall.Unsupported) resource.close()
                status += "$name：${result.message}"
                runtime.log("$name: ${result.message}", null)
            }.onFailure {
                status += "$name：${it.message ?: "启动失败"}"
                runtime.log("$name startup failed", it)
            }
        }
        install("词幕集成", configured.lyricon, "shared:applemusic-lyricon-provider") {
            LyriconIntegration(app, loader, build, CurrentSongCache()).install()
        }
        install("使用 iOS 媒体控制按钮", configured.iosControls, "shared:applemusic-player-output-button") {
            IosMediaControls(app, loader, build).install()
        }
        install("解除歌词分享限制", configured.lyricsSharing, "shared:applemusic-lyric-share-image") {
            LyricsSharingFeature().install(LyricsSharingIntegration(app, loader, build))
        }
        install("液态玻璃音质弹窗", configured.qualityGlass, "shared:applemusic-quality-disclosure") {
            AudioQualityGlassFeature().install(AudioQualityIntegration(app, build))
        }
    }
    override fun onStop() { runtime.log("Media plugin stopped; registered callbacks and provider released", null) }

    override fun createSettings(context: Context): PluginSettingsSession {
        val closed = AtomicBoolean()
        val main = Handler(Looper.getMainLooper())
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        var draft = store.read()
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        layout.addView(TextView(context).apply {
            text = "AM 媒体增强\n各功能独立启用，保存后完全停止并重开 Apple Music。"
            textSize = 16f
        })
        val switches = ArrayList<Switch>()
        val saveButton = Button(context).apply { text = "保存设置" }
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
                    result.onSuccess { Toast.makeText(context, "已保存，重开 Apple Music 后生效", Toast.LENGTH_SHORT).show() }
                        .onFailure {
                            Toast.makeText(context, "保存失败，点击保存设置重试", Toast.LENGTH_SHORT).show()
                            runtime.log("Plugin settings write failed", it)
                        }
                }
            }
        }
        fun option(title: String, checked: Boolean, change: (MediaSettings, Boolean) -> MediaSettings) {
            val control = Switch(context).apply { text = title; isChecked = checked; minHeight = dp(56) }
            switches += control
            control.setOnCheckedChangeListener { _, enabled ->
                draft = change(draft, enabled)
                persist()
            }
            layout.addView(control)
        }
        option("词幕集成", draft.lyricon) { state, enabled -> state.copy(lyricon = enabled) }
        option("使用 iOS 媒体控制按钮", draft.iosControls) { state, enabled -> state.copy(iosControls = enabled) }
        option("解除歌词分享限制", draft.lyricsSharing) { state, enabled -> state.copy(lyricsSharing = enabled) }
        option("液态玻璃音质弹窗", draft.qualityGlass) { state, enabled -> state.copy(qualityGlass = enabled) }
        saveButton.setOnClickListener { persist() }
        layout.addView(saveButton)
        layout.addView(TextView(context).apply { text = "本次运行\n" + status.joinToString("\n"); textSize = 14f; setPadding(0, dp(16), 0, 0) })
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
