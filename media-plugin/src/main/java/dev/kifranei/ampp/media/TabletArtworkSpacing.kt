package dev.kifranei.ampp.media

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import kotlin.math.roundToInt

/** Resting artwork translation only; never resize or multiply the native playback scale. */
internal class TabletArtworkSpacing(private val root: ViewGroup) {
    private fun id(name: String) = root.resources.getIdentifier(name, "id", root.context.packageName)
    private val artworkId = id("artwork_container")
    private val cardId = id("fullplayerSongImage")
    private val titleId = id("title")
    private var view: View? = null
    private var original = 0f
    private val point = IntArray(2)
    private val origin = IntArray(2)

    fun apply(controls: ViewGroup) {
        val song = controls.parent as? ViewGroup ?: run { restore(); return }
        val title = song.findViewById<View>(titleId) ?: run { restore(); return }
        val artwork = song.findViewById<View>(artworkId) ?: run { restore(); return }
        val card = artwork.findViewById<View>(cardId) ?: run { restore(); return }
        if (artwork.height <= 0 || card.height <= 0 || !title.isShown) { restore(); return }
        if (view !== artwork) { restore(); view = artwork; original = artwork.translationY }
        fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
        root.getLocationInWindow(origin)
        title.getLocationInWindow(point)
        val metadataTop = point[1] - origin[1]
        artwork.getLocationInWindow(point)
        val nativeTop = point[1] - origin[1] - (artwork.translationY - original).roundToInt() + card.top
        val inset = if (Build.VERSION.SDK_INT >= 30) root.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
            else @Suppress("DEPRECATION") (root.rootWindowInsets?.systemWindowInsetTop ?: 0)
        val safeTop = (inset - origin[1]).coerceAtLeast(0)
        val target = TabletArtworkSpacingGeometry.top(nativeTop, card.height, metadataTop, safeTop, dp(48), dp(32))
            ?: run { restore(); return }
        artwork.translationY = original + target - nativeTop
    }
    fun restore() { view?.translationY = original; view = null }
}

internal object TabletArtworkSpacingGeometry {
    fun top(nativeTop: Int, artworkHeight: Int, metadataTop: Int, safeTop: Int, topGap: Int, metadataGap: Int): Int? {
        if (artworkHeight <= 0 || safeTop < 0 || topGap < 0 || metadataGap < 0) return null
        val maximum = metadataTop - metadataGap - artworkHeight
        if (maximum < safeTop) return null
        return maxOf(nativeTop, safeTop + topGap).coerceIn(safeTop, maximum)
    }
}
