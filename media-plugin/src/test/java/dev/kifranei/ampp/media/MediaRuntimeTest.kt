package dev.kifranei.ampp.media

import android.app.Application
import dev.amenhancer.plugin.api.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.lang.reflect.Executable

class MediaRuntimeTest {
    class Host { fun value(input: Int) = input }
    private class Runtime : PluginContext {
        val cleanup = ArrayList<Runnable>()
        lateinit var hook: PluginHook
        lateinit var observer: PluginObserver
        var registrationsClosed = 0
        override fun getApplication(): Application = error("Not used by bridge test")
        override fun getHostClassLoader(): ClassLoader = javaClass.classLoader!!
        override fun getHostPackageName() = "com.apple.android.music"
        override fun getHostVersionName() = "7.0.0-beta"
        override fun getHostVersionCode() = 1611L
        override fun getDataDirectory(): File = error("Not used")
        override fun getCacheDirectory(): File = error("Not used")
        override fun openAsset(path: String): InputStream = error("Not used")
        val logs = ArrayList<String>()
        override fun log(message: String, error: Throwable?) { logs += message }
        override fun onClose(action: Runnable) { cleanup += action }
        override fun claimResource(key: String, exclusive: Boolean) = PluginRegistration { }
        override fun getHooks() = object : PluginHooks {
            override fun hook(target: Executable, exclusive: Boolean, callback: PluginHook): PluginRegistration {
                assertFalse(exclusive)
                hook = callback
                return PluginRegistration { registrationsClosed++ }
            }
            override fun observe(target: Executable, callback: PluginObserver): PluginRegistration {
                observer = callback
                return PluginRegistration { registrationsClosed++ }
            }
        }
    }
    @Test fun `scope gates callbacks and closes SDK registrations exactly once`() {
        val runtime = Runtime().also { MediaRuntime.context = it }
        val scope = PluginScope()
        val target = Host::class.java.getDeclaredMethod("value", Integer.TYPE)
        MediaRuntime.hookMethod(target, object : PluginMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = 99; param.result = 42 }
        }, scope)
        val inactive = PluginCall(target, Host(), arrayOf(1), null, null)
        runtime.hook.before(inactive)
        assertEquals(1, inactive.arguments[0]); assertFalse(inactive.isOutcomeChanged)
        scope.activate()
        val active = PluginCall(target, Host(), arrayOf(1), null, null)
        runtime.hook.before(active)
        assertEquals(99, active.arguments[0]); assertEquals(42, active.result); assertTrue(active.isOutcomeChanged)
        runtime.cleanup.forEach(Runnable::run)
        scope.close()
        scope.activate()
        assertFalse(scope.isActive)
        assertEquals(1, runtime.registrationsClosed)
        val closed = PluginCall(target, Host(), arrayOf(1), null, null)
        runtime.hook.before(closed)
        assertEquals(1, closed.arguments[0]); assertFalse(closed.isOutcomeChanged)
    }
    @Test fun `after bridge preserves the host exception unless explicitly changed`() {
        val runtime = Runtime().also { MediaRuntime.context = it }
        val scope = PluginScope()
        val target = Host::class.java.getDeclaredMethod("value", Integer.TYPE)
        val failure = IllegalStateException("host failure")
        var observed: Throwable? = null
        MediaRuntime.hookMethod(target, object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { observed = param.throwable }
        }, scope)
        scope.activate()
        val call = PluginCall(target, Host(), arrayOf(1), null, failure)
        runtime.hook.after(call)
        assertSame(failure, observed); assertSame(failure, call.throwable); assertFalse(call.isOutcomeChanged)
        scope.close()
    }
    @Test fun `observations cannot accidentally commit a callback array change`() {
        val runtime = Runtime().also { MediaRuntime.context = it }
        val scope = PluginScope()
        val target = Host::class.java.getDeclaredMethod("value", Integer.TYPE)
        MediaRuntime.observeMethod(target, object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { param.args[0] = 99 }
        }, scope)
        scope.activate()
        val original = arrayOf<Any>(1)
        val call = PluginObservation(target, Host(), original, 1, null)
        runtime.observer.after(call)
        assertEquals(1, original[0]); assertEquals(1, call.arguments[0]); scope.close()
    }
    @Test fun `a throwing hook callback leaves the host call untouched and never propagates`() {
        MediaDiag.resetForTest()
        val runtime = Runtime().also { MediaRuntime.context = it }
        val scope = PluginScope()
        val target = Host::class.java.getDeclaredMethod("value", Integer.TYPE)
        var calls = 0
        MediaRuntime.hookMethod(target, object : PluginMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                calls++
                param.args[0] = 99; param.result = 42   // partial changes before the failure
                error("broken host contract")
            }
        }, scope)
        scope.activate()
        val call = PluginCall(target, Host(), arrayOf(1), null, null)
        runtime.hook.before(call)
        assertEquals(1, call.arguments[0]); assertNull(call.result); assertFalse(call.isOutcomeChanged)
        assertTrue(runtime.logs.joinToString(), runtime.logs.any {
            it.startsWith("media-diag: [hook-failed]") && it.contains("${Host::class.java.name}.value(int)") && it.contains("phase=before")
        })
        // After MAX_CALLBACK_FAILURES the hook unregisters itself; the host keeps running natively.
        repeat(MediaRuntime.MAX_CALLBACK_FAILURES + 2) { runtime.hook.before(PluginCall(target, Host(), arrayOf(1), null, null)) }
        assertEquals(MediaRuntime.MAX_CALLBACK_FAILURES, calls)
        assertEquals(1, runtime.registrationsClosed)
        assertTrue(runtime.logs.any { it.startsWith("media-diag: [hook-disabled]") })
        scope.close()
    }
    @Test fun `a throwing observer is contained and reported`() {
        MediaDiag.resetForTest()
        val runtime = Runtime().also { MediaRuntime.context = it }
        val scope = PluginScope()
        val target = Host::class.java.getDeclaredMethod("value", Integer.TYPE)
        MediaDiag.capability = "Test capability"
        MediaRuntime.observeMethod(target, object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { throw NoSuchFieldException("a0") }
        }, scope)
        MediaDiag.capability = null
        scope.activate()
        runtime.observer.after(PluginObservation(target, Host(), arrayOf<Any>(1), 1, null))
        assertTrue(runtime.logs.joinToString(), runtime.logs.any { it.contains("[hook-failed] Test capability observe") && it.contains("NoSuchFieldException") })
        scope.close()
    }
    @Test fun `diagnostics deduplicate repeated failures`() {
        MediaDiag.resetForTest()
        val runtime = Runtime().also { MediaRuntime.context = it }
        repeat(10) { MediaDiag.event("contract", "same failure") }
        MediaDiag.event("contract", "always", key = null)
        assertEquals(3, runtime.logs.count { it.contains("same failure") })
        assertEquals(1, runtime.logs.count { it.contains("always") })
    }
}
