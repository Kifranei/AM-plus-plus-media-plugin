package dev.kifranei.ampp.media

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/**
 * 原地修改音质 TextView：
 *  - 不新增任何图层/浮层，图标和布局保持原样；
 *  - 开始时原文字淡出 → 换成「智能混音」淡入；
 *  - 结束时反向；
 *  - 过渡期间宿主刷回音质文字会被拦截并改回「智能混音」。
 */
internal object AutoTransitionProgressBar {
    private val main = Handler(Looper.getMainLooper())
    private val badgeViews = WeakHashMap<TextView, String>()
    private val badgeColors = WeakHashMap<TextView, Int>()
    private val badgeBackgrounds = WeakHashMap<TextView, Drawable?>()
    private val badgeSpans = WeakHashMap<TextView, SpannableStringBuilder>()
    private val pendingVisibility = WeakHashMap<View, Int>()
    private val seekBars = WeakHashMap<View, Float>()
    private val glowOverlays = WeakHashMap<View, GlowOverlayView>()
    private val glowRoots = WeakHashMap<View, View>()

    @Volatile private var active = false
    @Volatile private var visualActive = false
    @Volatile private var ownText = false
    @Volatile private var activationStartedMs = 0L
    private var cachedRootView: View? = null
    private var clock: ValueAnimator? = null
    private val labelText = "智能混音"

    private val BADGE_KEYWORDS = listOf(
        "无损", "杜比", "Hi-Res", "Lossless", "Dolby", "Atmos", "空间音频", "Spatial",
        "母带", "高解析", "Digital Master", "Apple Digital",
    )

