package dev.kifranei.ampp.media

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import dev.amenhancer.plugin.api.*
import java.lang.reflect.Executable
import java.net.JarURLConnection
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** All registrations go through SDK v1 and are owned by this plugin's lifetime. */
internal object MediaRuntime {
    lateinit var context: PluginContext
    var modulePackageName: String = "dev.amenhancer.module"
        private set

    fun log(message: String, error: Throwable? = null) = context.log(message, error)
    /** A callback failing this many times is unregistered; only that hook stops, the host method runs natively. */
    const val MAX_CALLBACK_FAILURES = 3

    /**
     * Strict fail-open bridge: the callback runs against a staged copy of the call, and its argument and
     * outcome changes are committed to the host only when it returns normally. A throwing callback leaves the
     * host call untouched, is reported to [MediaDiag] with the hooked member and capability, and is never
     * rethrown (the AM++ SDK would otherwise stop the whole plugin).
     */
    fun hookMethod(member: Executable, callback: PluginMethodHook, scope: PluginScope): Boolean {
        val guard = CallbackGuard(member, MediaDiag.capability, "hook")
        val registration = context.hooks.hook(member, false, object : PluginHook {
            fun run(call: PluginCall, after: Boolean) {
                if (!scope.isActive || guard.disabled) return
                val staged = PluginCall(call.method, call.receiver, call.arguments.copyOf(), call.result, call.throwable)
                val param = PluginMethodHook.MethodHookParam(staged)
                if (!guard.run(after) { if (after) callback.afterHookedMethod(param) else callback.beforeHookedMethod(param) }) return
                staged.arguments.copyInto(call.arguments)
                if (staged.isOutcomeChanged) {
                    val thrown = staged.throwable
                    if (thrown != null) call.throwException(thrown) else call.returnResult(staged.result)
                }
            }
            override fun before(call: PluginCall) = run(call, false)
            override fun after(call: PluginCall) = run(call, true)
        })
        guard.registration = registration
        scope.onClose(registration::close)
        return true
    }
    fun observeMethod(member: Executable, callback: PluginMethodHook, scope: PluginScope): Boolean {
        val guard = CallbackGuard(member, MediaDiag.capability, "observe")
        val registration = context.hooks.observe(member, object : PluginObserver {
            fun param(call: PluginObservation) = PluginMethodHook.MethodHookParam(
                PluginCall(call.method, call.receiver, call.arguments, call.result, call.throwable))
            fun run(call: PluginObservation, after: Boolean) {
                if (!scope.isActive || guard.disabled) return
                guard.run(after) { if (after) callback.afterHookedMethod(param(call)) else callback.beforeHookedMethod(param(call)) }
            }
            override fun before(call: PluginObservation) = run(call, false)
            override fun after(call: PluginObservation) = run(call, true)
        })
        guard.registration = registration
        scope.onClose(registration::close)
        return true
    }
    /** Only the host's third-party Compose resources are reused; plugin views are separate classes. */
    fun glassContext(activity: Context): Context {
        val packageNames = setOf("dev.amenhancer.module", "dev.amenhancer.module.debug")
        val info = packageNames.firstNotNullOfOrNull { name ->
            runCatching { activity.packageManager.getApplicationInfo(name, 0) }.getOrNull()
        } ?: PluginContext::class.java.classLoader!!.getResources("AndroidManifest.xml").asSequence()
            .firstNotNullOfOrNull { resource ->
                // Package visibility can hide the module from the host app. Derive
                // its APK from the public SDK loader without reading module internals.
                runCatching {
                    val connection = resource.openConnection() as? JarURLConnection ?: return@runCatching null
                    val file = File(connection.jarFileURL.toURI())
                    activity.packageManager.getPackageArchiveInfo(file.path, 0)?.applicationInfo
                        ?.takeIf { it.packageName in packageNames }?.apply {
                            sourceDir = file.path; publicSourceDir = file.path
                        }
                }.getOrNull()
            } ?: error("液态玻璃需要可访问的 AM++ 插件宿主资源")
        modulePackageName = info.packageName
        val resources = activity.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        val isolated = Resources(resources.assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
        val theme = isolated.newTheme().apply { applyStyle(info.theme.takeIf { it != 0 }
            ?: android.R.style.Theme_Material_Light_NoActionBar, true) }
        return object : ContextWrapper(activity) {
            override fun getResources() = isolated
            override fun getAssets() = isolated.assets
            override fun getTheme() = theme
            override fun getClassLoader() = PluginGlassHostView::class.java.classLoader!!
        }
    }
}

/** Per-registration failure accounting for [MediaRuntime.hookMethod] / [MediaRuntime.observeMethod]. */
internal class CallbackGuard(private val member: Executable, private val capability: String?, private val kind: String) {
    private val failures = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var registration: PluginRegistration? = null
    @Volatile var disabled = false
        private set
    private val label get() = "${capability ?: "late"} $kind ${MediaDiag.member(member)}"
    /** Returns true when [block] completed normally. */
    inline fun run(after: Boolean, block: () -> Unit): Boolean = try { block(); true } catch (error: Throwable) {
        failed(after, error); false
    }
    fun failed(after: Boolean, error: Throwable) {
        val count = failures.incrementAndGet()
        MediaDiag.event("hook-failed", "$label phase=${if (after) "after" else "before"} count=$count; host call left unchanged", error,
            key = "hook-failed|$label")
        if (count >= MediaRuntime.MAX_CALLBACK_FAILURES && !disabled) {
            disabled = true
            runCatching { registration?.close() }
            MediaDiag.event("hook-disabled", "$label disabled after $count failures; other hooks keep running", key = null)
        }
    }
}

internal abstract class PluginMethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit
    class MethodHookParam(private val call: PluginCall) {
        val method get() = call.method
        val thisObject get() = call.receiver
        val args get() = call.arguments
        var result: Any?
            get() = call.result
            set(value) = call.returnResult(value)
        var throwable: Throwable?
            get() = call.throwable
            set(value) { if (value != null) call.throwException(value) }
    }
}

internal class PluginScope : AutoCloseable {
    private val closed = AtomicBoolean()
    private val cleanup = ArrayList<() -> Unit>()
    @Volatile var isActive = false
        private set
    init { MediaRuntime.context.onClose(::close) }
    @Synchronized fun onClose(action: () -> Unit) {
        if (closed.get()) action() else cleanup += action
    }
    @Synchronized fun activate() { if (!closed.get()) isActive = true }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        isActive = false
        cleanup.asReversed().forEach { runCatching(it).onFailure { error -> MediaRuntime.log("插件清理失败", error) } }
        cleanup.clear()
    }
}
