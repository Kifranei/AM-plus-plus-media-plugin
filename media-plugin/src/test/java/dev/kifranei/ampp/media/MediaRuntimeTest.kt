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
        override fun getHostVersionCode() = 1606L
        override fun getDataDirectory(): File = error("Not used")
        override fun getCacheDirectory(): File = error("Not used")
        override fun openAsset(path: String): InputStream = error("Not used")
        override fun log(message: String, error: Throwable?) = Unit
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
}
