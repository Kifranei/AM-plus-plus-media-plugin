package dev.kifranei.ampp.media

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.ImageButton
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Keep native binding/transition targets intact while exposing their actions in the right column. */
internal class TabletPlayerActionsIntegration(private val build: TargetBuild) {
    fun install(): TargetCapabilityInstall {
        val document = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)?.document
            ?: return TargetCapabilityInstall.Unsupported("No verified tablet player layout for ${build.displayName}")
        val names = document.getJSONObject("tabletPlayerActions")
        val create = PluginProfiles.method("player-controller-create-view")
        val getView = PluginProfiles.method("player-fragment-view")
        val callback = PluginProfiles.field("player-page-callback")
        val progress = PluginProfiles.field("player-page-progress-value")
        val callbackOwner = PluginProfiles.field("player-page-callback-owner")
        val behavior = PluginProfiles.field("player-page-behavior")
        val behaviorState = PluginProfiles.field("player-page-behavior-state")
        val enter = PluginProfiles.field("player-pane-enter-running")
        val shared = PluginProfiles.field("player-pane-shared-running")
        val selectedPane = PluginProfiles.field("player-selected-pane")
        val parentFragment = PluginProfiles.method("player-fragment-parent")
        val scope = PluginScope()
        val main = Handler(Looper.getMainLooper())
        val states = IdentityHashMap<Any, State>()
        fun ensure(owner: Any, createdRoot: ViewGroup? = null): State? {
            states[owner]?.let { return it }
            val root = createdRoot ?: getView.invoke(owner) as? ViewGroup ?: return null
            if (!scope.isActive) return null
            fun id(key: String) = root.resources.getIdentifier(names.getString(key), "id", build.packageName)
            val state = State(root, id("controlsId"), id("lyricsId"), id("queueId"), id("outputId"), id("progressId"), id("playId"),
                id("translationId"), id("vocalId"), id("vocalLimitsId"),
                id("previousId"), id("nextId"), owner,
                { behavior.get(owner)?.let(behaviorState::getInt) ?: 4 },
                { if (behavior.get(owner)?.let(behaviorState::getInt) == 3) 1f else callback.get(owner)?.let(progress::getFloat) ?: 0f },
                { enter.getBoolean(owner) || shared.getBoolean(owner) },
                { (selectedPane.get(owner) as? Enum<*>)?.name == "SONG" })
            states[owner] = state
            MediaRuntime.log("tablet_player: actions attached to native player root")
            return state
        }
        val tasks = PlayerLifecycleWork<Any>({ main.post(it) }, { main.removeCallbacks(it) }, { scope.isActive }) { ensure(it)?.dirty() }
        val bootstrap = PlayerViewBootstrap<Any, ViewGroup> { owner, root -> ensure(owner, root) }
        try {
            val pages = object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    states[param.thisObject]?.let { state ->
                        if ((param.args.firstOrNull() as? Enum<*>)?.name in setOf("SONG", "QUEUE")) state.beginPaneChange()
                        state.prepareNativeTransition()
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && create.declaringClass.isInstance(param.thisObject)) param.thisObject?.let(tasks::request)
                }
            }
            check(MediaRuntime.observeMethod(create, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject ?: return
                    // Fragment.getView() is not assigned until onCreateView returns to FragmentManager.
                    // Use the returned root for the first entry instead of waiting for a pane switch.
                    val root = param.result as? ViewGroup ?: return
                    bootstrap.created(owner, root)
                    tasks.created(owner)
                }
            }, scope))
            check(MediaRuntime.observeMethod(getView, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !create.declaringClass.isInstance(param.thisObject) || Looper.myLooper() != Looper.getMainLooper()) return
                    val root = param.result as? ViewGroup ?: return
                    param.thisObject?.let { bootstrap.observed(it, root) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-pane-view-created"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject?.let(parentFragment::invoke) ?: return
                    if (!create.declaringClass.isInstance(owner)) return
                    (param.args.firstOrNull() as? View)?.let { ensure(owner)?.preparePane(it) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-resume"), pages, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-select-pane"), pages, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-page-progress"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val slide = (param.args.firstOrNull() as? Number)?.toFloat() ?: return
                    param.thisObject?.let(callbackOwner::get)?.let { states[it]?.pageProgress(slide) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) param.thisObject?.let(callbackOwner::get)?.let { states[it]?.update() }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-page-state-changed"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject?.let(callbackOwner::get) ?: return
                    states[owner]?.settled((param.args[1] as Number).toInt())
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-destroy-view"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { tasks.destroyed(it); bootstrap.destroyed(it) }
                    states.remove(param.thisObject)?.close()
                }
            }, scope))
            scope.onClose {
                tasks.close()
                bootstrap.close()
                main.removeCallbacksAndMessages(null)
                val cleanup = { states.values.toList().forEach(State::close); states.clear() }
                if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else main.post(cleanup)
            }
            scope.activate()
            return TargetCapabilityInstall.Active("Dual-column player: output at lower left, lyrics and queue at lower right")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private class HiddenAction(val view: View) {
        var nativeAlpha = view.alpha
        private val accessibility = view.importantForAccessibility
        private val clickable = view.isClickable
        fun hide() {
            if (view.alpha != 0f) nativeAlpha = view.alpha
            view.alpha = 0f
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.isClickable = false
        }
        fun restore() {
            if (view.alpha == 0f) view.alpha = nativeAlpha
            view.importantForAccessibility = accessibility
            view.isClickable = clickable
        }
    }

    private class ActionView(root: ViewGroup) : View(root.context) {
        var source: View? = null
        var onClickAction: (() -> Unit)? = null
        var selectionOverride: Boolean? = null
        var iconName: String? = null
        var alwaysCircle = false
        private var icon: Drawable? = null
        private var currentIconName: String? = null
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 255, 255, 255) }
        private var signature: String? = null
        init {
            visibility = INVISIBLE
            setOnClickListener { source?.takeIf { it.isEnabled }?.let { view -> onClickAction?.invoke() ?: view.performClick() } }
            setOnLongClickListener { source?.takeIf { it.isEnabled }?.performLongClick() == true }
            isFocusable = true
        }
        fun sync(view: View) {
            source = view
            isEnabled = view.isEnabled
            isSelected = selectionOverride ?: view.isSelected
            isLongClickable = view.isLongClickable
            contentDescription = view.contentDescription
            val key = "${view.hashCode()}:$isSelected:${view.isEnabled}:${view.contentDescription}:${view.drawableState.contentHashCode()}:${(view as? ImageView)?.imageTintList}"
            if (signature != key) { signature = key; invalidate() }
        }
        override fun onDraw(canvas: Canvas) {
            val view = source ?: return
            if (view.width <= 0 || view.height <= 0) return
            if ((isSelected || alwaysCircle) && iconName != null) {
                val radius = minOf(width, height) / 2f
                canvas.drawCircle(width / 2f, height / 2f, radius, selectedPaint)
            }
            if (iconName != null) {
                if (currentIconName != iconName) {
                    currentIconName = iconName
                    val resource = resources.getIdentifier(checkNotNull(iconName), "drawable", context.packageName)
                    icon = resource.takeIf { it != 0 }?.let {
                        if (iconName == "ic_nowplaying_translate") TabletTranslationGlyph.read(resources, it)
                        else context.getDrawable(it)?.mutate()
                    }
                }
                icon?.let { drawable ->
                    val edge = minOf((28 * resources.displayMetrics.density).roundToInt(), width, height)
                    val left = (width - edge) / 2; val top = (height - edge) / 2
                    drawable.setTint((view as? ImageView)?.imageTintList?.defaultColor ?: Color.WHITE)
                    drawable.setBounds(left, top, left + edge, top + edge)
                    drawable.draw(canvas)
                }
                return
            }
            val saved = canvas.save()
            canvas.scale(width.toFloat() / view.width, height.toFloat() / view.height)
            // View.draw renders its native icon/background; parent rendering applies alpha.
            view.draw(canvas)
            canvas.restoreToCount(saved)
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.ImageButton"
        }
    }

    private class State(private val root: ViewGroup, private val controlsId: Int, private val lyricsId: Int,
        private val queueId: Int, private val outputId: Int, private val progressId: Int, private val playId: Int,
        private val translationId: Int, private val vocalId: Int, private val vocalLimitsId: Int,
        private val previousId: Int, private val nextId: Int, owner: Any,
        private val sheetState: () -> Int, private val expansion: () -> Float, private val transition: () -> Boolean,
        private val songVisible: () -> Boolean) : AutoCloseable {
        private val lyrics = ActionView(root).apply { iconName = "ic_nowplaying_lyrics" }
        private val queue = ActionView(root).apply { iconName = "ic_nowplaying_queue" }
        private val languageProxy = ActionView(root).apply { iconName = "ic_nowplaying_translate"; alwaysCircle = true }
        private val outputProxy = ActionView(root)
        private val shuffle = ImageButton(root.context).apply { visibility = View.INVISIBLE; background = null }
        private val repeat = ImageButton(root.context).apply { visibility = View.INVISIBLE; background = null }
        private val modes = lazy { TabletPlaybackModesAccess(owner) { root.invalidate() } }
        private var modeSignature: String? = null
        private val metadata = TabletPlayerMetadataStyle(root, root.context.packageName)
        private val transportStyle = TabletTransportStyle()
        private val lyricsPane = TabletLyricsPanePresentation(root)
        private val artworkSpacing = TabletArtworkSpacing(root)
        private val contentWidth = TabletPlayerContentWidth(root)
        private val progressStyle = TabletPlayerProgressStyle(root)
        private var widthGroup: ViewGroup? = null
        private val hidden = IdentityHashMap<View, HiddenAction>()
        private val translations = IdentityHashMap<View, Pair<Float, Float>>()
        private var groups = emptyList<ViewGroup>()
        private var needsDiscovery = true
        private var tree: ViewTreeObserver? = null
        private var closed = false
        private var observedExpansion: Float? = null
        private val origin = IntArray(2)
        private val point = IntArray(2)
        private data class Frame(val left: Int, val top: Int, val width: Int, val height: Int, val alpha: Float, val rootWidth: Int, val rootHeight: Int)
        private val frames = IdentityHashMap<View, Frame>()
        private val paneSurface = TabletPaneTransitionSurface(root,
            { view -> hidden.getOrPut(view) { HiddenAction(view).also { hiddenSources[view] = it } }.hide() }, ::restoreHidden)
        private val preDraw = ViewTreeObserver.OnPreDrawListener { update(); true }
        private val layout = ViewTreeObserver.OnGlobalLayoutListener { needsDiscovery = true; metadata.rediscover() }
        private val attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { listen() }
            override fun onViewDetachedFromWindow(view: View) { unlisten(); restore() }
        }
        init {
            preparePane(root)
            root.addView(lyrics, ViewGroup.LayoutParams(1, 1)); root.addView(queue, ViewGroup.LayoutParams(1, 1))
            root.addView(outputProxy, ViewGroup.LayoutParams(1, 1))
            root.addView(languageProxy, ViewGroup.LayoutParams(1, 1))
            root.addView(shuffle, ViewGroup.LayoutParams(1, 1)); root.addView(repeat, ViewGroup.LayoutParams(1, 1))
            shuffle.setOnClickListener { if (!closed) { modes.value.toggleShuffle(); update() } }
            repeat.setOnClickListener { if (!closed) { modes.value.cycleRepeat(); update() } }
            lyrics.onClickAction = {
                if (!closed) {
                    lyricsPane.lyricsClick(songVisible())
                    // Retain native analytics/click handling; the host short-circuits its LYRICS transaction in dual-pane mode.
                    lyrics.source?.performClick()
                    update()
                }
            }
            root.addOnAttachStateChangeListener(attached)
            if (root.isAttachedToWindow) listen()
        }
        fun dirty() { needsDiscovery = true; metadata.rediscover(); update() }
        fun beginPaneChange() {
            if (root.resources.configuration.smallestScreenWidthDp < 600) return
            lyricsPane.leftHost()?.let(paneSurface::begin)
            transitionSurfaces[root] = java.lang.ref.WeakReference(paneSurface)
        }
        fun prepareNativeTransition() {
            // Native shared elements need their original container geometry, while the
            // visible root-level actions stay in place throughout the pane hand-off.
            lyricsPane.restore()
            artworkSpacing.restore()
            contentWidth.restore()
            // Keep action translations: their proxy/label positions are independent of native shared cover targets.
        }
        fun pageProgress(value: Float) {
            observedExpansion = value
        }
        fun settled(state: Int) {
            if (state == 3) observedExpansion = null
            update()
        }
        fun preparePane(start: View) {
            if (root.resources.configuration.smallestScreenWidthDp < 600) return
            PlayerVolumeControls.discover(start, { it.id == controlsId }) { view ->
                if (view is ViewGroup) (0 until view.childCount).map(view::getChildAt) else emptyList()
            }.filterIsInstance<ViewGroup>().forEach(progressStyle::apply)
        }
        private fun listen() {
            unlisten()
            tree = root.viewTreeObserver.also { it.addOnPreDrawListener(preDraw); it.addOnGlobalLayoutListener(layout) }
            needsDiscovery = true
        }
        private fun unlisten() {
            tree?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnGlobalLayoutListener(layout) }
            tree = null
        }
        private fun alpha(view: View): Float {
            var current: View? = view
            var value = 1f
            while (current != null && current !== root) { value *= nativeAlpha(current); current = current.parent as? View }
            return value
        }
        fun update() {
            if (closed) return
            if (!root.isAttachedToWindow || !root.isShown) { restore(); return }
            val currentState = sheetState()
            if (currentState == 3) observedExpansion = null
            // Decide tablet ownership before HOLD can hide native incoming buttons.
            // Phone panes must retain their native lyrics/output/queue throughout the transition.
            when (TabletPlayerPresentationMode.mode(root.resources.configuration.smallestScreenWidthDp,
                currentState, observedExpansion ?: expansion(), transition(), frames.isNotEmpty())) {
                PlayerVolumeMotion.Mode.HIDE -> {
                    if (root.resources.configuration.smallestScreenWidthDp >= 600 &&
                        TabletPlayerPresentationMode.suspend(currentState, frames.isNotEmpty())) suspendActions()
                    else restore()
                    return
                }
                PlayerVolumeMotion.Mode.HOLD -> {
                    if (transition()) { prepareNativeTransition(); hideIncomingActions() }
                    holdActions()
                    paneSurface.hold()
                    return
                }
                PlayerVolumeMotion.Mode.LAYOUT -> Unit
            }
            val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
            if (!tablet) { restore(); return }
            lyrics.selectionOverride = lyricsPane.apply(songVisible())
            if (needsDiscovery) {
                groups = PlayerVolumeControls.discover(root as View, { it.id == controlsId }) { view ->
                    if (view is ViewGroup) (0 until view.childCount).map(view::getChildAt) else emptyList()
                }.filterIsInstance<ViewGroup>()
                needsDiscovery = false
            }
            val group = PlayerVolumeControls.select(groups) { candidate ->
                candidate.takeIf { it.width > 0 && it.height > 0 }
                    ?.findViewById<View>(playId)?.takeIf { it.isShown && it.width > 0 && it.height > 0 }?.let(::alpha)
            } ?: run { restore(); return }
            if (paneSurface.active) {
                val isSongGroup = (group.parent as? View)?.id == root.resources.getIdentifier("player_container", "id", root.context.packageName)
                val ready = isSongGroup == songVisible() && !group.isLayoutRequested && alpha(group) >= .99f
                if (!paneSurface.ready(ready)) { hideIncomingActions(); holdActions(); paneSurface.hold(); return }
                paneSurface.end()
            }
            val sourceLyrics = group.findViewById<View>(lyricsId) ?: run { restore(); return }
            val sourceQueue = group.findViewById<View>(queueId) ?: run { restore(); return }
            val output = group.findViewById<View>(outputId) ?: run { restore(); return }
            val progress = group.findViewById<View>(progressId) ?: run { restore(); return }
            progressStyle.retain(group)
            progressStyle.apply(group)
            val region = contentWidth.apply(group, songVisible() && lyrics.selectionOverride != null)
            if (widthGroup !== group) widthGroup?.let(contentRegions::remove)
            widthGroup = group
            if (region != null) contentRegions[group] = region else contentRegions.remove(group)
            if (songVisible() && lyrics.selectionOverride != null) artworkSpacing.apply(group) else artworkSpacing.restore()
            root.getLocationInWindow(origin)
            group.getLocationInWindow(point)
            val columnLeft = point[0] - origin[0]
            sourceLyrics.getLocationInWindow(point)
            val top = point[1] - origin[1]
            progress.getLocationInWindow(point)
            val contentLeft = point[0] - origin[0]
            fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
            val inset = root.rootWindowInsets?.let {
                if (Build.VERSION.SDK_INT >= 30) it.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).right
                else @Suppress("DEPRECATION") it.systemWindowInsetRight
            } ?: 0
            val placement = TabletPlayerActionsGeometry.place(root.width, root.height, columnLeft, group.width, contentLeft,
                top, maxOf(sourceLyrics.height, sourceQueue.height), sourceLyrics.width, sourceQueue.width, dp(32) + inset, dp(16))
                ?: run {
                    restoreActions()
                    metadata.apply()
                    styleTransport(group)
                    // Narrow dual-pane windows can lack the right-edge space for two
                    // proxies after centering. Keep native-row proxies so lyrics can reopen.
                    if (lyrics.selectionOverride != null) {
                        placeNativeActions(sourceLyrics, sourceQueue)
                        placeOutput(output, contentLeft - lyricsPane.horizontalOffset.roundToInt())
                    }
                    placeModes(group, region?.left?.roundToInt() ?: columnLeft, region?.width?.roundToInt() ?: group.width)
                    return
                }
            metadata.apply()
            styleTransport(group)
            val language = root.findViewById<View>(translationId)?.takeIf { it.isShown && it.width > 0 && alpha(it) > .01f }
            val active = setOfNotNull(sourceLyrics, sourceQueue, output.takeIf { it.isShown }, language)
            hidden.keys.toList().filter { it !in active }.forEach { restoreHidden(it) }
            val vocal = root.findViewById<View>(vocalId)?.takeIf { it.isShown && it.width > 0 && alpha(it) > .01f }
            val limits = vocal?.let { root.findViewById<View>(vocalLimitsId) }
            val moved = setOfNotNull(output, language, vocal, limits)
            translations.keys.toList().filter { it !in moved }.forEach(::restoreTranslation)
            active.forEach { view -> hidden.getOrPut(view) { HiddenAction(view).also { hiddenSources[view] = it } }.hide() }
            lyrics.sync(sourceLyrics); queue.sync(sourceQueue)
            place(lyrics, placement.lyricsLeft, placement.top, sourceLyrics.width, sourceLyrics.height, alpha(sourceLyrics))
            place(queue, placement.queueLeft, placement.top, sourceQueue.width, sourceQueue.height, alpha(sourceQueue))
            // The output stays at the lower left when only the song column is centered.
            val outputLeft = columnLeft - lyricsPane.horizontalOffset.roundToInt()
            placeOutput(output, outputLeft)
            val topInset = root.rootWindowInsets?.let {
                if (Build.VERSION.SDK_INT >= 30) it.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                else @Suppress("DEPRECATION") it.systemWindowInsetTop
            } ?: 0
            val right = root.width - dp(24) - inset
            var languageTop = topInset + dp(40)
            vocal?.let { control ->
                move(control, right - control.width, topInset + dp(12))
                limits?.let { limit ->
                    val original = translations.getOrPut(limit) { limit.translationX to limit.translationY }
                    val source = checkNotNull(translations[control])
                    limit.translationX = original.first + control.translationX - source.first
                    limit.translationY = original.second + control.translationY - source.second
                }
                languageTop = topInset + dp(12) + control.height + dp(4)
            }
            language?.let {
                move(it, right - dp(44), languageTop)
                languageProxy.sync(it)
                place(languageProxy, right - dp(44), languageTop, dp(44), dp(44), alpha(it))
            } ?: run { languageProxy.visibility = View.INVISIBLE }
            placeModes(group, region?.left?.roundToInt() ?: columnLeft, region?.width?.roundToInt() ?: group.width)
        }
        private fun placeModes(group: ViewGroup, columnLeft: Int, columnWidth: Int = group.width) {
            fun hide() { shuffle.visibility = View.INVISIBLE; repeat.visibility = View.INVISIBLE }
            val previous = group.findViewById<ImageView>(previousId) ?: run { hide(); return }
            val next = group.findViewById<View>(nextId) ?: run { hide(); return }
            val play = group.findViewById<View>(playId) ?: run { hide(); return }
            fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
            previous.getLocationInWindow(point)
            val previousLeft = point[0] - origin[0]
            next.getLocationInWindow(point)
            val nextRight = point[0] - origin[0] + next.width
            val slots = TabletPlayerActionsGeometry.modes(columnLeft, columnWidth, previousLeft, nextRight, dp(44), dp(8), dp(4))
                ?: run { hide(); return }
            play.getLocationInWindow(point)
            val top = point[1] - origin[1] + (play.height - slots.size) / 2
            if (top < 0 || top + slots.size > root.height) { hide(); return }
            val state = modes.value.refresh()
            val ink = previous.imageTintList?.defaultColor ?: Color.WHITE
            val signature = "$state:$ink:${dp(1)}"
            if (modeSignature != signature) {
                modeSignature = signature
                fun icon(name: String) = root.resources.getIdentifier(name, "drawable", root.context.packageName)
                fun caption(name: String, fallback: String) = root.resources.getIdentifier(name, "string", root.context.packageName)
                    .takeIf { it != 0 }?.let(root.resources::getString) ?: fallback
                shuffle.setImageResource(icon("ic_nowplaying_shuffle"))
                repeat.setImageResource(icon(if (state.repeatMode == TabletPlaybackModesAccess.RepeatMode.ONE) "ic_nowplaying_repeatone" else "ic_nowplaying_repeat"))
                fun decorate(button: ImageButton, selected: Boolean, enabled: Boolean) {
                    button.isSelected = selected; button.isEnabled = enabled
                    button.imageTintList = ColorStateList.valueOf(ink)
                    button.setPadding(dp(13), dp(13), dp(13), dp(13))
                    button.background = if (selected) GradientDrawable().apply { setColor(Color.argb(30, 255, 255, 255)); cornerRadius = dp(10).toFloat() } else null
                }
                decorate(shuffle, state.shuffleEnabled == true, state.canShuffle)
                decorate(repeat, state.repeatMode != null && state.repeatMode != TabletPlaybackModesAccess.RepeatMode.OFF, state.canRepeat)
                shuffle.contentDescription = caption("shuffle", "Shuffle")
                repeat.contentDescription = caption("repeat", "Repeat") + if (state.repeatMode == TabletPlaybackModesAccess.RepeatMode.ONE) " 1" else ""
            }
            val opacity = alpha(play)
            fun modeAlpha(button: View) = opacity * if (!button.isEnabled) .3f else if (button.isSelected) 1f else .55f
            place(shuffle, slots.shuffleLeft, top, slots.size, slots.size, modeAlpha(shuffle))
            place(repeat, slots.repeatLeft, top, slots.size, slots.size, modeAlpha(repeat))
        }
        private fun move(view: View, left: Int, top: Int?) {
            val original = translations.getOrPut(view) { view.translationX to view.translationY }
            view.getLocationInWindow(point)
            val nativeLeft = point[0] - origin[0] - (view.translationX - original.first).roundToInt()
            val nativeTop = point[1] - origin[1] - (view.translationY - original.second).roundToInt()
            view.translationX = original.first + left - nativeLeft
            if (top != null) view.translationY = original.second + top - nativeTop
        }
        private fun place(view: View, left: Int, top: Int, width: Int, height: Int, opacity: Float) {
            frames[view] = Frame(left, top, width, height, opacity, root.width, root.height)
            val params = view.layoutParams
            if (params.width != width || params.height != height) { params.width = width; params.height = height; view.layoutParams = params }
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(left, top, left + width, top + height)
            view.alpha = opacity
            view.visibility = View.VISIBLE
        }
        private fun restoreHidden(view: View) { hidden.remove(view)?.restore(); hiddenSources.remove(view) }
        private fun restoreTranslation(view: View) { translations.remove(view)?.let { view.translationX = it.first; view.translationY = it.second } }
        private fun styleTransport(group: ViewGroup) {
            transportStyle.apply(group.findViewById(previousId), group.findViewById(playId), group.findViewById(nextId))
        }
        private fun placeOutput(output: View, left: Int) {
            if (!output.isShown) { outputProxy.visibility = View.INVISIBLE; return }
            hidden.getOrPut(output) { HiddenAction(output).also { hiddenSources[output] = it } }.hide()
            move(output, left, null)
            output.getLocationInWindow(point)
            outputProxy.sync(output)
            presentationSources[output] = java.lang.ref.WeakReference(outputProxy)
            place(outputProxy, left, point[1] - origin[1], output.width, output.height, alpha(output))
        }
        private fun placeNativeActions(sourceLyrics: View, sourceQueue: View) {
            fun proxy(target: ActionView, source: View) {
                source.getLocationInWindow(point)
                val left = point[0] - origin[0]
                val top = point[1] - origin[1]
                if (left < 0 || top < 0 || left + source.width > root.width || top + source.height > root.height) return
                hidden.getOrPut(source) { HiddenAction(source).also { hiddenSources[source] = it } }.hide()
                target.sync(source)
                place(target, left, top, source.width, source.height, alpha(source))
            }
            proxy(lyrics, sourceLyrics); proxy(queue, sourceQueue)
        }
        private fun restoreActions() {
            frames.clear()
            lyrics.visibility = View.INVISIBLE; queue.visibility = View.INVISIBLE
            outputProxy.visibility = View.INVISIBLE
            languageProxy.visibility = View.INVISIBLE
            shuffle.visibility = View.INVISIBLE; repeat.visibility = View.INVISIBLE
            hidden.keys.toList().forEach(::restoreHidden)
            translations.keys.toList().forEach(::restoreTranslation)
        }
        private fun suspendActions() {
            // Keep owned native geometry and hidden sources while the sheet is moving.
            // A settled expanded callback re-places the same proxies; collapse restores ownership.
            listOf(lyrics, queue, outputProxy, languageProxy, shuffle, repeat).forEach { it.visibility = View.INVISIBLE }
            paneSurface.end()
        }
        private fun holdActions() {
            for ((view, frame) in frames.toMap()) {
                if (frame.rootWidth != root.width || frame.rootHeight != root.height) view.visibility = View.INVISIBLE
                else place(view, frame.left, frame.top, frame.width, frame.height, frame.alpha)
            }
        }
        private fun hideIncomingActions() {
            // Fragment transactions can attach the new controls after select-pane returns.
            // Keep those native sources invisible until the stable frame selects its proxies.
            val candidates = PlayerVolumeControls.discover(root as View, { it.id == controlsId }) { view ->
                if (view is ViewGroup) (0 until view.childCount).map(view::getChildAt) else emptyList()
            }.filterIsInstance<ViewGroup>()
            for (group in candidates) for (id in listOf(lyricsId, queueId, outputId)) {
                val source = group.findViewById<View>(id) ?: continue
                hidden.getOrPut(source) { HiddenAction(source).also { hiddenSources[source] = it } }.hide()
            }
            root.findViewById<View>(translationId)?.let { source ->
                hidden.getOrPut(source) { HiddenAction(source).also { hiddenSources[source] = it } }.hide()
            }
        }
        private fun restore() {
            paneSurface.end()
            restoreActions()
            lyricsPane.restore()
            artworkSpacing.restore()
            contentWidth.restore()
            widthGroup?.let(contentRegions::remove); widthGroup = null
            lyrics.selectionOverride = null
            metadata.restore(); transportStyle.restore()
            progressStyle.close()
        }
        override fun close() {
            if (closed) return
            closed = true; unlisten(); root.removeOnAttachStateChangeListener(attached); restore(); metadata.close()
            paneSurface.close()
            transitionSurfaces.remove(root)
            lyrics.source = null; queue.source = null; outputProxy.source = null; languageProxy.source = null; lyrics.onClickAction = null
            if (modes.isInitialized()) modes.value.close()
            Handler(Looper.getMainLooper()).post { root.removeView(lyrics); root.removeView(queue); root.removeView(outputProxy); root.removeView(languageProxy); root.removeView(shuffle); root.removeView(repeat) }
        }
    }

    companion object {
        private val hiddenSources = WeakHashMap<View, HiddenAction>()
        private val presentationSources = WeakHashMap<View, java.lang.ref.WeakReference<View>>()
        private val contentRegions = WeakHashMap<View, TabletPlayerContentWidth.Region>()
        private val transitionSurfaces = WeakHashMap<View, java.lang.ref.WeakReference<TabletPaneTransitionSurface>>()
        fun holdsPane(root: View): Boolean = transitionSurfaces[root]?.get()?.active == true
        fun contentRegion(view: View): TabletPlayerContentWidth.Region? = contentRegions[view]
        fun presentationView(view: View): View = presentationSources[view]?.get()?.takeIf { it.visibility == View.VISIBLE } ?: view
        fun nativeAlpha(view: View): Float = hiddenSources[view]?.let { if (view.alpha == 0f) it.nativeAlpha else view.alpha } ?: view.alpha
    }
}

internal object TabletPlayerPresentationMode {
    fun mode(smallestWidthDp: Int, sheetState: Int, expansion: Float, transition: Boolean,
        hasPresentation: Boolean = false): PlayerVolumeMotion.Mode = when {
        smallestWidthDp < 600 -> PlayerVolumeMotion.Mode.HIDE
        sheetState == 3 -> if (transition) PlayerVolumeMotion.Mode.HOLD else PlayerVolumeMotion.Mode.LAYOUT
        nearExpanded(sheetState, expansion, hasPresentation) -> PlayerVolumeMotion.Mode.HOLD
        else -> PlayerVolumeMotion.Mode.HIDE
    }
    fun nearExpanded(sheetState: Int, expansion: Float, hasPresentation: Boolean) =
        hasPresentation && sheetState in 1..2 && expansion.isFinite() && expansion >= .95f
    fun suspend(sheetState: Int, hasPresentation: Boolean) = hasPresentation && sheetState in 1..2
}
