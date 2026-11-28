package dev.kifranei.ampp.media

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import java.util.SortedSet

/** Adds a "更多分享" button above "报告问题" in the native lyrics-share sheet. */
internal class LyricsMoreShareIntegration(private val build: TargetBuild, private val loader: ClassLoader) : PlayerShareCardTarget {
    @Volatile private var interceptNextShare = false
    @Volatile private var shareVm: Any? = null
    override fun install(): TargetCapabilityInstall {
        val type = runCatching {
            loader.loadClass("com.apple.android.music.common.actionsheet.lyrics.ShareLyricsActionSheetFragment")
        }.getOrNull() ?: return TargetCapabilityInstall.Unsupported("No lyrics share sheet for ${build.displayName}")
        val method = runCatching {
            type.getDeclaredMethod("onCreateView", android.view.LayoutInflater::class.java,
                ViewGroup::class.java, android.os.Bundle::class.java)
        }.getOrNull() ?: return TargetCapabilityInstall.Unsupported("No lyrics share view for ${build.displayName}")
        val scope = PluginScope()
        try {
            runCatching {
                val vmCls = loader.loadClass("com.apple.android.music.common.actionsheet.ShareSheetViewModel")
                MediaRuntime.observeMethod(vmCls.getConstructor(android.app.Application::class.java), object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { shareVm = param.thisObject }
                }, scope)
                vmCls.methods.firstOrNull { it.name == "setShareItemDetails" }?.let { m ->
                    MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { shareVm = param.thisObject }
                    }, scope)
                }
            }
            // The native lyrics share target generates the real card image and then
            // launches the chosen app. Intercept that launch once, keep the card URI
            // and show the system chooser instead.
            for (name in listOf("startActivity", "startActivityForResult")) {
                for (method in android.app.Activity::class.java.methods.filter { it.name == name }) {
                    runCatching {
                        MediaRuntime.observeMethod(method, object : PluginMethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!interceptNextShare) return
                                val intent = param.args.firstOrNull() as? Intent ?: return
                                if (intent.action != Intent.ACTION_SEND) return
                                val uri = @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                                    ?: intent.data ?: return
                                interceptNextShare = false
                                param.result = null
                                val context = (param.thisObject as? android.app.Activity) ?: return
                                main.post {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        setType(intent.type ?: "image/*")
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "分享歌词"))
                                }
                            }
                        }, scope)
                    }
                }
            }
            check(MediaRuntime.observeMethod(method, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.result as? View ?: return
                    if (param.throwable != null) return
                    root.post { addButton(root, param.thisObject) }
                }
            }, scope))
            scope.activate()
            return TargetCapabilityInstall.Active("Lyrics share sheet has a 更多分享 system-share button")
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun addButton(root: View, fragment: Any) {
        val context = root.context
        val report = findText(root, "报告问题") ?: findText(root, "Report a Problem") ?: return
        val density = root.resources.displayMetrics.density
        val label = TextView(context).apply {
            text = "更多分享"
            tag = "ampp_more_share"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F7")); cornerRadius = 18 * density
            }
            setOnClickListener {
                android.util.Log.e("AMPP_LY", "more-share clicked")
                val selected = selectedLyrics(fragment)
                if (selected.isEmpty()) {
                    Toast.makeText(context, "请先选择需要分享的歌词", Toast.LENGTH_SHORT).show()
                } else {
                    if (!shareViaNativeViewModel(root, fragment, selected)) {
                        FileLogger.log("lyrics_share: native generation failed; refusing black-card fallback")
                        Toast.makeText(context, "无法生成歌词分享图片", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val height = (48 * density).toInt()
        val parent = report.parent as? ViewGroup ?: root as? ViewGroup ?: return
        if (parent is android.widget.LinearLayout) {
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height)
            lp.setMargins((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            parent.addView(label, parent.indexOfChild(report), lp)
        } else {
            root as? ViewGroup ?: return
            root.addView(label, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            label.post {
                val loc = IntArray(2); report.getLocationInWindow(loc)
                val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
                label.y = (loc[1] - rootLoc[1] - height - 12 * density).coerceAtLeast(0f)
                label.x = 16 * density
                val lp = label.layoutParams as ViewGroup.LayoutParams
                lp.width = root.width - (32 * density).toInt()
                label.layoutParams = lp
            }
        }
        MediaRuntime.log("lyrics more-share button added")
        runCatching {
            val methods = fragment.javaClass.methods
                .filter { m -> listOf("share", "image", "card", "export", "lyric", "snapshot", "capture", "intent").any { m.name.contains(it, true) } }
                .joinToString(" | ") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
            FileLogger.log("lyrics_share_methods=$methods")
            val vm = fragment.javaClass.getMethod("h1").invoke(fragment)
            if (vm != null) {
                val vmMethods = vm.javaClass.methods
                    .filter { m -> listOf("share", "image", "card", "export", "intent", "lyric", "snapshot").any { m.name.contains(it, true) } }
                    .joinToString(" | ") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
                FileLogger.log("lyrics_vm_methods=$vmMethods")
            }
        }
    }

    private fun selectedLyrics(fragment: Any): List<String> = runCatching {
        val vm = fragment.javaClass.getMethod("h1").invoke(fragment) ?: return emptyList()
        val set = vm.javaClass.getMethod("getSelectedLyricLines").invoke(vm) as? SortedSet<*> ?: return emptyList()
        set.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val text = runCatching {
                item.javaClass.methods.firstOrNull { it.name in setOf("getText", "getLine", "getContent", "toString") && it.parameterCount == 0 }
                    ?.invoke(item)?.toString()
            }.getOrNull()
            text?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Upstream 1.3.4 path: the native ShareProgress fragment (obfuscated `common.actionsheet.S`). */
    private fun startNativeProgress(fragment: Any, vm: Any, item: Any, option: Any, lyrics: List<String>, url: String): Boolean =
        runCatching {
            val cls = loader.loadClass("com.apple.android.music.common.actionsheet.S")
            val progress = cls.getConstructor().newInstance()
            val observable = vm.javaClass.getMethod("getIntentObservable").invoke(vm)
            cls.getDeclaredField("b").apply { isAccessible = true }.set(progress, observable)
            cls.getDeclaredField("c").apply { isAccessible = true }.set(progress, item)
            cls.getDeclaredField("d").apply { isAccessible = true }.set(progress, option)
            cls.getDeclaredField("e").apply { isAccessible = true }.set(progress, lyrics.toTypedArray())
            cls.getDeclaredField("f").apply { isAccessible = true }.set(progress, url)
            cls.getDeclaredField("g").apply { isAccessible = true }.set(progress, false)
            val fm = fragment.javaClass.getMethod("getParentFragmentManager").invoke(fragment)
            val tx = fm.javaClass.getMethod("beginTransaction").invoke(fm)
            val add = tx.javaClass.methods.first { it.name == "add" && it.parameterCount == 2 && it.parameterTypes[0].isInstance(progress) }
            add.invoke(tx, progress, "ampp_lyrics_share_progress")
            tx.javaClass.getMethod("commitAllowingStateLoss").invoke(tx)
            FileLogger.log("lyrics_share: native progress fragment added")
            true
        }.getOrElse { FileLogger.log("lyrics_share: progress fragment failed ${it.javaClass.name}: ${it.message}"); false }

    private fun awaitNativeResult(vm: Any, fragment: Any, context: android.content.Context) {
        val observable = runCatching { vm.javaClass.getMethod("getIntentObservable").invoke(vm) }.getOrNull() ?: return
        fun deliver(value: Any?) {
            val intent = when (value) {
                is android.content.Intent -> value
                null -> null
                else -> runCatching {
                    if (value is Pair<*, *>) value.second as? android.content.Intent
                    else value.javaClass.declaredFields.firstOrNull { android.content.Intent::class.java.isAssignableFrom(it.type) }
                        ?.apply { isAccessible = true }?.get(value) as? android.content.Intent
                }.getOrNull()
            }
            if (intent == null || intent.action != android.content.Intent.ACTION_SEND) return
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) ?: intent.data ?: return
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                setType(intent.type ?: "image/*")
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(send, "分享歌词").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            FileLogger.log("lyrics_share: native card delivered uri=$uri")
        }
        runCatching {
            // Obfuscated AndroidX: Observer=androidx.lifecycle.K, owner=fragment.viewLifecycleOwner.
            val iface = Class.forName("androidx.lifecycle.K", false, observable.javaClass.classLoader)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, _, args ->
                if (args != null && args.isNotEmpty()) deliver(args[0])
                null
            }
            val owner = fragment.javaClass.getMethod("getViewLifecycleOwner").invoke(fragment)
            val observe = observable.javaClass.methods.firstOrNull { it.name == "observe" && it.parameterCount == 2 }
                ?: error("observe not found")
            observe.invoke(observable, owner, proxy)
            FileLogger.log("lyrics_share: native observer registered")
        }.onFailure { FileLogger.log("lyrics_share: observer registration failed ${it}") }
        // Fallback: poll the observable for hosts that expose getValue only.
        val getValue = observable.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 } ?: return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        lateinit var poll: Runnable
        poll = Runnable {
            tries++
            deliver(runCatching { getValue.invoke(observable) }.getOrNull())
            if (tries < 30) handler.postDelayed(poll, 200)
            else FileLogger.log("lyrics_share: native result timeout")
        }
        handler.post(poll)
    }

    private fun shareViaNativeViewModel(root: View, fragment: Any, lyrics: List<String>): Boolean {
        var vm = shareVm
        if (vm == null) {
            val activity = (root.context as? android.app.Activity) ?: run {
                var c: android.content.Context? = root.context
                while (c is android.content.ContextWrapper) { if (c is android.app.Activity) break; c = c.baseContext }
                c as? android.app.Activity
            } ?: return false
            vm = runCatching {
                val providerCls = sequenceOf(activity.classLoader, LyricsMoreShareIntegration::class.java.classLoader)
                    .mapNotNull { cl -> runCatching { Class.forName("androidx.lifecycle.ViewModelProvider", false, cl) }.getOrNull() }
                    .firstOrNull() ?: error("ViewModelProvider not found")
                val ctor = providerCls.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInstance(activity) }
                    ?: error("no ViewModelProvider constructor for Activity")
                val provider = ctor.newInstance(activity)
                val modelCls = loader.loadClass("com.apple.android.music.common.actionsheet.ShareSheetViewModel")
                providerCls.getMethod("get", Class::class.java).invoke(provider, modelCls)
            }.recoverCatching {
                FileLogger.log("lyrics_share: ViewModelProvider failed, construct directly ${it}")
                val modelCls = loader.loadClass("com.apple.android.music.common.actionsheet.ShareSheetViewModel")
                modelCls.getConstructor(android.app.Application::class.java)
                    .newInstance(activity.application)
            }.onFailure { FileLogger.log("lyrics_share: cannot obtain ShareSheetViewModel ${it}") }.getOrNull()
            if (vm == null) return false
            shareVm = vm
        }
        return runCatching {
            var item = runCatching { vm.javaClass.getMethod("getItemToShare").invoke(vm) }.getOrNull()
            if (item == null) {
                // ShareLyricsActionSheetFragment.L holds the CollectionItemView being shared.
                for (fieldName in listOf("L", "l")) {
                    item = runCatching {
                        fragment.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(fragment)
                    }.getOrNull()
                    if (item != null) break
                }
            }
            val itemObj = item ?: error("no itemToShare")
            runCatching { vm.javaClass.methods.first { it.name == "setItemToShare" && it.parameterCount == 1 }.invoke(vm, itemObj) }
            var option = runCatching { vm.javaClass.getMethod("getShareOptionItem").invoke(vm) }.getOrNull()
            if (option == null) option = itemObj
            val url = runCatching { vm.javaClass.getDeclaredField("mShareLyricsUrl").apply { isAccessible = true }.get(vm) as? String }.getOrNull() ?: ""
            val setDetails = vm.javaClass.methods.first { it.name == "setShareItemDetails" && it.parameterCount == 4 }
            setDetails.invoke(vm, itemObj, option, lyrics.toTypedArray(), url)
            runCatching { vm.javaClass.getMethod("subscribeToSharePlatformIntent").invoke(vm) }
            FileLogger.log("lyrics_share: native viewmodel generation requested lines=${lyrics.size}")
            startNativeProgress(fragment, vm, itemObj, option, lyrics, url)
            true
        }.getOrElse {
            val root = if (it is java.lang.reflect.InvocationTargetException) it.targetException else it
            FileLogger.log("lyrics_share: native viewmodel failed cause=${root.javaClass.name}: ${root.message}")
            false
        }
    }

    /** Click a native share target (it renders AM's real lyrics card) and intercept its launch. */
    private fun triggerNativeShare(root: View): Boolean {
        val target = findShareTarget(root)
        android.util.Log.e("AMPP_LY", "target=${target?.javaClass?.name} desc=${target?.contentDescription}")
        if (target == null) return false
        interceptNextShare = true
        return runCatching { target.performClick(); true }.getOrDefault(false).also {
            if (!it) interceptNextShare = false
        }
    }

    private fun findShareTarget(view: View): View? {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findShareTarget(view.getChildAt(i))?.let { return it }
        }
        val desc = view.contentDescription?.toString().orEmpty()
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val label = "$desc $text"
        android.util.Log.e("AMPP_LY", "candidate ${view.javaClass.simpleName} label=$label clickable=${view.isClickable}")
        val isShareTarget = label.contains("Instagram", true) || label.contains("分享", true) || label.contains("Share", true)
        if (view.tag == "ampp_more_share") return null
        if (!isShareTarget) return null
        var candidate: View? = view
        while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
        return candidate
    }

    private fun shareLyrics(root: View, fragment: Any, lyrics: List<String>) {
        runCatching {
            val context = root.context
            val width = 1080
            val height = 1350
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(18, 18, 20))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 52f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            var y = 240f
            for (line in lyrics.take(12)) {
                for (part in wrap(line, paint, width - 180f)) {
                    canvas.drawText(part, 90f, y, paint)
                    y += 78f
                }
                y += 18f
            }
            paint.textSize = 34f
            paint.color = Color.LTGRAY
            canvas.drawText("Apple Music", 90f, height - 90f, paint)
            val uri = save(bitmap, context)
            val send = Intent(Intent.ACTION_SEND).apply {
                setType("image/png")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享歌词").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            MediaRuntime.log("lyrics card share failed", it)
            Toast.makeText(root.context, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wrap(text: String, paint: Paint, max: Float): List<String> {
        val out = ArrayList<String>(); var current = ""
        for (ch in text) {
            val next = current + ch
            if (paint.measureText(next) > max && current.isNotEmpty()) { out.add(current); current = ch.toString() } else current = next
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun save(bitmap: Bitmap, context: android.content.Context): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ampp-lyrics-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AM++")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("insert failed")
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: error("write failed")
        return uri
    }

    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text?.toString()?.contains(text) == true) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        return null
    }
}
