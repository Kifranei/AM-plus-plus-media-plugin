package dev.kifranei.ampp.media

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import java.io.File
import java.lang.reflect.Executable

/** Extend the native picker and deliver its original image through Android's share sheet. */
internal class LyricsSharingIntegration(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
) : LyricsSharingTarget {
    override fun install(export: (LyricsShareImage, Boolean) -> Unit): TargetCapabilityInstall {
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("lyricsSharing")
            ?: return TargetCapabilityInstall.Unsupported("No verified lyrics-sharing contract for ${build.displayName}")
        val fragment = loader.loadClass(names.getString("fragmentClass"))
        val source = loader.loadClass(names.getString("sourceClass"))
        val option = loader.loadClass(names.getString("optionClass"))
        val item = loader.loadClass(names.getString("itemClass"))
        val viewModel = loader.loadClass(names.getString("viewModelClass"))
        val progress = loader.loadClass(names.getString("progressClass"))
        val observer = loader.loadClass(names.getString("observerClass"))
        val result = loader.loadClass(names.getString("resultClass"))
        val sourceList = source.getDeclaredField(names.getString("sourceListField")).apply { isAccessible = true }
        val optionId = item.getMethod("getId")
        val optionConstructor = option.getConstructor(java.lang.Long.TYPE, String::class.java, String::class.java,
            String::class.java, String::class.java)
        val viewCreated = fragment.getDeclaredMethod("onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
        val sourceConstructor = source.getConstructor(Context::class.java, String::class.java, java.util.HashSet::class.java)
        val setDetails = viewModel.getDeclaredMethod("setShareItemDetails", item, item, Array<String>::class.java, String::class.java)
        val owningProgress = observer.getDeclaredField(names.getString("observerProgressField")).apply { isAccessible = true }
        val progressOption = progress.getDeclaredField(names.getString("progressOptionField")).apply { isAccessible = true }
        val progressItem = progress.getDeclaredField(names.getString("progressItemField")).apply { isAccessible = true }
        val getTitle = item.getMethod("getTitle")
        val getActivity = progress.getMethod("getActivity")
        val dismiss = progress.getMethod("dismissAllowingStateLoss")
        val unwrap = result.getDeclaredMethod(names.getString("resultValueMethod"))
        val fileProvider = loader.loadClass(names.getString("fileProviderClass"))
            .getDeclaredMethod(names.getString("fileProviderMethod"), Context::class.java, File::class.java, String::class.java)
        val main = Handler(Looper.getMainLooper())
        val scope = PluginScope()
        val depth = ThreadLocal.withInitial { 0 }
        fun hook(member: Executable, callback: PluginMethodHook) {
            check(MediaRuntime.hookMethod(member, callback, scope))
        }
        fun customId(target: Any?): String? = target?.takeIf(item::isInstance)
            ?.let { optionId.invoke(it) as? String }?.takeIf { it == SHARE || it == SAVE }
        try {
            hook(viewCreated, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { depth.set((depth.get() ?: 0) + 1) }
                override fun afterHookedMethod(param: MethodHookParam) { depth.set(((depth.get() ?: 1) - 1).coerceAtLeast(0)) }
            })
            hook(sourceConstructor, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val filter = param.args.getOrNull(2) as? Set<*>
                    if (param.throwable != null || depth.get() == 0 || filter?.contains("com.instagram.android") != true) return
                    @Suppress("UNCHECKED_CAST")
                    val options = sourceList.get(param.thisObject) as MutableList<Any>
                    options.add(optionConstructor.newInstance(0L, SHARE, "分享图片", build.packageName, ""))
                    options.add(optionConstructor.newInstance(0L, SAVE, "保存歌词卡片", build.packageName, ""))
                    MediaRuntime.log("lyrics_sharing: system share and save actions added")
                }
            })
            hook(option.getMethod("getIconDrawable"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val icon = when (customId(param.thisObject)) {
                        SHARE -> android.R.drawable.ic_menu_share
                        SAVE -> android.R.drawable.ic_menu_save
                        else -> return
                    }
                    param.result = application.getDrawable(icon)?.mutate()
                }
            })
            hook(setDetails, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (customId(param.args.getOrNull(1)) == null) return
                    // AM chooses its social card template from this descriptor. Keep the
                    // progress fragment's original custom action for delivery, and replace
                    // only the renderer's input. No platform app is installed or launched.
                    param.args[1] = optionConstructor.newInstance(15000L, "instagram", "", "com.instagram.android", "")
                    MediaRuntime.log("lyrics_sharing: native lyric card generation requested")
                }
            })
            hook(observer.getDeclaredMethod("onChanged", Any::class.java), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = owningProgress.get(param.thisObject)
                    val id = customId(progressOption.get(owner)) ?: return
                    // This observer runs on the main thread after AM finishes generating
                    // the image. Suppress only our action's platform launch, including the
                    // native error result, and always close the native progress dialog.
                    param.result = null
                    val activity = getActivity.invoke(owner) as? Activity
                    val image = runCatching {
                        val intent = param.args.firstOrNull()?.let { unwrap.invoke(it) } as? Intent
                            ?: error("Native lyric card generation returned no image")
                        @Suppress("DEPRECATION")
                        // The story Intent's data URI is only the gradient background.
                        // Its interactive asset is the complete native lyric card.
                        val uri = intent.getParcelableExtra<Uri>(names.getString("cardUriExtra"))
                            ?: error("Native lyric card intent contains no card URI")
                        require(uri.scheme == "content") { "Native lyric image is not a content URI" }
                        val background = checkNotNull(intent.data) { "Native lyric story has no background URI" }
                        require(background.scheme == "content" && background != uri) { "Invalid native lyric background" }
                        val active = checkNotNull(activity)
                        val title = getTitle.invoke(progressItem.get(owner)) as? String ?: ""
                        LyricsShareImage(active, title, uri, background) { file ->
                            fileProvider.invoke(null, active, file, uri.authority) as Uri
                        }
                    }
                    runCatching { dismiss.invoke(owner) }
                        .onFailure { MediaRuntime.log("native lyric share progress dismissal failed", it) }
                    main.post {
                        image.onSuccess { card ->
                            if (!card.activity.isFinishing && !card.activity.isDestroyed) {
                                runCatching { export(card, id == SAVE) }.onFailure {
                                    MediaRuntime.log("native lyric image delivery failed", it)
                                    Toast.makeText(application, "歌词卡片导出失败，请重试", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.onFailure {
                            MediaRuntime.log("native lyric card generation failed", it)
                            Toast.makeText(application, "歌词卡片暂时无法生成，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
            scope.activate()
            return TargetCapabilityInstall.Active("Native Apple Music lyric cards support system sharing and saving")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private companion object {
        const val SHARE = "amppShareLyricsImage"
        const val SAVE = "amppSaveLyricsImage"
    }
}
