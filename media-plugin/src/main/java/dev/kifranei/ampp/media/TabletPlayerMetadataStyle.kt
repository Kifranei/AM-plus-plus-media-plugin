package dev.kifranei.ampp.media

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.IdentityHashMap

/** Compact only the player's own song headers, preserving queue entries and the app mini player. */
internal class TabletPlayerMetadataStyle(private val root: ViewGroup, private val packageName: String) : AutoCloseable {
    private fun id(name: String) = root.resources.getIdentifier(name, "id", packageName)
    private val songId = id("player_container")
    private val headerId = id("current_player_item")
    private val titleId = id("title")
    private val artistId = id("subtitle")
    private val miniTitleId = id("mini_player_title")
    private val miniArtistId = id("mini_player_subtitle")
    private data class Original(val textSize: Float, val includeFontPadding: Boolean, val minimumHeight: Int)
    private val originals = IdentityHashMap<TextView, Original>()
    private var headers = emptyList<Pair<TextView, Boolean>>()
    private var dirty = true

    fun rediscover() { dirty = true }

    fun apply() {
        if (dirty) {
            val result = ArrayList<Pair<TextView, Boolean>>()
            fun visit(view: View) {
                if (view is ViewGroup) {
                    if (view.id == songId) {
                        // The cover pane has direct title/subtitle children.
                        for (index in 0 until view.childCount) {
                            val child = view.getChildAt(index) as? TextView ?: continue
                            if (child.id == titleId || child.id == artistId) result += child to (child.id == titleId)
                        }
                    } else if (view.id == headerId) {
                        view.findViewById<TextView>(miniTitleId)?.let { result += it to true }
                        view.findViewById<TextView>(miniArtistId)?.let { result += it to false }
                    } else for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
            }
            visit(root)
            headers = result
            dirty = false
        }
        @Suppress("DEPRECATION")
        val scaledDensity = root.resources.displayMetrics.scaledDensity
        headers.forEach { (view, title) ->
            val original = originals.getOrPut(view) { Original(view.textSize, view.includeFontPadding, view.minimumHeight) }
            val size = minOf(original.textSize, (if (title) 18f else 16f) * scaledDensity)
            if (kotlin.math.abs(view.textSize - size) > .1f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            if (view.includeFontPadding) view.includeFontPadding = false
            // The adjacent native buttons span the two text lines. Keep a 44 dp touch row.
            val minimum = maxOf(original.minimumHeight, kotlin.math.ceil(22 * root.resources.displayMetrics.density).toInt())
            if (view.minimumHeight != minimum) view.minimumHeight = minimum
        }
    }

    fun restore() {
        originals.forEach { (view, original) ->
            if (kotlin.math.abs(view.textSize - original.textSize) > .1f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, original.textSize)
            if (view.includeFontPadding != original.includeFontPadding) view.includeFontPadding = original.includeFontPadding
            if (view.minimumHeight != original.minimumHeight) view.minimumHeight = original.minimumHeight
        }
        originals.clear()
    }

    override fun close() { restore(); headers = emptyList() }
}
