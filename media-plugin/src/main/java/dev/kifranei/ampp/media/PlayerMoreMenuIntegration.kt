package dev.kifranei.ampp.media

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.FrameLayout
import java.util.IdentityHashMap

/** Present the player's native commands in a floating iOS glass menu. */
internal class PlayerMoreMenuIntegration(private val build: TargetBuild, private val downloads: ContentDownloadsIntegration? = null) {
    fun install(): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < 33) return TargetCapabilityInstall.Unsupported("完整玻璃折射需要 Android 13+")
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("playerMoreMenu")
            ?: return TargetCapabilityInstall.Unsupported("No verified player-menu contract for ${build.displayName}")
        val create = PluginProfiles.method("more-dialog-create")
        val destroy = PluginProfiles.method("more-dialog-destroy")
        val controller = PluginProfiles.field("more-dialog-controller")
        val playerMethod = PluginProfiles.method("player-action-sheet-method")
        val player = playerMethod.declaringClass
        val dialogType = PluginProfiles.type(names.getString("dialogClass"))
        val scope = PluginScope()
        val owners = IdentityHashMap<Any, Dialog>()
        val surfaces = IdentityHashMap<Dialog, Surface>()
        val bypass = ThreadLocal<Dialog?>()
        fun nativeShow(dialog: Dialog) {
            bypass.set(dialog)
            try { dialog.show() } finally { bypass.remove() }
        }
        try {
            check(MediaRuntime.observeMethod(create, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject ?: return
                    if (!player.isInstance(controller.get(owner))) return
                    val dialog = param.result as? Dialog ?: return
                    if (dialogType.isInstance(dialog)) owners[owner] = dialog
                }
            }, scope))
            check(MediaRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("show"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    if (bypass.get() === dialog || dialog.isShowing || !owners.containsValue(dialog)) return
                    surfaces[dialog]?.let { param.result = null; return }
                    val activity = activity(dialog.context) ?: return
                    if (activity.isFinishing || activity.isDestroyed) return
                    val listId = activity.resources.getIdentifier(names.getString("listId"), "id", build.packageName)
                    val coordinatorId = activity.resources.getIdentifier(names.getString("coordinatorId"), "id", build.packageName)
                    val list = dialog.findViewById<ViewGroup>(listId) ?: return
                    val container = list.parent as? ViewGroup ?: return
                    if (container.id != coordinatorId) return
                    if (dialog.window == null) return
                    val owner = owners.entries.firstOrNull { it.value === dialog }?.key ?: return
                    val surface = runCatching { Surface(activity, dialog, owner, list, container, downloads) }
                        .onFailure { MediaRuntime.log("player_more_glass: preparation failed", it) }.getOrNull() ?: return
                    surfaces[dialog] = surface
                    try {
                        surface.capture { ready ->
                            if (surface.closed || !scope.isActive || !owners.containsValue(dialog) || activity.isDestroyed || activity.isFinishing) {
                                surface.close(); surfaces.remove(dialog); return@capture
                            }
                            if (!ready) {
                                surface.close(); surfaces.remove(dialog)
                                nativeShow(dialog)
                                return@capture
                            }
                            runCatching {
                                surface.mount()
                                nativeShow(dialog)
                                MediaRuntime.log("player_more_glass: first window shown with floating iOS menu")
                            }.onFailure {
                                surface.close(); surfaces.remove(dialog)
                                MediaRuntime.log("player_more_glass: native fallback", it)
                                nativeShow(dialog)
                            }
                        }
                        param.result = null
                    } catch (error: Throwable) {
                        surface.close(); surfaces.remove(dialog)
                        MediaRuntime.log("player_more_glass: capture failed", error)
                    }
                }
            }, scope))
            check(MediaRuntime.observeMethod(Dialog::class.java.getDeclaredMethod("dismiss"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    surfaces.remove(param.thisObject)?.close()
                }
            }, scope))
            check(MediaRuntime.observeMethod(destroy, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dialog = owners.remove(param.thisObject) ?: return
                    surfaces.remove(dialog)?.close()
                }
            }, scope))
            scope.onClose {
                surfaces.values.toList().forEach(Surface::close)
                surfaces.clear(); owners.clear(); bypass.remove()
            }
            scope.activate()
            return TargetCapabilityInstall.Active("Player native commands in floating iOS glass menu installed")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    @android.annotation.TargetApi(33)
    private class Surface(
        private val activity: Activity,
        private val dialog: Dialog,
        owner: Any,
        list: ViewGroup,
        private val container: ViewGroup,
        downloads: ContentDownloadsIntegration?,
    ) : AutoCloseable {
        val closed: Boolean get() = cleanup.closed
        private val main = Handler(Looper.getMainLooper())
        private val window = checkNotNull(dialog.window)
        private val attributes = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        private val windowBackground = window.decorView.background
        private val visibility = container.visibility
        private val content = checkNotNull(window.decorView.findViewById<ViewGroup>(android.R.id.content))
        private val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        private val native = NativePlayerMoreMenu(activity, owner, list, downloads) { dialog.dismiss() }
        private val root = FrameLayout(activity).apply {
            setOnClickListener { dialog.cancel() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val card = FrameLayout(activity).apply {
            isClickable = true
            elevation = 18f * resources.displayMetrics.density
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 28f * resources.displayMetrics.density }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 28f * view.resources.displayMetrics.density)
                    outline.alpha = .35f
                }
            }
            clipToOutline = true
        }
        private val hidden = FrameLayout(activity).apply { visibility = View.INVISIBLE }
        private val glass = PluginGlassHostView(MediaRuntime.glassContext(activity), bleedDp = 0).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false; isFocusable = false
        }
        private val backdrop = DialogWindowBackdrop(activity, glass, live = true)
        private val menu = IosPlayerMoreMenuView(activity, dark) { entry, view ->
            if (!closed && entry.enabled) runCatching { entry.perform(view) }
                .onFailure { MediaRuntime.log("player_more_glass: native command failed", it) }
        }
        private var tree: ViewTreeObserver? = null
        private var mounted = false
        private var contentHeight = 0
        private var measuredMenuWidth = 0
        private var measuredSignature: List<List<Any?>> = emptyList()
        private val position = IntArray(2)
        private val windowPosition = IntArray(2)
        private var loggedBounds: PlayerMenuBounds? = null
        private val preDraw = ViewTreeObserver.OnPreDrawListener { layout(); true }
        private val update = object : Runnable {
            override fun run() {
                if (closed || !root.isAttachedToWindow) return
                runCatching { menu.update(native.entries()) }
                    .onFailure { MediaRuntime.log("player_more_glass: native menu refresh failed", it) }
                root.postDelayed(this, 500L)
            }
        }
        private val attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                tree = root.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
                root.post(update)
            }
            override fun onViewDetachedFromWindow(view: View) { cleanup.close(insideWindowDetach = true) }
        }
        private val cleanup: MenuSurfaceCleanup = MenuSurfaceCleanup(
            // Use the main Handler: View.post can remain queued on a detached view.
            defer = { action -> main.post { action() } },
            stop = {
                root.removeCallbacks(update)
                root.removeOnAttachStateChangeListener(attached)
                tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw); tree = null
                backdrop.close()
            },
            restore = {
                container.visibility = visibility
                if (mounted) (root.parent as? ViewGroup)?.removeView(root)
                window.setBackgroundDrawable(windowBackground)
                window.attributes = attributes
            },
        )
        init {
            native.prepareNavigation(hidden)
            menu.update(native.entries())
            glass.content { GlassPlayerMoreMenu(backdrop, dark) }
            card.addView(glass, FrameLayout.LayoutParams(-1, -1))
            card.addView(menu, FrameLayout.LayoutParams(-1, -1))
            root.addView(hidden, FrameLayout.LayoutParams(0, 0))
            root.addView(card, FrameLayout.LayoutParams(1, 1))
        }
        fun capture(ready: (Boolean) -> Unit) = backdrop.capture(ready)
        fun mount() {
            check(!closed)
            root.addOnAttachStateChangeListener(attached)
            content.addView(root, ViewGroup.LayoutParams(-1, -1))
            mounted = true
            container.visibility = View.INVISIBLE
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setWindowAnimations(0)
            window.setDimAmount(.12f)
            window.setLayout(-1, -1)
            window.setGravity(android.view.Gravity.FILL)
        }
        private fun layout() {
            if (closed || !mounted || root.width <= 0 || root.height <= 0) return
            root.getLocationOnScreen(position)
            val decor = root.rootView
            decor.getLocationOnScreen(windowPosition)
            val insets = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val safe = PlayerMenuLayout.localInsets(
                PlayerMenuBounds(position[0], position[1], root.width, root.height),
                PlayerMenuBounds(windowPosition[0], windowPosition[1], decor.width, decor.height),
                PlayerMenuInsets(insets?.left ?: 0, insets?.top ?: 0, insets?.right ?: 0, insets?.bottom ?: 0),
            )
            fun bounds(contentHeight: Int) = PlayerMenuLayout.bounds(root.width, root.height,
                root.resources.displayMetrics.density, safe.top, safe.bottom, contentHeight, safe.left, safe.right)
            val width = bounds(1).width
            val changed = measuredMenuWidth != width || measuredSignature != menu.signature
            if (changed) {
                menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                contentHeight = menu.measuredHeight
                measuredMenuWidth = width; measuredSignature = menu.signature
                menu.forceLayout(); card.forceLayout()
            }
            val target = bounds(contentHeight)
            val params = card.layoutParams as FrameLayout.LayoutParams
            if (params.width != target.width || params.height != target.height || params.leftMargin != target.left || params.topMargin != target.top) {
                params.width = target.width; params.height = target.height
                params.leftMargin = target.left; params.topMargin = target.top
                card.layoutParams = params
            }
            if (changed || card.measuredWidth != target.width || card.measuredHeight != target.height) {
                card.measure(View.MeasureSpec.makeMeasureSpec(target.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(target.height, View.MeasureSpec.EXACTLY))
            }
            card.layout(target.left, target.top, target.left + target.width, target.top + target.height)
            if (loggedBounds != target) {
                loggedBounds = target
                MediaRuntime.log("player_more_glass: fixed bounds=$target viewport=${root.width}x${root.height} density=${root.resources.displayMetrics.density} safe=$safe")
            }
            backdrop.refreshPosition()
        }
        override fun close() = cleanup.close()
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
