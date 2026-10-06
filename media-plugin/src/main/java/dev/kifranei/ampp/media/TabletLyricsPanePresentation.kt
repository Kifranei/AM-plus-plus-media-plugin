package dev.kifranei.ampp.media

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** The host retains its native lyric fragment and viewport while the plugin hides that pane. */
internal class TabletLyricsPanePresentation(private val root: ViewGroup) {
    private val songHostId = root.resources.getIdentifier("player_fragments_host", "id", root.context.packageName)
    private val playerId = root.resources.getIdentifier("player_root", "id", root.context.packageName)
    private val choice = TabletLyricsPaneState()
    private data class Panes(val left: View, val right: View, val parent: ViewGroup)
    private data class Original(val panes: Panes, val translation: Float, val visibility: Int)
    private var original: Original? = null
    val horizontalOffset: Float get() = original?.let { it.panes.left.translationX - it.translation } ?: 0f
    fun leftHost(): View? = panes()?.left

    @SuppressLint("ResourceType") // Stable runtime container created by AM++'s dual-pane host.
    private fun panes(): Panes? {
        val config = root.resources.configuration
        if (config.smallestScreenWidthDp < 600 || config.orientation != Configuration.ORIENTATION_LANDSCAPE ||
            root.width / root.resources.displayMetrics.density < 600 || songHostId == 0 || playerId == 0) return null
        val player = root.findViewById<ViewGroup>(playerId) ?: return null
        val left = player.findViewById<View>(songHostId) ?: return null
        val right = player.findViewById<FrameLayout>(0x00a71606) ?: return null
        val parent = left.parent as? ViewGroup ?: return null
        if (right.parent !== parent || parent.parent !== player || right.childCount == 0 || left.height <= 0 ||
            right.width <= 0 || right.height <= 0 ||
            TabletLyricsPaneGeometry.centeredTranslation(parent.width, left.left, left.width, 0f) == null) return null
        return Panes(left, right, parent)
    }

    /** Return true only when the host's forced SONG pane makes native lyrics clicks ineffective. */
    fun lyricsClick(songVisible: Boolean): Boolean = panes()?.let { choice.lyricsClick(songVisible) } ?: false

    fun apply(songVisible: Boolean): Boolean? {
        val panes = panes() ?: run { restore(); return null }
        if (!songVisible) { restore(); return choice.expanded }
        if (original?.panes?.left !== panes.left || original?.panes?.right !== panes.right) {
            restore()
            original = Original(panes, panes.left.translationX, panes.right.visibility)
        }
        val saved = checkNotNull(original)
        val translation = if (choice.expanded) saved.translation else
            TabletLyricsPaneGeometry.centeredTranslation(panes.parent.width, panes.left.left, panes.left.width, saved.translation)
                ?: run { restore(); return null }
        if (panes.left.translationX != translation) panes.left.translationX = translation
        val visibility = if (choice.expanded) saved.visibility else View.INVISIBLE
        if (panes.right.visibility != visibility) panes.right.visibility = visibility
        return choice.expanded
    }

    fun restore() {
        original?.let { saved ->
            saved.panes.left.translationX = saved.translation
            saved.panes.right.visibility = saved.visibility
        }
        original = null
    }
}
