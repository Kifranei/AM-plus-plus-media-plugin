package dev.kifranei.ampp.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.sqrt

/** Hold one foreground frame while native SONG/QUEUE shared elements occupy different layouts. */
internal class TabletPaneTransitionSurface(private val root: ViewGroup,
    private val hide: (View) -> Unit, private val show: (View) -> Unit) {
    private var source: View? = null
    private var bitmap: Bitmap? = null
    private var frames = 0
    private var started = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private val point = IntArray(2)
    private val origin = IntArray(2)
    private val roundedIds = listOf("fullplayerSongImage", "button_shuffle", "button_repeat", "button_autoplay")
        .map { root.resources.getIdentifier(it, "id", root.context.packageName) }.filter { it != 0 }.toSet()
    private val surface = object : View(root.context) {
        override fun onDraw(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint) } }
    }.apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val active get() = bitmap != null
    init { root.addView(surface, ViewGroup.LayoutParams(1, 1)) }
    fun begin(left: View) {
        if (active || left.width <= 0 || left.height <= 0 || !left.isShown) return
        fun video(view: View): Boolean = view is TextureView && view.isShown && view.alpha > .01f ||
            view is ViewGroup && (0 until view.childCount).any { video(view.getChildAt(it)) }
        if (video(left)) return // TextureView pixels are owned by the video compositor.
        var captured: Bitmap? = null
        try {
            val scale = minOf(1.0, sqrt(2_000_000.0 / (left.width.toDouble() * left.height)))
            captured = Bitmap.createBitmap(ceil(left.width * scale).toInt(), ceil(left.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(captured)
            canvas.scale(scale.toFloat(), scale.toFloat())
            left.draw(canvas)
            applyNativeCorners(left, canvas)
            root.getLocationInWindow(origin); left.getLocationInWindow(point)
            destination.set(point[0] - origin[0], point[1] - origin[1], point[0] - origin[0] + left.width, point[1] - origin[1] + left.height)
            bitmap = captured; captured = null; source = left; frames = 0; started = SystemClock.uptimeMillis()
            hide(left)
            hold()
        } catch (_: RuntimeException) { captured?.recycle(); end() }
        catch (_: OutOfMemoryError) { captured?.recycle(); end() }
    }
    /** Software View.draw misses RenderNode outline clipping used by native MaterialCardViews. */
    private fun applyNativeCorners(left: View, canvas: Canvas) {
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val outline = Outline()
        val rect = Rect()
        val path = Path()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || view.alpha <= .01f) return
            if (view.id in roundedIds && view.clipToOutline) {
                outline.setEmpty()
                view.outlineProvider?.getOutline(view, outline)
                if (outline.getRect(rect) && !rect.isEmpty && outline.radius > 0f) {
                    val bounds = RectF(rect)
                    path.reset()
                    path.fillType = Path.FillType.EVEN_ODD
                    path.addRect(bounds, Path.Direction.CW)
                    path.addRoundRect(bounds, outline.radius, outline.radius, Path.Direction.CW)
                    canvas.drawPath(path, clear)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                val saved = canvas.save()
                canvas.translate((child.left - view.scrollX).toFloat(), (child.top - view.scrollY).toFloat())
                canvas.concat(child.matrix)
                visit(child)
                canvas.restoreToCount(saved)
            }
        }
        visit(left)
    }
    fun hold() {
        if (!active) return
        if (SystemClock.uptimeMillis() - started >= 750) { end(); return }
        frames++
        if (destination.left < 0 || destination.top < 0 || destination.right > root.width || destination.bottom > root.height) { end(); return }
        surface.measure(View.MeasureSpec.makeMeasureSpec(destination.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(destination.height(), View.MeasureSpec.EXACTLY))
        surface.layout(destination.left, destination.top, destination.right, destination.bottom)
        surface.visibility = View.VISIBLE
        root.postInvalidateOnAnimation()
    }
    fun ready(nativeReady: Boolean): Boolean = !active || TabletPaneTransitionGate.ready(frames, SystemClock.uptimeMillis() - started, nativeReady)
    fun end() {
        surface.visibility = View.INVISIBLE
        source?.let(show); source = null
        bitmap?.recycle(); bitmap = null; frames = 0
    }
    fun close() { end(); root.post { root.removeView(surface) } }
}

internal object TabletPaneTransitionGate {
    fun ready(frames: Int, elapsed: Long, nativeReady: Boolean): Boolean = frames >= 2 && nativeReady || elapsed >= 750
}
