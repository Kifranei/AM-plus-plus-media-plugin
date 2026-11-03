package dev.kifranei.ampp.media

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * Desktop / floating lyrics overlay adapted from Halcyon's DesktopLyricService + word karaoke
 * progress (AppleMusicKaraokeText.karaokeProgress / fill clip).
 *
 * No host Service can be registered from an LSPosed plugin ZIP, so this uses WindowManager
 * directly (TYPE_APPLICATION_OVERLAY) while Apple Music holds SYSTEM_ALERT_WINDOW.
 */
internal class DesktopLyricOverlay(private val context: Context) : LiveLyricSink {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: KaraokeLyricView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    @Volatile private var enabled = false
    private var lastLineKey: String? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            hide()
            return
        }
        if (!canDrawOverlay()) {
            MediaRuntime.log("desktop lyrics: overlay permission missing for ${context.packageName}", null)
            main.post {
                Toast.makeText(
                    context,
                    if (isChinese()) "请为 Apple Music 开启「显示在其他应用上层」以使用桌面歌词"
                    else "Grant “Display over other apps” to Apple Music for desktop lyrics",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching { openOverlaySettings() }
            }
            return
        }
        ensureWindow()
    }

    fun isEnabled() = enabled

    override fun onLyric(song: LiveLyricSongMeta?, line: LiveLyricLine?, positionMs: Long, playing: Boolean) {
        if (!enabled) return
        if (!canDrawOverlay()) return
        if (!playing || line == null) {
            root?.setPlaybackActive(false)
            return
        }
        ensureWindow()
        val key = "${line.beginMs}:${line.text}:${line.secondary.orEmpty()}"
        val view = root ?: return
        if (key != lastLineKey) {
            lastLineKey = key
            view.setLine(line)
        }
        view.setPlaybackActive(true)
        view.setPosition(positionMs)
    }

    fun clearLyric() {
        lastLineKey = null
        root?.setLine(null)
        root?.setPlaybackActive(false)
    }

    fun hide() {
        lastLineKey = null
        val view = root ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        root = null
        layoutParams = null
    }

    fun destroy() {
        enabled = false
        hide()
        main.removeCallbacksAndMessages(null)
    }

    private fun ensureWindow() {
        if (root != null || !canDrawOverlay()) return
        try {
            val density = context.resources.displayMetrics
            val width = (density.widthPixels * 0.86f).toInt().coerceAtLeast(200)
            val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 96f, density).toInt()
            val view = KaraokeLyricView(context)
            val params = WindowManager.LayoutParams(
                width,
                height,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (density.heightPixels * 0.12f).toInt()
                title = "AM++ Desktop Lyrics"
            }
            view.setOnTouchListener { _, event -> handleTouch(view, params, event) }
            windowManager.addView(view, params)
            root = view
            layoutParams = params
        } catch (error: Throwable) {
            MediaRuntime.log("desktop lyrics window failed", error)
            root = null
            layoutParams = null
        }
    }

    private fun handleTouch(view: View, params: WindowManager.LayoutParams, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = startX + (event.rawX - downX).toInt()
                params.y = startY + (event.rawY - downY).toInt()
                runCatching { windowManager.updateViewLayout(view, params) }
                return true
            }
        }
        return false
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun isChinese(): Boolean =
        context.resources.configuration.locales[0].language.equals("zh", ignoreCase = true)
}

/** Canvas karaoke line: dim full text + bright clipped fill by word timing (Halcyon-style). */
internal class KaraokeLyricView(context: Context) : View(context) {
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.36f * 255).toInt(), 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0))
    }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 0f, 1f, Color.argb(200, 0, 0, 0))
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 220, 220, 220)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private var line: LiveLyricLine? = null
    private var positionMs = 0L
    private var playing = true
    private val clip = Rect()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
        dimPaint.textSize = sp
        litPaint.textSize = sp
        secondaryPaint.textSize = sp * 0.72f
    }

    fun setLine(value: LiveLyricLine?) {
        line = value
        invalidate()
    }

    fun setPosition(ms: Long) {
        positionMs = ms
        invalidate()
    }

    fun setPlaybackActive(active: Boolean) {
        playing = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = line ?: return
        if (!playing && current.text.isBlank()) return
        val cx = width / 2f
        val primaryY = height * 0.42f
        val text = current.text
        val progress = if (playing) {
            LiveLyricIndexer.fillProgress(current.words, positionMs, current.beginMs, current.endMs)
        } else 1f
        canvas.drawText(text, cx, primaryY, dimPaint)
        val textWidth = litPaint.measureText(text)
        val left = cx - textWidth / 2f
        val right = left + textWidth * progress
        clip.set(left.toInt(), 0, right.toInt().coerceAtLeast(left.toInt()), height)
        canvas.save()
        canvas.clipRect(clip)
        canvas.drawText(text, cx, primaryY, litPaint)
        canvas.restore()
        val secondary = current.secondary
        if (!secondary.isNullOrBlank()) {
            canvas.drawText(secondary, cx, primaryY + secondaryPaint.textSize * 1.35f, secondaryPaint)
        }
    }
}
