package dev.kifranei.ampp.media

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method

/** Keep Apple's typed DataBinding view; replace its indicator and picker contracts. */
internal class IosMediaControls(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
) : PlayerAudioOutputTarget {
    private var result: TargetCapabilityInstall? = null

    @Synchronized override fun install(): TargetCapabilityInstall {
        result?.let { return it }
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("audioOutput")
            ?: return TargetCapabilityInstall.Unsupported("No verified player audio-output contract for ${build.displayName}")
        val scope = PluginScope()
        return try {
            val type = loader.loadClass(names.getString("buttonClass"))
            val indicator = method(type, "setRemoteIndicatorDrawableInternal", Drawable::class.java)
            val attached = method(type, "onAttachedToWindow")
            val dialog = method(type, "showDialog")
            val click = method(type, "performClick")
            val description = method(type, "updateContentDescription")
            val id = application.resources.getIdentifier(names.getString("buttonId"), "id", build.packageName)
            val lyricsId = application.resources.getIdentifier(names.getString("lyricsId"), "id", build.packageName)
            check(id != 0 && lyricsId != 0) { "Player route control resources missing" }
            val icon = IosOutputDrawable.read(MediaRuntime.context)
            fun newIcon() = IosOutputDrawable(icon, application.resources.displayMetrics.density)
            fun owns(value: Any?): Boolean {
                val view = value as? View ?: return false
                return type.isInstance(view) && view.id == id &&
                    (view.parent as? ViewGroup)?.findViewById<View>(lyricsId) != null
            }
            fun hook(member: Method, callback: PluginMethodHook) {
                check(MediaRuntime.hookMethod(member, callback, scope))
            }
            hook(indicator, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (owns(param.thisObject)) param.args[0] = newIcon()
                }
            })
            hook(attached, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !owns(param.thisObject)) return
                    runCatching {
                        indicator.invoke(param.thisObject, newIcon())
                        (param.thisObject as View).contentDescription = "投放 · 音频输出"
                        MediaRuntime.log("player_audio_output: native route indicator replaced")
                    }.onFailure { MediaRuntime.log("player audio-output indicator failed", it) }
                }
            })
            val open = object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!owns(param.thisObject)) return
                    runCatching { PlatformAudioOutputSwitcher.open((param.thisObject as View).context) }
                        .onFailure { MediaRuntime.log("player audio-output picker failed", it) }
                    param.result = true
                }
            }
            hook(dialog, open)
            hook(click, open)
            hook(description, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (owns(param.thisObject)) (param.thisObject as View).contentDescription = "投放 · 音频输出"
                }
            })
            scope.activate()
            TargetCapabilityInstall.Active("Native route indicator and system output picker hooks installed")
                .also { result = it }
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        generateSequence(type as Class<*>?) { it.superclass }.firstNotNullOfOrNull {
            runCatching { it.getDeclaredMethod(name, *args).apply { isAccessible = true } }.getOrNull()
        } ?: throw NoSuchMethodException("${type.name}#$name")
}
