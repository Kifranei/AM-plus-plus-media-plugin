package dev.kifranei.ampp.media

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/** One window snapshot per popup; screen origins align the two separate windows. */
class DialogWindowBackdrop(private val activity: Activity, private val consumer: View) : Backdrop, AutoCloseable {
    private val source = activity.window.decorView
    private val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    private var image by mutableStateOf<ImageBitmap?>(null)
    private var closed = false
    private var copying = false
    override val isCoordinatesDependent = true

    fun capture(ready: (Boolean) -> Unit) {
        check(!closed && !copying)
        copying = true
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                copying = false
                if (closed) { bitmap.recycle(); return@request }
                val success = result == PixelCopy.SUCCESS
                if (success) image = bitmap.asImageBitmap() else close()
                ready(success)
            }, Handler(Looper.getMainLooper()))
        } catch (error: Throwable) { copying = false; close(); throw error }
    }

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val captured = image ?: return
        if (closed || coordinates == null || !coordinates.isAttached) return
        val sourcePosition = IntArray(2).also(source::getLocationOnScreen)
        val targetPosition = IntArray(2).also(consumer.rootView::getLocationOnScreen)
        val origin = coordinates.localToWindow(Offset.Zero) + Offset(
            (targetPosition[0] - sourcePosition[0]).toFloat(), (targetPosition[1] - sourcePosition[1]).toFloat())
        drawImage(captured, topLeft = -origin)
    }

    override fun close() {
        if (closed) return
        closed = true
        image = null
        if (!copying) bitmap.recycle()
    }
}
