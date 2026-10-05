package dev.kifranei.ampp.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Html
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.service.addConnectionListener

/** SDK v1 adapter for LyricProvider's native Apple Music song/position protocol. */
internal class LyriconIntegration(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
    private val currentSong: CurrentSongCache,
) : LyriconTarget {
    private var result: TargetCapabilityInstall? = null
    private var provider: LyriconProvider? = null
    private val main = Handler(Looper.getMainLooper())
    private var playback: PlaybackState? = null
    private var session: Any? = null
    @Volatile private var running = false
    private val state = LyriconSongState { song ->
        provider?.player?.setDisplayTranslation(song?.lyrics.orEmpty().any { !it.translation.isNullOrBlank() })
        provider?.player?.setDisplayRoma(false)
        provider?.player?.setSong(song)
        val lines = song?.lyrics.orEmpty()
        val mismatches = lines.count { line -> !line.words.isNullOrEmpty() &&
            line.words!!.joinToString("") { word -> word.text.orEmpty() } != line.text }
        MediaRuntime.log("lyricon: song=${song?.id} lines=${lines.size} " +
            "translated=${lines.count { !it.translation.isNullOrBlank() }} " +
            "wordLines=${lines.count { !it.words.isNullOrEmpty() }} " +
            "multiWordLines=${lines.count { (it.words?.size ?: 0) > 1 }} " +
            "spacingMismatch=$mismatches")
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running || playback?.state != PlaybackState.STATE_PLAYING || !screenOn()) return
            provider?.player?.setPosition(position())
            main.postDelayed(this, 50L)
        }
    }

    @Synchronized override fun install(): TargetCapabilityInstall {
        result?.let { return it }
        if (Build.VERSION.SDK_INT < 28) return TargetCapabilityInstall.Unsupported("Lyricon needs Android 9+")
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("lyricon")
            ?: return TargetCapabilityInstall.Unsupported("No verified Lyricon contract for ${build.displayName}")
        val scope = PluginScope()
        return try {
            val model = loader.loadClass(names.getString("viewModelClass"))
            val pointer = loader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr")
            val item = loader.loadClass(names.getString("itemClass"))
            val buildLyrics = model.getDeclaredMethod(names.getString("buildMethod"), pointer).apply { isAccessible = true }
            val loadLyrics = model.getDeclaredMethod(names.getString("loadMethod"), item).apply { isAccessible = true }
            val installLyrics = PluginProfiles.method("lyrics-install-method")
            val installedPointer = installLyrics.declaringClass.getDeclaredField(names.getString("installedPointerField"))
                .apply { check(type == pointer); isAccessible = true }
            val constructor = model.getConstructor(Application::class.java)
            val nativeType = loader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative")
            val unwrap = pointer.getMethod("get")
            val selectTranslation = nativeType.getMethod("setTranslation", String::class.java)
            val systemLanguage = model.getMethod("getCurrentSystemLyricsLanguage")
            val pronunciationLanguages = nativeType.getMethod("getPronunciationLanguages")
            val selectPronunciation = nativeType.getMethod("setPronunciation", String::class.java)
            val matchPronunciation = loader.loadClass(names.getString("localeUtilClass"))
                .getMethod("matchToSystemLyricsScript", pronunciationLanguages.returnType)
            val translationSelected = model.getMethod("getTranslationSelectedLiveResult")
            val pronunciationSelected = model.getMethod("getPronunciationSelectedLiveResult")
            val observerType = loader.loadClass(names.getString("observerClass"))
            val requestModel = constructor.newInstance(application)
            var requestedId: String? = null
            fun request() {
                val current = currentSong.current() ?: return
                val id = current.details.appleMusicId.toString()
                if (id != state.currentId || state.hasLyrics || requestedId == id || !item.isInstance(current.item)) return
                requestedId = id
                runCatching {
                    loadLyrics.invoke(requestModel, current.item)
                }.onFailure { requestedId = null; MediaRuntime.log("lyricon lyric request failed", it) }
            }
            fun hook(method: java.lang.reflect.Method, callback: PluginMethodHook) {
                check(MediaRuntime.observeMethod(method, callback, scope))
            }
            val publish = PluginProfiles.method("player-metadata-publish-method")
            val converter = PluginProfiles.method("metadata-to-playback-item-method")
            val getId = item.getMethod("getId")
            hook(publish, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val playbackItem = converter.invoke(null, param.args.firstOrNull()) ?: return@runCatching
                        val id = (getId.invoke(playbackItem) as? String)?.toLongOrNull() ?: return@runCatching
                        currentSong.publish(playbackItem, id)
                    }.onFailure { MediaRuntime.log("lyricon current song snapshot failed", it) }
                }
            })
            val parser = LyriconNativeSongParser { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            fun capture(pointer: Any?, installed: Boolean = false) {
                if (pointer == null) return
                runCatching {
                    val song = parser.parse(pointer) ?: return@runCatching
                    main.post { if (scope.isActive) state.lyrics(song, installed) }
                }.onFailure { MediaRuntime.log("lyricon native lyric snapshot failed", it) }
            }
            hook(buildLyrics, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // The dedicated request model does not run the UI fragment's F2 language
                    // setup. Select its translation language before snapshotting its lyrics.
                    if (param.throwable == null && param.thisObject === requestModel) runCatching {
                        val ptr = param.args.getOrNull(0) ?: return@runCatching
                        val language = systemLanguage.invoke(param.thisObject) as String
                        val native = unwrap.invoke(ptr) ?: return@runCatching
                        selectTranslation.invoke(native, language)
                        val pronunciation = matchPronunciation.invoke(null, pronunciationLanguages.invoke(native)) as? String
                        if (pronunciation != null) selectPronunciation.invoke(native, pronunciation)
                    }.onFailure { MediaRuntime.log("lyricon translation selection failed", it) }
                    if (param.throwable == null) capture(param.args.getOrNull(0))
                }
            })
            // This also receives the final manual/automatic replacement supplied by AM++.
            hook(installLyrics, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) runCatching {
                        capture(installedPointer.get(param.thisObject), installed = true)
                    }.onFailure { MediaRuntime.log("lyricon installed lyric snapshot failed", it) }
                }
            })
            hook(MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val metadata = param.args.getOrNull(0) as? MediaMetadata
                    val owner = param.thisObject
                    main.post {
                        if (!scope.isActive) return@post
                        session = owner
                        val id = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
                        val song = id?.let { Song(id = it, name = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)) }
                        if (state.metadata(song)) {
                            requestedId = null
                            if (song == null) { playback = null; main.removeCallbacks(tick); provider?.player?.setPlaybackState(false) }
                        }
                        request()
                    }
                }
            })
            hook(MediaSession::class.java.getDeclaredMethod("setPlaybackState", PlaybackState::class.java), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val value = param.args.getOrNull(0) as? PlaybackState
                    val owner = param.thisObject
                    main.post {
                        if (!scope.isActive || (session != null && session !== owner)) return@post
                        playback = value
                        provider?.player?.setPlaybackState(value?.state == PlaybackState.STATE_PLAYING)
                        provider?.player?.seekTo(position())
                        main.removeCallbacks(tick)
                        if (value?.state == PlaybackState.STATE_PLAYING && screenOn()) main.post(tick)
                    }
                }
            })
            provider = LyriconFactory.createProvider(application,
                providerPackageName = MediaRuntime.modulePackageName, playerPackageName = application.packageName)
            scope.onClose {
                running = false
                main.post { provider?.destroy(); provider = null; playback = null; main.removeCallbacksAndMessages(null) }
            }
            var selection = LyriconAuxiliarySelection()
            fun updateSelection(next: LyriconAuxiliarySelection) {
                selection = next
                main.post {
                    if (!scope.isActive) return@post
                    state.auxiliary(next)
                    MediaRuntime.log("lyricon: auxiliary translation=${next.translation} pronunciation=${next.pronunciation}")
                }
            }
            val removeTranslation = observeLyriconSelection(requestModel, translationSelected, observerType) {
                updateSelection(selection.copy(translation = it))
            }
            scope.onClose { main.post { removeTranslation() } }
            val removePronunciation = observeLyriconSelection(requestModel, pronunciationSelected, observerType) {
                updateSelection(selection.copy(pronunciation = it))
            }
            scope.onClose { main.post { removePronunciation() } }
            provider?.service?.addConnectionListener {
                onConnected { MediaRuntime.log("lyricon: connected to system central service") }
                onReconnected { MediaRuntime.log("lyricon: reconnected to system central service") }
                onConnectTimeout { MediaRuntime.log("lyricon: central service unavailable; cached song retained") }
            }
            val screen = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    main.removeCallbacks(tick)
                    if (screenOn() && playback?.state == PlaybackState.STATE_PLAYING) main.post(tick)
                }
            }
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
            else application.registerReceiver(screen, filter)
            scope.onClose { application.unregisterReceiver(screen) }
            val subscription = currentSong.addListener { main.post { if (scope.isActive) request() } }
            scope.onClose(subscription::close)
            scope.activate()
            running = scope.isActive
            main.post {
                provider?.player?.setDisplayTranslation(false)
                provider?.player?.setDisplayRoma(false)
                provider?.register()
            }
            TargetCapabilityInstall.Active("词幕提供器已接入：原生/替换歌词、逐字、翻译、背景人声与系统播放进度")
                .also { result = it }
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private fun screenOn(): Boolean = (application.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    private fun position(): Long {
        val value = playback ?: return 0L
        val elapsed = if (value.state == PlaybackState.STATE_PLAYING && value.lastPositionUpdateTime > 0)
            (SystemClock.elapsedRealtime() - value.lastPositionUpdateTime).coerceAtLeast(0) else 0L
        return (value.position + elapsed * value.playbackSpeed).toLong().coerceAtLeast(0)
    }
}
