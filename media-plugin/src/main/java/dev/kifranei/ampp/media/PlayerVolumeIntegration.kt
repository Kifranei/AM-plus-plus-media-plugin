package dev.kifranei.ampp.media

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import org.json.JSONObject

/** Attach to the real player root, including pages already created before plugin startup. */
internal class PlayerVolumeIntegration(private val build: TargetBuild, private val compactTablet: Boolean = false) {
    fun install(): TargetCapabilityInstall {
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)?.document?.optJSONObject("playerVolume")
            ?: return TargetCapabilityInstall.Unsupported("No verified player-volume layout for ${build.displayName}")
        val create = PluginProfiles.method("player-controller-create-view")
        val getView = PluginProfiles.method("player-fragment-view")
        val resume = PluginProfiles.method("player-controller-resume")
        val destroy = PluginProfiles.method("player-controller-destroy-view")
        val slide = PluginProfiles.method("player-page-progress")
        val ownerField = PluginProfiles.field("player-page-callback-owner")
        val callbackField = PluginProfiles.field("player-page-callback")
        val progressField = PluginProfiles.field("player-page-progress-value")
        val behaviorField = PluginProfiles.field("player-page-behavior")
        val stateField = PluginProfiles.field("player-page-behavior-state")
        val enterTransition = PluginProfiles.field("player-pane-enter-running")
        val sharedTransition = PluginProfiles.field("player-pane-shared-running")
        val paneCreated = PluginProfiles.method("player-pane-view-created")
        val parentFragment = PluginProfiles.method("player-fragment-parent")
        val minimumHeight = PluginProfiles.field("player-controls-min-height")
        val scope = PluginScope()
        val main = Handler(Looper.getMainLooper())
        val states = IdentityHashMap<Any, State>()
        fun attach(owner: Any, root: ViewGroup) {
            if (!scope.isActive || states.containsKey(owner)) return
            val expanded = behaviorField.get(owner)?.let { stateField.getInt(it) == 3 } == true
            val progress = if (expanded) 1f else (callbackField.get(owner)?.let { progressField.getFloat(it) } ?: 0f)
            states[owner] = State(root, build.packageName, names, progress, minimumHeight, compactTablet,
                sheetState = { behaviorField.get(owner)?.let(stateField::getInt) ?: 4 },
                paneTransition = { enterTransition.getBoolean(owner) || sharedTransition.getBoolean(owner) })
            MediaRuntime.log("player_volume: attached to native playback page")
        }
        fun ensure(owner: Any): State? {
            states[owner]?.let { return it }
            (getView.invoke(owner) as? ViewGroup)?.let { attach(owner, it) }
            return states[owner]
        }
        try {
            val created = object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !create.declaringClass.isInstance(param.thisObject)) return
                    val owner = param.thisObject ?: return
                    val root = param.result as? ViewGroup ?: return
                    if (Looper.myLooper() == Looper.getMainLooper()) attach(owner, root) else main.post { attach(owner, root) }
                }
            }
            check(MediaRuntime.observeMethod(create, created, scope))
            check(MediaRuntime.observeMethod(getView, created, scope))
            check(MediaRuntime.observeMethod(paneCreated, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject?.let(parentFragment::invoke) ?: return
                    if (!create.declaringClass.isInstance(owner)) return
                    val paneRoot = param.args.firstOrNull() as? View ?: return
                    ensure(owner)?.preparePane(paneRoot)
                }
            }, scope))
            check(MediaRuntime.observeMethod(resume, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { if (param.throwable == null) param.thisObject?.let(::ensure) }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-select-pane"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Capture completed source row sizes before AM starts its shared transition.
                    states[param.thisObject]?.rememberPhoneRows()
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject ?: return
                    ensure(owner)?.refreshControls()
                    main.post { if (scope.isActive) states[owner]?.refreshControls() }
                }
            }, scope))
            check(MediaRuntime.observeMethod(slide, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject?.let(ownerField::get) ?: return
                    ensure(owner)?.let { it.expansion = (param.args[0] as Number).toFloat().coerceIn(0f, 1f); it.update() }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-page-state-changed"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject?.let(ownerField::get) ?: return
                    ensure(owner)?.let { state ->
                        state.expansion = callbackField.get(owner)?.let(progressField::getFloat) ?: state.expansion
                        if ((param.args[1] as Number).toInt() == 3) state.expansion = 1f
                        state.update()
                    }
                }
            }, scope))
            check(MediaRuntime.observeMethod(destroy, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { states.remove(param.thisObject)?.close() }
            }, scope))
            scope.onClose {
                val action = { states.values.toList().forEach(State::close); states.clear(); main.removeCallbacksAndMessages(null) }
                if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
            }
            scope.activate()
            return TargetCapabilityInstall.Active("iOS media-volume bar with system level synchronization installed")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private class State(private val root: ViewGroup, private val packageName: String, names: JSONObject, var expansion: Float,
        minimumHeight: java.lang.reflect.Field, private val compactTablet: Boolean,
        private val sheetState: () -> Int, private val paneTransition: () -> Boolean) : AutoCloseable {
        private val volume = IosVolumeSliderView(root.context).apply { visibility = View.INVISIBLE }
        private fun id(name: String) = root.resources.getIdentifier(name, "id", packageName)
        private val transportIds = names.getJSONArray("transportIds").let { values -> (0 until values.length()).map { id(values.getString(it)) } }
        private val progressIds = names.getJSONArray("progressIds").let { values -> (0 until values.length()).map { id(values.getString(it)) } }
        private val actionsId = id(names.getString("actionsId"))
        private val controlsId = id(names.getString("controlsId"))
        private val contentIds = names.getJSONArray("contentIds").let { values -> (0 until values.length()).map { id(values.getString(it)) }.toSet() }
        private data class Controls(val root: ViewGroup, val transports: List<View>, val actions: View, val progress: List<View>)
        private var controls = emptyList<Controls>()
        private var controlsDirty = true
        private data class Spacing(val params: ViewGroup.MarginLayoutParams, val value: PlayerVolumeSpacing)
        private val spacing = IdentityHashMap<View, Spacing>()
        private val space = PlayerVolumeSpace(root, minimumHeight, id("controls"))
        private val position = PlayerVolumePosition()
        private val holdColumn = PlayerVolumeHoldColumn<View>(position)
        private val tabletSizing = TabletPlayerControlSizing()
        private val panePreparation = PlayerVolumePanePreparation()
        private val drawSettlement = PlayerVolumeDrawSettlement()
        private fun phone() = root.resources.configuration.smallestScreenWidthDp < 600
        private var layoutRevision = 0L
        private val compactActionIds = listOf(actionsId, id("player_queue"), id("media_route_button"))
        private val timeIds = listOf(id("current_time_progress"), id("total_time_progress"))
        private fun compact() = compactTablet && root.resources.configuration.smallestScreenWidthDp >= 600
        private var loggedHoldCorrection = false
        private val rootPosition = IntArray(2)
        private val transportPosition = IntArray(2)
        private val actionsPosition = IntArray(2)
        private val progressPosition = IntArray(2)
        private val columnPosition = IntArray(2)
        private var loggedLayout: String? = null
        private var tree: ViewTreeObserver? = null
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            val before = layoutRevision
            update()
            // A spacing/minimum change requests another native layout. Do not display
            // the intermediate bounds of the first frame and then jump on the next one.
            !phone() || drawSettlement.allow(before != layoutRevision)
        }
        private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { controlsDirty = true }
        private val attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { listen() }
            override fun onViewDetachedFromWindow(view: View) { unlisten(); volume.setPageVisible(false) }
        }
        init {
            preparePane(root)
            root.addView(volume, ViewGroup.LayoutParams(1, 1))
            root.addOnAttachStateChangeListener(attached)
            if (root.isAttachedToWindow) listen()
        }
        private fun groupsIn(start: View) = PlayerVolumeControls.discover(start, { it.id == controlsId }) { view ->
            if (view is ViewGroup && view.id !in contentIds) {
                (0 until view.childCount).map(view::getChildAt)
            } else emptyList()
        }
        private fun preferredTransportShift(): Int {
            val dp = if (root.resources.configuration.smallestScreenWidthDp >= 600) 16 else 24
            return (dp * root.resources.displayMetrics.density).roundToInt()
        }
        fun preparePane(paneRoot: View) {
            // Run before the pane's first layout and before AM captures shared elements.
            rememberPhoneRows()
            val shift = -preferredTransportShift()
            groupsIn(paneRoot).filterIsInstance<ViewGroup>().forEach { group ->
                if (compact()) tabletSizing.apply(transportIds.mapNotNull { group.findViewById(it) },
                    compactActionIds.mapNotNull { group.findViewById(it) }, timeIds.mapNotNull { group.findViewById(it) })
                transportIds.forEach { id -> group.findViewById<View>(id)?.let { setSpacing(it, shift) } }
                if (phone()) controlsIn(group)?.let { controls ->
                    panePreparation.required(window(), rows(controls))?.let { height ->
                        if (space.reserve(controls.root, height)) {
                            layoutRevision++
                            MediaRuntime.log("player_volume: prepared phone controls before native layout; minimum $height")
                        }
                    }
                }
            }
            controlsDirty = true
        }
        private fun window() = PlayerVolumePanePreparation.Window(root.width, root.height,
            root.resources.configuration.toString())
        private fun rows(controls: Controls) =
            (controls.progress.filter { it.visibility != View.GONE } + controls.transports + controls.actions).map { view ->
                PlayerVolumePanePreparation.Row(view.id, view.layoutParams.width, view.layoutParams.height,
                    view.paddingTop, view.paddingBottom, (view as? ImageView)?.drawable?.intrinsicHeight ?: -1)
            }
        fun rememberPhoneRows() {
            if (!phone()) return
            groupsIn(root).filterIsInstance<ViewGroup>().mapNotNull(::controlsIn).forEach { controls ->
                requiredSpace(controls)?.let { height -> panePreparation.remember(window(), rows(controls), height) }
            }
        }
        private fun requiredSpace(controls: Controls): Int? {
            // A child may still report its old bounds while rotation or a parent
            // constraint change is waiting for the next native layout pass.
            if (root.isLayoutRequested || controls.root.isLayoutRequested) return null
            val density = root.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            // seek_bar_controls contains another ConstraintLayout. An UNSPECIFIED probe
            // bypasses both its parent bounds and fixed transport sizes; wait for AM's layout.
            fun height(view: View) = PlayerVolumeMeasurement.height(view.layoutParams.height, view.measuredHeight,
                view.height, view.isLaidOut && !view.isLayoutRequested)
            val progressRows = controls.progress.filter { it.visibility != View.GONE }
            if (progressRows.isEmpty()) return null
            val progress = progressRows.map { height(it) ?: return null }.max()
            val transport = controls.transports.map { height(it) ?: return null }.max()
            val actions = height(controls.actions) ?: return null
            val bottom = (controls.actions.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            return PlayerVolumeGeometry.controlsHeight(progress, transport, actions, bottom, dp(44), dp(if (compact()) 4 else 8))
        }
        private fun reserveSpace(controls: Controls): Boolean? {
            val height = requiredSpace(controls) ?: return null
            if (phone()) panePreparation.remember(window(), rows(controls), height)
            return space.reserve(controls.root, height, compact = compact()).also { if (it) layoutRevision++ }
        }
        private fun listen() {
            unlisten(); controlsDirty = true
            tree = root.viewTreeObserver.also { it.addOnPreDrawListener(preDraw); it.addOnGlobalLayoutListener(globalLayout) }
        }
        private fun unlisten() {
            tree?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnGlobalLayoutListener(globalLayout) }
            tree = null
        }
        fun refreshControls() { controlsDirty = true; update() }
        private fun controlsIn(group: ViewGroup): Controls? {
            val transports = transportIds.mapNotNull { group.findViewById<View>(it) }
            val actions = group.findViewById<View>(actionsId) ?: return null
            val progress = progressIds.mapNotNull { group.findViewById<View>(it) }
            if (transports.size != transportIds.size || progress.isEmpty()) return null
            return Controls(group, transports, actions, progress)
        }
        private fun discoverControls() {
            controls = groupsIn(root).filterIsInstance<ViewGroup>().mapNotNull(::controlsIn)
            controlsDirty = false
        }
        private fun opacity(controls: Controls): Float? {
            val play = controls.transports.first()
            val actions = controls.actions
            return if (play.isShown && actions.isShown && play.width > 0 && actions.width > 0 &&
                controls.progress.any(View::isShown)) minOf(effectiveAlpha(play), effectiveAlpha(actions)) else null
        }
        fun update() {
            fun hide() { volume.visibility = View.INVISIBLE; volume.setPageVisible(false) }
            fun hold() {
                if (TabletPlayerActionsIntegration.holdsPane(root)) {
                    if (!position.hold(root.width, root.height, ::placeVolume)) hide()
                    return
                }
                val source = holdColumn.source?.takeIf(View::isAttachedToWindow)
                val windowX = source?.let { it.getLocationInWindow(columnPosition); columnPosition[0] }
                if (!holdColumn.hold(windowX, root.width, root.height, ::placeVolume)) hide()
            }
            if (!root.isAttachedToWindow || !root.isShown) { hide(); return }
            val currentState = sheetState()
            if (currentState == 3) expansion = 1f
            if (compact() && TabletPlayerPresentationMode.nearExpanded(currentState, expansion, holdColumn.source != null)) {
                hold(); return
            }
            when (PlayerVolumeMotion.mode(currentState, expansion, paneTransition())) {
                PlayerVolumeMotion.Mode.HIDE -> { hide(); return }
                // Keep native geometry untouched, but undo the parent's layout of our extra View.
                PlayerVolumeMotion.Mode.HOLD -> {
                    hold()
                    if (volume.isShown && volume.top >= root.height / 2 && !loggedHoldCorrection) {
                        loggedHoldCorrection = true
                        MediaRuntime.log("player_volume: held bottom placement during native transition at ${volume.left},${volume.top}")
                    }
                    return
                }
                PlayerVolumeMotion.Mode.LAYOUT -> loggedHoldCorrection = false
            }
            if (controlsDirty) discoverControls()
            val current = PlayerVolumeControls.select(controls, ::opacity)
            var restored = false
            // Phone panes share the same rows. Keep overrides while their Views remain
            // in the player, so a retained pane is already laid out when AM reuses it.
            val retained = if (phone()) controls else listOfNotNull(current)
            PlayerVolumeLayoutState.inactive(spacing.keys, retained.flatMap(Controls::transports)).forEach {
                if (restoreSpacing(it)) restored = true
            }
            if (space.retain(retained.map(Controls::root))) { restored = true; layoutRevision++ }
            if (compact() && current != null) {
                val actions = compactActionIds.mapNotNull { current.root.findViewById<View>(it) }
                val times = timeIds.mapNotNull { current.root.findViewById<View>(it) }
                if (tabletSizing.retain(current.transports + actions + times)) { restored = true; layoutRevision++ }
                if (tabletSizing.apply(current.transports, actions, times)) { restored = true; layoutRevision++ }
            }
            if (!compact()) tabletSizing.close()
            if (current == null || restored || reserveSpace(current) != false) { hide(); return }
            val transports = current.transports.filter(View::isShown)
            val actions = current.actions
            val contentAlpha = checkNotNull(opacity(current)).coerceIn(0f, 1f)
            val density = root.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val margin = dp(if (compact()) 4 else 8)
            val progress = current.progress.firstOrNull(View::isShown)
                ?: run { hide(); return }
            root.getLocationInWindow(rootPosition); actions.getLocationInWindow(actionsPosition); progress.getLocationInWindow(progressPosition)
            current.root.getLocationInWindow(columnPosition)
            val height = dp(44)
            val actionsTop = actionsPosition[1] - rootPosition[1]
            val columnLeft = columnPosition[0] - rootPosition[0]
            val columnTop = columnPosition[1] - rootPosition[1]
            val columnBottom = columnTop + current.root.height
            // Shared-element sources may temporarily report the transition's origin.
            if (columnTop < 0 || columnBottom > root.height || columnBottom <= root.height / 2 ||
                actionsTop < columnTop + current.root.height / 2 || actionsTop.toLong() + actions.height > columnBottom) { hold(); return }
            var transportTop = Int.MAX_VALUE
            var transportBottom = Int.MIN_VALUE
            transports.forEach { view ->
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                val state = spacing[view]?.takeIf { it.params === params }
                val offset = if (params != null) state?.value?.offset(params.bottomMargin) ?: 0f else 0f
                view.getLocationInWindow(transportPosition)
                val y = transportPosition[1] - rootPosition[1] - offset.roundToInt()
                transportTop = minOf(transportTop, y); transportBottom = maxOf(transportBottom, y + view.height)
            }
            val shift = PlayerVolumeGeometry.transportShift(transportTop, transportBottom,
                progressPosition[1] - rootPosition[1] + progress.height, actionsTop, height, margin, preferredShift = preferredTransportShift()) ?: run {
                transports.forEach(::restoreSpacing); hide(); return
            }
            var layoutChanged = false
            transports.forEach { if (setSpacing(it, shift)) layoutChanged = true }
            if (layoutChanged) { hide(); return } // Wait for native ConstraintLayout to apply new bounds.
            val top = PlayerVolumeGeometry.top(transportBottom + shift, actionsTop, height, margin) ?: run { hide(); return }
            val cover = if (compact()) TabletPlayerActionsIntegration.contentRegion(current.root) else null
            // The entire tablet row, including both speakers, stays within the playing
            // cover width. Its built-in speaker slots shorten only the inner track.
            val bounds = if (cover != null) PlayerVolumeGeometry.column(cover.left.roundToInt(), cover.width.roundToInt(), root.width, 0)
                else PlayerVolumeGeometry.column(columnLeft, current.root.width, root.width, dp(32))
            val (left, width) = bounds ?: run { hide(); return }
            if (width < dp(120)) { hide(); return }
            val placement = PlayerVolumePlacement(left, top, width, height, root.width, root.height, contentAlpha,
                PlayerVolumeRegion(minOf(columnLeft, left), columnTop,
                    maxOf(columnLeft + current.root.width, left + width) - minOf(columnLeft, left), current.root.height))
            if (position.place(placement, ::placeVolume)) holdColumn.remember(current.root, columnPosition[0]) else hold()
            val layout = "${root.width}x${root.height}:${current.root.width}x${current.root.height}:${dp(1)}"
            if (loggedLayout != layout) {
                loggedLayout = layout
                MediaRuntime.log("player_volume: column ${current.root.width}x${current.root.height}; bar $left,$top ${width}x$height; transport shift $shift")
            }
        }
        private fun placeVolume(placement: PlayerVolumePlacement) {
            val params = volume.layoutParams
            if (params.width != placement.width || params.height != placement.height) {
                params.width = placement.width; params.height = placement.height; volume.layoutParams = params
            }
            volume.measure(View.MeasureSpec.makeMeasureSpec(placement.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(placement.height, View.MeasureSpec.EXACTLY))
            volume.layout(placement.left, placement.top, placement.left + placement.width, placement.top + placement.height)
            volume.alpha = placement.alpha
            volume.visibility = View.VISIBLE
            volume.setPageVisible(volume.alpha > .01f)
        }
        private fun effectiveAlpha(view: View): Float {
            var current: View? = view
            var alpha = 1f
            while (current != null && current !== root) { alpha *= TabletPlayerActionsIntegration.nativeAlpha(current); current = current.parent as? View }
            return alpha
        }
        private fun setSpacing(view: View, shift: Int): Boolean {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            val state = spacing[view]?.takeIf { it.params === params }
                ?: Spacing(params, PlayerVolumeSpacing(params.bottomMargin)).also { spacing[view] = it }
            val margin = state.value.update(params.bottomMargin, shift)
            if (margin == params.bottomMargin) return false
            params.bottomMargin = margin; view.layoutParams = params; layoutRevision++
            return true
        }
        private fun restoreSpacing(view: View): Boolean {
            val state = spacing.remove(view) ?: return false
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            if (params !== state.params) return false
            val margin = state.value.restore(params.bottomMargin)
            if (margin == params.bottomMargin) return false
            params.bottomMargin = margin; view.layoutParams = params; layoutRevision++
            return true
        }
        override fun close() {
            unlisten(); root.removeOnAttachStateChangeListener(attached); volume.close()
            spacing.keys.toList().forEach(::restoreSpacing)
            tabletSizing.close()
            space.close()
            controls = emptyList(); holdColumn.clear()
            // A destroy hook can run during native window detachment; defer hierarchy mutation.
            Handler(Looper.getMainLooper()).post { (volume.parent as? ViewGroup)?.removeView(volume) }
        }
    }
}
