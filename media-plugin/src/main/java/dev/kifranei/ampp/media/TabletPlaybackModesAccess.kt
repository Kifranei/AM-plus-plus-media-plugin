package dev.kifranei.ampp.media

import android.os.Handler
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Native 1606 playback modes, independent of the queue fragment and its views.
 * Construct/call on the UI thread with the native PlayerMainFragment owner.
 * Close with the parent's player-view State; refresh after its resume/reattach.
 */
internal class TabletPlaybackModesAccess(
    owner: Any,
    onChanged: (State) -> Unit = {},
) : AutoCloseable {
    /** PlaybackRepeatMode.smali constants; h1.h(View) cycles OFF -> ALL -> ONE -> OFF. */
    enum class RepeatMode(val nativeValue: Int) { OFF(0), ALL(2), ONE(1) }

    data class State(
        val connected: Boolean = false,
        val shuffleEnabled: Boolean? = null,
        val repeatMode: RepeatMode? = null,
        val canShuffle: Boolean = false,
        val canRepeat: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val getBrowser = PluginProfiles.method("tablet-playback-media-browser")
    private val getShuffle = PluginProfiles.method("tablet-playback-get-shuffle")
    private val setShuffle = PluginProfiles.method("tablet-playback-set-shuffle")
    private val getRepeat = PluginProfiles.method("tablet-playback-get-repeat")
    private val setRepeat = PluginProfiles.method("tablet-playback-set-repeat")
    private val commandAvailable = PluginProfiles.method("tablet-playback-command-available")
    private val getLooper = PluginProfiles.method("tablet-playback-application-looper")
    private val isConnected = PluginProfiles.method("tablet-playback-backend-connected")
    private val addListener = PluginProfiles.method("tablet-playback-add-listener")
    private val removeListener = PluginProfiles.method("tablet-playback-remove-listener")
    private val addBrowserListener = PluginProfiles.method("tablet-playback-add-browser-listener")
    private val removeBrowserListener = PluginProfiles.method("tablet-playback-remove-browser-listener")
    private val disconnected = PluginProfiles.method("tablet-playback-browser-disconnected")
    private val backend = PluginProfiles.field("tablet-playback-controller-backend")
    private val nativeState = PluginProfiles.field("tablet-playback-main-state")
    private val modeBlocked = PluginProfiles.field("tablet-playback-mode-blocked")
    private var owner: Any? = owner
    private var onChanged: ((State) -> Unit)? = onChanged
    private var browser: Any? = null
    private var playerListenerAttached = false
    private var browserListenerAttached = false
    @Volatile private var closed = false

    @Volatile var state: State = State()
        private set

    private val scope = PluginScope()
    private val pendingRefresh = Runnable {
        if (!closed && scope.isActive) refresh()
    }
    private val playerListener = Proxy.newProxyInstance(
        addListener.parameterTypes.single().classLoader,
        arrayOf(addListener.parameterTypes.single()),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "AM++ tablet native playback modes listener"
            "onEvents", "onRepeatModeChanged", "onShuffleModeEnabledChanged",
            "onAvailableCommandsChanged", "onMediaMetadataChanged", "onTimelineChanged",
            "onPlayerError" -> { scheduleRefresh(); null }
            else -> null // Remaining z3.D$c methods are void callbacks.
        }
    }
    private val browserListener = Proxy.newProxyInstance(
        addBrowserListener.parameterTypes.single().classLoader,
        arrayOf(addBrowserListener.parameterTypes.single()),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "AM++ tablet playback browser listener"
            "onBrowserConnected", "onConnectionFailed" -> { scheduleRefresh(); null }
            else -> null
        }
    }
    init {
        requireUiThread()
        require(nativeState.declaringClass.isInstance(owner)) {
            "TabletPlaybackModesAccess requires the native PlayerMainFragment owner"
        }
        scope.onClose(::shutdown)
        try {
            check(MediaRuntime.observeMethod(disconnected, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) scheduleRefresh()
                }
            }, scope))
            browserListenerAttached = true
            // ia.a.a adds to the persistent connection-listener set. It does not
            // instantiate a queue, start a connection, or modify playback.
            addBrowserListener.invoke(null, browserListener)
            scope.activate()
            refreshNative(force = true)
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    /** Read native getters, including after a controller replacement or reconnect. */
    fun refresh(): State {
        requireUiThread()
        return refreshNative()
    }

    /** True means the native setter was invoked; the listener confirms resulting state. */
    fun toggleShuffle(): Boolean = change({ it.canShuffle }) { target, current ->
        setShuffle.invoke(target, !checkNotNull(current.shuffleEnabled))
    }

    fun setShuffleEnabled(enabled: Boolean): Boolean = change({ it.canShuffle }) { target, _ ->
        setShuffle.invoke(target, enabled)
    }

    /** Exact native queue-button order, using the freshly queried native mode. */
    fun cycleRepeat(): Boolean = change({ it.canRepeat }) { target, current ->
        val next = when (checkNotNull(current.repeatMode)) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeat.invoke(target, next.nativeValue)
    }

    fun setRepeatMode(mode: RepeatMode): Boolean = change({ it.canRepeat }) { target, _ ->
        setRepeat.invoke(target, mode.nativeValue)
    }

    private fun change(allowed: (State) -> Boolean, action: (Any, State) -> Unit): Boolean {
        requireUiThread()
        if (closed || !scope.isActive) return false
        val current = refreshNative()
        if (!allowed(current) || closed || !scope.isActive) return false
        val target = browser ?: return false
        return try {
            action(target, current)
            // No optimistic local flip: a rejected/asynchronous native command
            // keeps its actual getter value until the native listener updates it.
            refreshNative()
            true
        } catch (error: Throwable) {
            logFailure("native mode command failed", error)
            refreshNative()
            false
        }
    }

    private fun refreshNative(force: Boolean = false): State {
        if (closed || !scope.isActive) return state
        val next = try {
            val source = owner ?: return state
            val target = getBrowser.invoke(source)
            if (target !== browser) {
                detachPlayerListener()
                browser = target
            }
            if (target == null) {
                State()
            } else {
                check(getLooper.invoke(target) === Looper.myLooper()) {
                    "Native playback modes must use the controller application looper"
                }
                if (!playerListenerAttached) {
                    addListener.invoke(target, playerListener)
                    playerListenerAttached = true
                }
                val connection = backend.get(target)
                if (connection == null || isConnected.invoke(connection) != true) {
                    State()
                } else {
                    val repeatValue = (getRepeat.invoke(target) as Number).toInt()
                    val repeat = RepeatMode.entries.firstOrNull { it.nativeValue == repeatValue }
                    // Mirror h1's native b1.M restriction and i1.j's command
                    // permissions. Main.H is created before any queue pane.
                    val model = nativeState.get(source)
                    val blocked = model == null || modeBlocked.getBoolean(model)
                    State(
                        connected = true,
                        shuffleEnabled = getShuffle.invoke(target) as Boolean,
                        repeatMode = repeat,
                        canShuffle = !blocked && commandAvailable.invoke(target, COMMAND_SHUFFLE) == true,
                        canRepeat = !blocked && repeat != null &&
                            commandAvailable.invoke(target, COMMAND_REPEAT) == true,
                    )
                }
            }
        } catch (error: Throwable) {
            logFailure("native mode state unavailable", error)
            State()
        }
        val changed = force || next != state
        state = next
        if (changed && !closed) {
            runCatching { onChanged?.invoke(next) }
                .onFailure { logFailure("playback mode UI observer failed", it) }
        }
        return next
    }

    private fun scheduleRefresh() {
        if (closed) return
        // Deliver after the native event batch, so Main.H has also been updated.
        main.removeCallbacks(pendingRefresh)
        main.post(pendingRefresh)
    }

    private fun detachPlayerListener() {
        val old = browser
        if (old != null && playerListenerAttached) {
            runCatching { removeListener.invoke(old, playerListener) }
                .onFailure { logFailure("playback listener cleanup failed", it) }
        }
        playerListenerAttached = false
        browser = null
    }

    private fun shutdown() {
        closed = true
        main.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            detachPlayerListener()
            if (browserListenerAttached) {
                runCatching { removeBrowserListener.invoke(null, browserListener) }
                    .onFailure { logFailure("browser listener cleanup failed", it) }
            }
            browserListenerAttached = false
            owner = null
            onChanged = null
            state = State()
        }
        if (Looper.myLooper() === main.looper) cleanup.run() else main.post(cleanup)
    }

    private fun requireUiThread() {
        check(Looper.myLooper() === main.looper) { "Playback mode access requires the UI thread" }
    }

    private fun logFailure(message: String, error: Throwable) {
        MediaRuntime.log("tablet_playback_modes: $message",
            (error as? InvocationTargetException)?.targetException ?: error)
    }

    override fun close() = scope.close()

    private companion object {
        // Source: native i1.j(Player,b1), i1.smali 1986-2023; not broadcast values.
        const val COMMAND_SHUFFLE = 14
        const val COMMAND_REPEAT = 15
    }
}
