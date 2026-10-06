package dev.kifranei.ampp.media

import android.os.Looper
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.util.Size
import java.util.WeakHashMap
import java.util.IdentityHashMap
import java.lang.ref.WeakReference

/** The native square container owns the big cover size; a transition-sized child does not. */
internal class PlayerArtworkRecovery(private val build: TargetBuild) {
    fun install(): TargetCapabilityInstall {
        val profile = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?: return TargetCapabilityInstall.Unsupported("No verified artwork layout")
        val names = profile.document.getJSONObject("playerRecovery")
        val getView = PluginProfiles.method("player-fragment-view")
        val mainType = PluginProfiles.method("player-controller-create-view").declaringClass
        val resize = PluginProfiles.method("player-artwork-resize")
        val videoMode = PluginProfiles.field("player-artwork-video-mode")
        val sizeAnimation = PluginProfiles.field("player-artwork-size-animation")
        val baseline = PluginProfiles.field("player-artwork-static-baseline")
        val targetSize = PluginProfiles.field("player-artwork-target-size")
        val parentFragment = PluginProfiles.method("player-fragment-parent")
        val enter = PluginProfiles.field("player-pane-enter-running")
        val shared = PluginProfiles.field("player-pane-shared-running")
        val behavior = PluginProfiles.field("player-page-behavior")
        val behaviorState = PluginProfiles.field("player-page-behavior-state")
        val originals = WeakHashMap<View, Pair<Int, Int>>()
        val owners = WeakHashMap<View, WeakReference<Any>>()
        val listeners = WeakHashMap<Animator, AnimatorListenerAdapter>()
        val scope = PluginScope()
        val watches = IdentityHashMap<Any, ArtworkFrameWatch>()
        fun card(root: View): View? {
            val view = root.findViewById<View>(root.resources.getIdentifier(names.getString("cardId"), "id", build.packageName)) ?: return null
            val parent = view.parent as? ViewGroup ?: return null
            return view.takeIf { parent.id == root.resources.getIdentifier(names.getString("containerId"), "id", build.packageName) }
        }
        fun nativeSize(view: View) {
            val original = originals[view] ?: return
            val params = view.layoutParams ?: return
            if (params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = original.first; params.height = original.second; view.layoutParams = params
            }
        }
        fun prepare(root: View, pane: Any? = null) {
            if (Looper.myLooper() != Looper.getMainLooper()) return
            val card = card(root) ?: return
            if (pane != null && resize.declaringClass.isInstance(pane)) owners[card] = WeakReference(pane)
            val owner = owners[card]?.get()
            if (owner != null && (videoMode.getBoolean(owner) || (sizeAnimation.get(owner) as? Animator)?.isRunning == true)) {
                nativeSize(card); return
            }
            val params = card.layoutParams ?: return
            if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) return
            originals.putIfAbsent(card, params.width to params.height)
            // Preserve G0.P's absolute play/pause scale. Match the native artwork slot,
            // including its tablet column and density, before shared elements are captured.
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            card.layoutParams = params
            MediaRuntime.log("player_artwork: cover measurement follows native container; playback scale preserved")
        }
        fun stable(main: Any, root: View): Boolean = root.isShown && root.isLaidOut &&
            !enter.getBoolean(main) && !shared.getBoolean(main) &&
            behavior.get(main)?.let(behaviorState::getInt) == 3
        fun desired(root: View): Pair<View, Size>? {
            val cover = card(root) ?: return null
            val slot = cover.parent as ViewGroup
            if (!slot.isLaidOut || slot.width <= 1 || slot.height <= 1 || kotlin.math.abs(slot.width - slot.height) > 1) return null
            return cover to Size(slot.width, slot.height)
        }
        fun reconcile(main: Any, root: View) {
            if (!scope.isActive || !stable(main, root)) return
            val (cover, size) = desired(root) ?: return
            if (!cover.isShown || cover.alpha <= .01f) return
            val pane = owners[cover]?.get() ?: return
            if (!baseline.declaringClass.isInstance(pane) || videoMode.getBoolean(pane) ||
                (sizeAnimation.get(pane) as? Animator)?.isRunning == true) return
            if (baseline.get(pane) != size) baseline.set(pane, size)
            val image = cover.findViewById<View>(root.resources.getIdentifier(names.getString("imageId"), "id", build.packageName)) ?: return
            val params = image.layoutParams ?: return
            val mismatch = params.width > 0 && params.width != size.width || params.height > 0 && params.height != size.height
            if (!mismatch) return
            // The native setter can otherwise early-return while an old animation's child
            // params still disagree with its cached target. Let native code rewrite both children.
            if (targetSize.get(pane) == size) targetSize.set(pane, null)
            resize.invoke(pane, false, size)
            MediaRuntime.log("player_artwork: static child size reconciled to ${size.width}x${size.height}")
        }
        fun track(owner: Any, root: View) {
            if (watches.containsKey(owner)) return
            watches[owner] = ArtworkFrameWatch(root) { reconcile(owner, root) }
        }
        try {
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-pane-view-created"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) (param.args.firstOrNull() as? View)?.let { prepare(it, param.thisObject) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-create-view"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) (param.result as? View)?.let { root ->
                        prepare(root)
                        param.thisObject?.let { track(it, root) }
                    }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-resume"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && mainType.isInstance(param.thisObject))
                        (getView.invoke(param.thisObject) as? View)?.let { root ->
                            prepare(root); track(checkNotNull(param.thisObject), root)
                        }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-destroy-view"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { watches.remove(param.thisObject)?.close() }
            }, scope))
            check(MediaRuntime.hookMethod(resize, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val root = getView.invoke(param.thisObject) as? View ?: return
                    if (param.args.firstOrNull() == true) { card(root)?.let(::nativeSize); return }
                    val pane = param.thisObject ?: return
                    if (!baseline.declaringClass.isInstance(pane) || (sizeAnimation.get(pane) as? Animator)?.isRunning == true) return
                    var parent = parentFragment.invoke(pane)
                    repeat(4) {
                        if (parent != null && !mainType.isInstance(parent)) parent = parentFragment.invoke(parent)
                    }
                    val main = parent?.takeIf(mainType::isInstance) ?: return
                    val mainRoot = getView.invoke(main) as? View ?: return
                    if (!stable(main, mainRoot)) return
                    val (_, size) = desired(root) ?: return
                    baseline.set(pane, size)
                    param.args[1] = size
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || param.args.firstOrNull() != false) return
                    val owner = param.thisObject ?: return
                    val root = getView.invoke(owner) as? View ?: return
                    val animation = sizeAnimation.get(owner) as? Animator
                    if (animation?.isRunning == true) {
                        if (listeners.containsKey(animation)) return
                        val listener = object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(done: Animator) {
                                done.removeListener(this); listeners.remove(done)
                                if (scope.isActive && !videoMode.getBoolean(owner)) prepare(root, owner)
                            }
                        }
                        listeners[animation] = listener
                        animation.addListener(listener)
                    } else prepare(root, owner)
                }
            }, scope))
            scope.onClose {
                watches.values.toList().forEach(ArtworkFrameWatch::close); watches.clear()
                listeners.forEach { (animation, listener) -> animation.removeListener(listener) }
                listeners.clear()
                originals.keys.toList().forEach(::nativeSize)
                originals.clear(); owners.clear()
            }
            scope.activate()
            return TargetCapabilityInstall.Active("Native artwork slot measurement restored")
        } catch (error: Throwable) { scope.close(); throw error }
    }
}

private class ArtworkFrameWatch(private val root: View, private val update: () -> Unit) : AutoCloseable {
    private var tree: ViewTreeObserver? = null
    private val draw = ViewTreeObserver.OnPreDrawListener { update(); true }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { listen() }
        override fun onViewDetachedFromWindow(view: View) { unlisten() }
    }
    init { root.addOnAttachStateChangeListener(attach); if (root.isAttachedToWindow) listen() }
    private fun listen() { unlisten(); tree = root.viewTreeObserver.also { it.addOnPreDrawListener(draw) } }
    private fun unlisten() { tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(draw); tree = null }
    override fun close() { unlisten(); root.removeOnAttachStateChangeListener(attach) }
}
