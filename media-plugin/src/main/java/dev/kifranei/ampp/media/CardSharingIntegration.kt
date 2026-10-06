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
internal class CardSharingIntegration(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
) : CardSharingTarget {
    override fun install(export: (NativeShareImage, Boolean) -> Unit): TargetCapabilityInstall {
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("cardSharing")
            ?: return TargetCapabilityInstall.Unsupported("No verified card-sharing contract for ${build.displayName}")
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
        val contentType = item.getMethod("getContentType")
        val songTypes = names.getJSONArray("songContentTypes").let { types ->
            (0 until types.length()).map(types::getInt).toSet()
        }
        val songItem = PluginProfiles.field("song-sharing-item")
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
        val contexts = ThreadLocal.withInitial { ArrayList<SharedCardKind?>() }
        fun creationStack() = checkNotNull(contexts.get())
        scope.onClose { contexts.remove(); main.removeCallbacksAndMessages(null) }
        fun hook(member: Executable, callback: PluginMethodHook) {
            check(MediaRuntime.hookMethod(member, callback, scope))
        }
        fun customAction(target: Any?) = target?.takeIf(item::isInstance)
            ?.let { SharedCardAction.fromId(optionId.invoke(it) as? String) }
        fun trackCreation(member: Executable, kind: (Any?) -> SharedCardKind?) {
            hook(member, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    creationStack().add(runCatching { kind(param.thisObject) }.getOrElse {
                        MediaRuntime.log("card_sharing: share sheet classification failed", it); null
                    })
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = creationStack()
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    if (stack.isEmpty()) contexts.remove()
                }
            })
        }
        try {
            trackCreation(viewCreated) { SharedCardKind.LYRICS }
            trackCreation(PluginProfiles.method("song-sharing-create-view")) { owner ->
                val target = songItem.get(owner)
                if (item.isInstance(target) && (contentType.invoke(target) as Int) in songTypes) SharedCardKind.SONG else null
            }
            hook(sourceConstructor, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val filter = param.args.getOrNull(2) as? Set<*>
                    val kind = creationStack().lastOrNull() ?: return
                    if (param.throwable != null || (kind == SharedCardKind.LYRICS && filter?.contains("com.instagram.android") != true)) return
                    @Suppress("UNCHECKED_CAST")
                    val options = sourceList.get(param.thisObject) as MutableList<Any>
                    val context = param.args.firstOrNull() as? Context ?: application
                    val language = context.resources.configuration.locales[0].language
                    val existing = options.map { optionId.invoke(it) as? String }.toSet()
                    listOf(SharedCardAction(kind, false), SharedCardAction(kind, true)).forEach { action ->
                        if (action.id !in existing) options.add(optionConstructor.newInstance(0L, action.id,
                            if (action.save) kind.saveLabel(language) else if (language == "zh") "分享图片" else "Share image",
                            build.packageName, ""))
                    }
                    MediaRuntime.log("card_sharing: system share and save actions added to native ${kind.filenameTag} app row")
                }
            })
            hook(option.getMethod("getIconDrawable"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val action = customAction(param.thisObject) ?: return
                    val icon = if (action.save) android.R.drawable.ic_menu_save else android.R.drawable.ic_menu_share
                    param.result = application.getDrawable(icon)?.mutate()
                }
            })
            hook(setDetails, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val action = customAction(param.args.getOrNull(1)) ?: return
                    // AM chooses its social card template from this descriptor. Keep the
                    // progress fragment's original custom action for delivery, and replace
                    // only the renderer's input. No platform app is installed or launched.
                    param.args[1] = optionConstructor.newInstance(15000L, "instagram", "", "com.instagram.android", "")
                    MediaRuntime.log("card_sharing: native ${action.kind.filenameTag} card generation requested")
                }
            })
            hook(observer.getDeclaredMethod("onChanged", Any::class.java), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = owningProgress.get(param.thisObject)
                    val action = customAction(progressOption.get(owner)) ?: return
                    // This observer runs on the main thread after AM finishes generating
                    // the image. Suppress only our action's platform launch, including the
                    // native error result, and always close the native progress dialog.
                    param.result = null
                    val activity = getActivity.invoke(owner) as? Activity
                    val image = runCatching {
                        val intent = param.args.firstOrNull()?.let { unwrap.invoke(it) } as? Intent
                            ?: error("Native card generation returned no image")
                        @Suppress("DEPRECATION")
                        // The story Intent's data URI is only the gradient background.
                        // Its interactive asset is the complete native song/lyrics card.
                        val uri = intent.getParcelableExtra<Uri>(names.getString("cardUriExtra"))
                            ?: error("Native card intent contains no card URI")
                        require(uri.scheme == "content") { "Native card image is not a content URI" }
                        val background = checkNotNull(intent.data) { "Native story has no background URI" }
                        require(background.scheme == "content" && background != uri) { "Invalid native card background" }
                        val active = checkNotNull(activity)
                        val title = getTitle.invoke(progressItem.get(owner)) as? String ?: ""
                        NativeShareImage(active, title, uri, background, action.kind) { file ->
                            fileProvider.invoke(null, active, file, uri.authority) as Uri
                        }
                    }
                    runCatching { dismiss.invoke(owner) }
                        .onFailure { MediaRuntime.log("native card share progress dismissal failed", it) }
                    main.post {
                        image.onSuccess { card ->
                            if (!card.activity.isFinishing && !card.activity.isDestroyed) {
                                runCatching { export(card, action.save) }.onFailure {
                                    MediaRuntime.log("native card image delivery failed", it)
                                    Toast.makeText(application, action.kind.failureMessage(application.resources.configuration.locales[0].language), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.onFailure {
                            MediaRuntime.log("native ${action.kind.filenameTag} card generation failed", it)
                            Toast.makeText(application, action.kind.failureMessage(application.resources.configuration.locales[0].language, generation = true), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
            scope.activate()
            return TargetCapabilityInstall.Active("Native Apple Music song and lyrics cards support system sharing and saving")
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