    private fun isAudioBadge(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return BADGE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    fun install(scope: PluginScope, packageName: String): Boolean {
        runCatching {
            val onResume = android.app.Activity::class.java.getDeclaredMethod("onResume")
            MediaRuntime.observeMethod(onResume, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val activity = param.thisObject as? android.app.Activity ?: return
                    main.post { cachedRootView = activity.window?.decorView }
                }
            }, scope)
        }
        runCatching {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText", CharSequence::class.java, TextView.BufferType::class.java,
            )
            MediaRuntime.hookMethod(setText, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val tv = param.thisObject as? TextView ?: return
                    val text = param.args[0]?.toString()
                    if (text.isNullOrBlank() || !isAudioBadge(text)) return
                    if (!badgeViews.containsKey(tv)) badgeViews[tv] = text
                    if (!active || ownText) return
                    // 过渡期间宿主又把音质文字刷回来：直接拦掉，并保持「智能混音」。
                    param.result = null
                    main.post {
                        runCatching {
                            if (!badgeSpans.containsKey(tv)) {
                                val sb = SpannableStringBuilder(labelText)
                                updateBadgeSpan(sb, 0L)
                                badgeSpans[tv] = sb
                            }
                            val sb = badgeSpans[tv]
                            if (sb != null) {
                                ownText = true
                                tv.text = sb
                                ownText = false
                            }
                        }
                    }
                }
            }, scope)
        }
        runCatching {
            val onAttach = View::class.java.getDeclaredMethod("onAttachedToWindow")
            MediaRuntime.observeMethod(onAttach, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val view = param.thisObject as? View ?: return
                    main.post {
                        if (view is TextView && isAudioBadge(view.text?.toString())) {
                            if (!badgeViews.containsKey(view)) badgeViews[view] = view.text?.toString() ?: ""
                        }
                        if (view.javaClass.name == "com.apple.android.music.common.MinimalSeekBar") {
                            seekBars[view] = view.elevation
                        }
                    }
                }
            }, scope)
        }
        runCatching {
            val setVis = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
            MediaRuntime.hookMethod(setVis, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!active) return
                    val v = param.thisObject as? View ?: return
                    if (!isBadgeTarget(v)) return
                    val target = param.args[0] as? Int ?: return
                    if (target != View.VISIBLE) {
                        pendingVisibility[v] = target
                        param.result = null
                    }
                }
            }, scope)
        }
        return true
    }

    private fun isBadgeTarget(v: View): Boolean {
        for (badge in badgeViews.keys) {
            if (v === badge || v === badge.parent) return true
        }
        return false
    }

    fun setBpm(bpm: Float?) = Unit
    fun setRootView(root: View?) { if (root != null) cachedRootView = root }

    fun setTransitionActive(active: Boolean) {
        this.active = active
        main.post {
            if (active) {
                if (visualActive) return@post
                visualActive = true
                val root = cachedRootView
                if (root != null) findViewsInTree(root)
                val targets = badgeViews.keys.filter { it.isShown }
                val list = if (targets.isEmpty()) badgeViews.keys.toList() else targets
                list.forEach { applyBadgeTransition(it) }
                val bars = seekBars.keys.filter { it.isShown && it.width > 0 }
                bars.maxByOrNull { it.width }?.let { applyBarGlow(it) }
                activationStartedMs = SystemClock.elapsedRealtime()
                startClock()
            } else {
                visualActive = false
                stopClock()
                restoreBadges()
            }
        }
    }

    private fun findViewsInTree(root: View?) {
        if (root == null) return
        if (root is TextView && isAudioBadge(root.text?.toString())) {
            if (!badgeViews.containsKey(root)) badgeViews[root] = root.text?.toString() ?: ""
        }
        if (root.javaClass.name == "com.apple.android.music.common.MinimalSeekBar") {
            if (!seekBars.containsKey(root)) seekBars[root] = root.elevation
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) findViewsInTree(root.getChildAt(i))
    }

    private fun applyBadgeTransition(badge: TextView) {
        runCatching {
            if (badgeSpans.containsKey(badge)) return@runCatching
            if (!badgeColors.containsKey(badge)) badgeColors[badge] = badge.currentTextColor
            if (!badgeBackgrounds.containsKey(badge)) badgeBackgrounds[badge] = badge.background

            val out = ValueAnimator.ofFloat(1f, 0f)
            out.duration = 220L
            out.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
            out.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    runCatching {
                        val sb = SpannableStringBuilder(labelText)
                        updateBadgeSpan(sb, 0L)
                        badgeSpans[badge] = sb
                        ownText = true
                        badge.background = null
                        badge.backgroundTintList = null
                        badge.setTextColor(Color.WHITE)
                        badge.text = sb
                        ownText = false
                        val inAnim = ValueAnimator.ofFloat(0f, 1f)
                        inAnim.duration = 360L
                        inAnim.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
                        inAnim.start()
                    }
                }
            })
            out.start()
        }
    }

    private fun updateBadgeSpan(span: SpannableStringBuilder, elapsedMs: Long) {
        val cycle = 2200L
        val waveT = elapsedMs.coerceAtLeast(0L) % cycle
        for (i in 0 until span.length) {
            val local = (waveT - i * 90L + cycle) % cycle
            val alpha = when {
                local < 620L -> 255
                local < 870L -> (255 - (local - 620L) * 95L / 250L).toInt()
                local < 1580L -> 160
                local < 1830L -> (160 + (local - 1580L) * 95L / 250L).toInt()
                else -> 255
            }
            for (old in span.getSpans(i, i + 1, ForegroundColorSpan::class.java)) span.removeSpan(old)
            span.setSpan(ForegroundColorSpan(Color.argb(alpha.coerceIn(0, 255), 255, 255, 255)),
                i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun startClock() {
        if (clock != null) return
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val elapsed = (SystemClock.elapsedRealtime() - activationStartedMs - 600L).coerceAtLeast(0L)
                for (badge in badgeViews.keys) {
                    runCatching {
                        if (badge.text?.toString() != labelText) return@runCatching
                        val sb = badgeSpans[badge] ?: return@runCatching
                        updateBadgeSpan(sb, elapsed)
                        badge.invalidate()
                    }
                }
                for ((bar, ov) in glowOverlays) {
                    runCatching {
                        ov.visibility = if (bar.isShown) View.VISIBLE else View.GONE
                        val root = glowRoots[bar]
                        if (bar.isShown && root != null) {
                            val bl = IntArray(2); bar.getLocationOnScreen(bl)
                            val rl = IntArray(2); root.getLocationOnScreen(rl)
                            ov.x = (bl[0] - rl[0] - ov.spill).toFloat()
                            ov.y = (bl[1] - rl[1] - ov.spill).toFloat()
                        }
                        ov.invalidate()
                    }
                }
            }
            start()
        }
    }

    private fun stopClock() {
        clock?.cancel()
        clock = null
    }

    private fun applyBarGlow(bar: View) {
        runCatching {
            if (bar.width <= 0 || bar.height <= 0) {
                bar.post { applyBarGlow(bar) }
                return@runCatching
            }
            val root = bar.rootView as? ViewGroup ?: return@runCatching
            glowOverlays[bar]?.let { root.removeView(it) }
            val spill = (10f * bar.resources.displayMetrics.density).toInt().coerceAtLeast(8)
            val overlay = GlowOverlayView(bar.context, bar).apply {
                layoutParams = ViewGroup.LayoutParams(bar.width + spill * 2, bar.height + spill * 2)
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            // 挂到根 DecorView，不参与播放页布局，避免把下方音量条挤走/隐藏。
            root.addView(overlay)
            glowRoots[bar] = root
            val barLoc = IntArray(2); bar.getLocationOnScreen(barLoc)
            val rootLoc = IntArray(2); root.getLocationOnScreen(rootLoc)
            overlay.x = (barLoc[0] - rootLoc[0] - spill).toFloat()
            overlay.y = (barLoc[1] - rootLoc[1] - spill).toFloat()
            glowOverlays[bar] = overlay
        }
    }

    private fun restoreBadges() {
        for (badge in badgeViews.keys) {
            runCatching {
                val originalText = badgeViews[badge] ?: ""
                val originalColor = badgeColors[badge] ?: Color.WHITE
                val originalBg = badgeBackgrounds[badge]
                val out = ValueAnimator.ofFloat(1f, 0f)
                out.duration = 220L
                out.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
                out.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        runCatching {
                            ownText = true
                            badge.text = originalText
                            ownText = false
                            badge.setTextColor(originalColor)
                            badge.background = originalBg
                            badgeSpans.remove(badge)
                            val inAnim = ValueAnimator.ofFloat(0f, 1f)
                            inAnim.duration = 300L
                            inAnim.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
                            inAnim.start()
                            main.postDelayed({ runCatching { badge.visibility = View.VISIBLE; badge.alpha = 1f } }, 650L)
                        }
                    }
                })
                out.start()
            }
        }
        for ((bar, ov) in glowOverlays) runCatching { (ov.parent as? ViewGroup)?.removeView(ov) }
        glowOverlays.clear()
        glowRoots.clear()
        for ((v, vis) in pendingVisibility) runCatching { v.visibility = vis }
        pendingVisibility.clear()
    }
    private class GlowOverlayView(context: android.content.Context, private val bar: View) : View(context) {
        val spill: Int = (10f * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bitmap: android.graphics.Bitmap? = runCatching {
            android.graphics.Bitmap.createBitmap(bar.width.coerceAtLeast(1), bar.height.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull()

        private fun sampleColor(bmp: android.graphics.Bitmap): Int {
            val y = (bmp.height / 2).coerceIn(0, bmp.height - 1)
            for (px in 0 until bmp.width step 3) {
                val c = bmp.getPixel(px, y)
                if (Color.alpha(c) >= 24) {
                    return Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
                }
            }
            return Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val bmp = bitmap ?: return
            bmp.eraseColor(Color.TRANSPARENT)
            bar.draw(Canvas(bmp))
            val d = resources.displayMetrics.density
            val frac = runCatching {
                val p = bar as? android.widget.ProgressBar ?: return@runCatching 1f
                p.progress.toFloat() / p.max.coerceAtLeast(1).toFloat()
            }.getOrDefault(1f).coerceIn(0f, 1f)
            if (frac <= 0.005f) return
            val color = sampleColor(bmp)
            val w = width.toFloat(); val h = height.toFloat()
            val rect = android.graphics.RectF(spill.toFloat(), spill.toFloat(), w - spill, h - spill)
            val cy = h / 2f
            val left = spill.toFloat()
            val right = spill + (w - spill * 2) * frac
            // 沿已播放段画一条发光横线：不做矩形裁剪，避免起点出现竖着的白色胶囊。
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = color
            paint.strokeWidth = rect.height() * 0.95f
            paint.alpha = 110
            paint.maskFilter = android.graphics.BlurMaskFilter(3.0f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            canvas.drawLine(left, cy, right, cy, paint)
            paint.strokeWidth = rect.height() * 0.55f
            paint.alpha = 160
            paint.maskFilter = android.graphics.BlurMaskFilter(1.4f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            canvas.drawLine(left, cy, right, cy, paint)
            paint.maskFilter = null
            paint.strokeCap = Paint.Cap.BUTT
        }
    }
}

