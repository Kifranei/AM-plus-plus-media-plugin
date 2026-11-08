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

/**
 * Captures Apple Music lyrics / position with the same contracts as [LyriconIntegration]
 * (independent of whether Lyricon is installed) and fans out to Meizu ticker,
 * media-notification lyrics, SuperLyric and Lyric Getter — Halcyon's system lyric sinks.
 */
internal class SystemLyricDeliveryIntegration(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
    private val options: Options,
) {
    data class Options(
        val meizuTicker: Boolean,
        val mediaNotificationLyrics: Boolean,
        val superLyric: Boolean,
        val lyricGetter: Boolean,
    ) {
        val anyEnabled get() = meizuTicker || mediaNotificationLyrics || superLyric || lyricGetter
    }

    private var result: TargetCapabilityInstall? = null
    private val main = Handler(Looper.getMainLooper())
    private val currentSong = CurrentSongCache()
    private var playback: PlaybackState? = null
    private var session: MediaSession? = null
    private var song: Song? = null
    private var songMeta: LiveLyricSongMeta? = null
    private var lastLineKey: String? = null
    @Volatile private var running = false

    private val ticker = MeizuTickerBridge(application)
    private val mediaNotification = MediaNotificationLyricBridge()
    private val superLyric = SuperLyricBridge()
    private val lyricGetter = LyricGetterBridge(application)

    private val tick = object : Runnable {
        override fun run() {
            if (!running || playback?.state != PlaybackState.STATE_PLAYING || !screenOn()) return
            publish(force = false)
            main.postDelayed(this, 50L)
        }
    }

    @Synchronized fun install(): TargetCapabilityInstall {
        result?.let { return it }
        if (!options.anyEnabled) return TargetCapabilityInstall.Unsupported("No system lyric sinks enabled")
        if (Build.VERSION.SDK_INT < 28) return TargetCapabilityInstall.Unsupported("System lyric delivery needs Android 9+")
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
            val requestModel = constructor.newInstance(application)
            var requestedId: String? = null
            val parser = LyriconNativeSongParser { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            val songs = object : LinkedHashMap<String, Song>(16, .75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean = size > 16
            }
            val installedIds = HashSet<String>()

            fun request() {
                val current = currentSong.current() ?: return
                val id = current.details.appleMusicId.toString()
                if (songs[id]?.lyrics.isNullOrEmpty().not() || requestedId == id || !item.isInstance(current.item)) return
                requestedId = id
                runCatching { loadLyrics.invoke(requestModel, current.item) }
                    .onFailure { requestedId = null; MediaRuntime.log("system lyric request failed", it) }
            }

            fun capture(pointerValue: Any?, installed: Boolean = false) {
                if (pointerValue == null) return
                runCatching {
                    val parsed = parser.parse(pointerValue) ?: return@runCatching
                    val id = parsed.id ?: return@runCatching
                    if (!installed && id in installedIds) return@runCatching
                    songs[id] = parsed
                    installedIds.retainAll(songs.keys)
                    if (installed) installedIds += id
                    main.post {
                        if (!scope.isActive) return@post
                        if (id == songMeta?.id) {
                            song = parsed
                            lastLineKey = null
                            publish(force = true)
                        }
                    }
                }.onFailure { MediaRuntime.log("system lyric snapshot failed", it) }
            }

            fun hook(method: java.lang.reflect.Method, callback: PluginMethodHook) {
                check(MediaRuntime.observeMethod(method, callback, scope))
            }

            val publishMeta = PluginProfiles.method("player-metadata-publish-method")
            val converter = PluginProfiles.method("metadata-to-playback-item-method")
            val getId = item.getMethod("getId")
            hook(publishMeta, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val playbackItem = converter.invoke(null, param.args.firstOrNull()) ?: return@runCatching
                        val id = (getId.invoke(playbackItem) as? String)?.toLongOrNull() ?: return@runCatching
                        currentSong.publish(playbackItem, id)
                    }.onFailure { MediaRuntime.log("system lyric current song snapshot failed", it) }
                }
            })
            hook(buildLyrics, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && param.thisObject === requestModel) runCatching {
                        val ptr = param.args.getOrNull(0) ?: return@runCatching
                        val language = systemLanguage.invoke(param.thisObject) as String
                        val native = unwrap.invoke(ptr) ?: return@runCatching
                        selectTranslation.invoke(native, language)
                        val pronunciation = matchPronunciation.invoke(null, pronunciationLanguages.invoke(native)) as? String
                        if (pronunciation != null) selectPronunciation.invoke(native, pronunciation)
                    }.onFailure { MediaRuntime.log("system lyric translation selection failed", it) }
                    if (param.throwable == null) capture(param.args.getOrNull(0))
                }
            })
            hook(installLyrics, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) runCatching {
                        capture(installedPointer.get(param.thisObject), installed = true)
                    }.onFailure { MediaRuntime.log("system lyric installed snapshot failed", it) }
                }
            })
            hook(MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val metadata = param.args.getOrNull(0) as? MediaMetadata
                    val owner = param.thisObject as? MediaSession
                    main.post {
                        if (!scope.isActive) return@post
                        session = owner
                        mediaNotification.attachSession(owner, metadata)
                        val id = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
                        val meta = id?.let {
                            LiveLyricSongMeta(
                                id = it,
                                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                            )
                        }
                        val changed = meta?.id != songMeta?.id
                        songMeta = meta
                        if (changed) {
                            requestedId = null
                            lastLineKey = null
                            song = id?.let { songs[it] }
                            if (meta == null) {
                                playback = null
                                main.removeCallbacks(tick)
                                fanout(null, position(), playing = false)
                            }
                        }
                        request()
                        if (changed) publish(force = true)
                    }
                }
            })
            hook(MediaSession::class.java.getDeclaredMethod("setPlaybackState", PlaybackState::class.java), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val value = param.args.getOrNull(0) as? PlaybackState
                    val owner = param.thisObject as? MediaSession
                    main.post {
                        if (!scope.isActive || (session != null && session !== owner)) return@post
                        playback = value
                        val playing = value?.state == PlaybackState.STATE_PLAYING
                        main.removeCallbacks(tick)
                        if (!playing) {
                            lastLineKey = null
                            fanout(null, position(), playing = false)
                        } else if (screenOn()) {
                            main.post(tick)
                            publish(force = true)
                        }
                    }
                }
            })

            if (options.meizuTicker) ticker.setEnabled(true)
            if (options.mediaNotificationLyrics) mediaNotification.setEnabled(true)
            if (options.superLyric) superLyric.setEnabled(true)
            if (options.lyricGetter) lyricGetter.setEnabled(true)

            scope.onClose {
                running = false
                main.post {
                    main.removeCallbacksAndMessages(null)
                    ticker.destroy()
                    mediaNotification.destroy()
                    superLyric.destroy()
                    lyricGetter.destroy()
                    playback = null
                    session = null
                }
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

            val parts = buildList {
                if (options.meizuTicker) add("魅族Ticker")
                if (options.mediaNotificationLyrics) add("媒体通知歌词")
                if (options.superLyric) add(if (superLyric.isAvailable()) "SuperLyric" else "SuperLyric(未安装)")
                if (options.lyricGetter) add(if (lyricGetter.isAvailable()) "LyricGetter" else "LyricGetter(未安装)")
            }
            TargetCapabilityInstall.Active("系统歌词投递已接入：${parts.joinToString("、")}")
                .also { result = it }
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun publish(force: Boolean) {
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val pos = position()
        val line = if (playing) LiveLyricIndexer.lineAt(song, pos) else null
        val key = line?.let { "${it.beginMs}:${it.text}:${it.secondary.orEmpty()}" }
        if (!force && key != null && key == lastLineKey) return
        if (key != null) lastLineKey = key
        fanout(line, pos, playing)
    }

    private fun fanout(line: LiveLyricLine?, positionMs: Long, playing: Boolean) {
        runCatching { if (options.meizuTicker) ticker.onLyric(songMeta, line, positionMs, playing) }
        runCatching { if (options.mediaNotificationLyrics) mediaNotification.onLyric(songMeta, line, positionMs, playing) }
        runCatching { if (options.superLyric) superLyric.onLyric(songMeta, line, positionMs, playing) }
        runCatching { if (options.lyricGetter) lyricGetter.onLyric(songMeta, line, positionMs, playing) }
    }

    private fun screenOn(): Boolean =
        (application.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun position(): Long {
        val value = playback ?: return 0L
        val elapsed = if (value.state == PlaybackState.STATE_PLAYING && value.lastPositionUpdateTime > 0)
            (SystemClock.elapsedRealtime() - value.lastPositionUpdateTime).coerceAtLeast(0) else 0L
        return (value.position + elapsed * value.playbackSpeed).toLong().coerceAtLeast(0)
    }
}
