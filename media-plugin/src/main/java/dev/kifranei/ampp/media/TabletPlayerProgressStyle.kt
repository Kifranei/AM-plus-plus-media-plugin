package dev.kifranei.ampp.media

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** A smaller native quality badge with its own clearance below the seek track. */
internal class TabletPlayerProgressStyle(private val root: ViewGroup) : AutoCloseable {
    private fun id(name: String) = root.resources.getIdentifier(name, "id", root.context.packageName)
    private val badgeId = id("audio_badge_with_text")
    private val textId = id("audio_badge_text")
    private data class Badge(val params: ViewGroup.MarginLayoutParams, val top: Int, val bottom: Int,
        val leftPadding: Int, val rightPadding: Int, val topPadding: Int, val bottomPadding: Int)
    private data class Text(val size: Float, val fontPadding: Boolean)
    private val badges = IdentityHashMap<View, Badge>()
    private val texts = IdentityHashMap<TextView, Text>()

    fun apply(controls: ViewGroup) {
        if (root.resources.configuration.smallestScreenWidthDp < 600) return
        val badge = controls.findViewById<View>(badgeId) ?: return
        val params = badge.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val saved = badges[badge]?.takeIf { it.params === params } ?: Badge(params, params.topMargin, params.bottomMargin,
            badge.paddingLeft, badge.paddingRight, badge.paddingTop, badge.paddingBottom).also { badges[badge] = it }
        fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
        val top = maxOf(saved.top, dp(6)); val bottom = maxOf(saved.bottom, dp(4))
        if (params.topMargin != top || params.bottomMargin != bottom) {
            params.topMargin = top; params.bottomMargin = bottom; badge.layoutParams = params
        }
        val padding = listOf(minOf(saved.leftPadding, dp(5)), minOf(saved.topPadding, dp(2)),
            minOf(saved.rightPadding, dp(5)), minOf(saved.bottomPadding, dp(2)))
        if (badge.paddingLeft != padding[0] || badge.paddingTop != padding[1] ||
            badge.paddingRight != padding[2] || badge.paddingBottom != padding[3])
            badge.setPadding(padding[0], padding[1], padding[2], padding[3])
        val text = controls.findViewById<TextView>(textId) ?: return
        val original = texts.getOrPut(text) { Text(text.textSize, text.includeFontPadding) }
        @Suppress("DEPRECATION") val size = minOf(original.size, 10f * text.resources.displayMetrics.scaledDensity)
        if (abs(text.textSize - size) > .1f) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        if (text.includeFontPadding) text.includeFontPadding = false
    }

    fun retain(controls: ViewGroup) {
        val badge = controls.findViewById<View>(badgeId)
        val text = controls.findViewById<TextView>(textId)
        badges.keys.toList().filter { it !== badge }.forEach(::restoreBadge)
        texts.keys.toList().filter { it !== text }.forEach(::restoreText)
    }
    private fun restoreBadge(view: View) {
        val saved = badges.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        saved.params.topMargin = saved.top; saved.params.bottomMargin = saved.bottom
        view.setPadding(saved.leftPadding, saved.topPadding, saved.rightPadding, saved.bottomPadding)
        view.layoutParams = saved.params
    }
    private fun restoreText(view: TextView) {
        val saved = texts.remove(view) ?: return
        if (abs(view.textSize - saved.size) > .1f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, saved.size)
        if (view.includeFontPadding != saved.fontPadding) view.includeFontPadding = saved.fontPadding
    }
    override fun close() {
        badges.keys.toList().forEach(::restoreBadge)
        texts.keys.toList().forEach(::restoreText)
    }
}
