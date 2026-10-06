package dev.kifranei.ampp.media

import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Aligns a stable tablet SONG pane to the native playing cover width, in root-local pixels.
 * The caller owns the tablet/pane/transition decision and must restore before a native transition.
 * This helper never writes artwork geometry, scales, text sizes, or button dimensions.
 *
 * Native model: music_player.xml has direct title/subtitle children anchored to
 * metadata_guideline_start, and list_left_icon anchored to metadata_guideline_end.
 * Favorite/emoji remain constrained to that trailing action. The included match-parent
 * player_controls keeps its native transport constraints; only horizontal padding changes.
 * seek_bar_controls/progress horizontal margins and padding are temporarily zeroed as well:
 * either native inset mechanism would otherwise shorten the visible track a second time.
 * G0.P(View, int, boolean) owns the card's pause scale. Exclude that local scale
 * from the reference width so pausing never shrinks the metadata or control tracks.
 */
internal class TabletPlayerContentWidth(private val root: ViewGroup) : AutoCloseable {
    data class Region(val left: Float, val right: Float) {
        val width: Float get() = right - left
    }

    private fun id(name: String) = root.resources.getIdentifier(name, "id", root.context.packageName)
    private val songId = id("player_container")
    private val controlsId = id("player_controls")
    private val seekId = id("seek_bar_controls")
    private val progressId = id("progress")
    private val artworkId = id("artwork_container")
    private val cardId = id("fullplayerSongImage")
    private val titleId = id("title")
    private val artistId = id("subtitle")
    private val moreId = id("list_left_icon")
    private val startId = id("metadata_guideline_start")
    private val endId = id("metadata_guideline_end")
    private val startAnchor by lazy { PluginProfiles.field("tablet-content-start-anchor") }
    private val endAnchor by lazy { PluginProfiles.field("tablet-content-end-anchor") }
    private var activeSong: ViewGroup? = null
    private var activeControls: ViewGroup? = null
    private val settlement = TabletContentWidthSettlement()
    private var viewportWidth = 0
    private var viewportHeight = 0

    private data class MarginOriginal(
        val params: ViewGroup.MarginLayoutParams, val start: Boolean, val value: Int,
        val relative: Boolean, val physical: Int, val rtl: Boolean,
    )
    private data class PaddingOriginal(val left: Int, val right: Int, val start: Int, val end: Int, val relative: Boolean)
    private data class HorizontalMarginsOriginal(val params: ViewGroup.MarginLayoutParams,
        val left: Int, val right: Int, val start: Int, val end: Int, val relative: Boolean)
    private val margins = IdentityHashMap<View, MarginOriginal>()
    private val paddings = IdentityHashMap<View, PaddingOriginal>()
    private val trackMargins = IdentityHashMap<View, HorizontalMarginsOriginal>()

    private fun directChild(parent: ViewGroup, childId: Int): View? = if (childId == 0) null else
        (0 until parent.childCount).asSequence().map(parent::getChildAt).firstOrNull { it.id == childId }

    /** Null means unsupported/unstable geometry; previous overrides are restored. */
    fun apply(controls: ViewGroup, stableTabletSong: Boolean): Region? {
        fun unavailable(): Region? { restore(); return null }
        if (!stableTabletSong || root.resources.configuration.smallestScreenWidthDp < 600 ||
            !root.isAttachedToWindow || !root.isShown || controls.id != controlsId || controlsId == 0) return unavailable()
        val song = controls.parent as? ViewGroup ?: return unavailable()
        if (songId == 0 || song.id != songId || !song.isShown) return unavailable()
        if (activeSong !== song || activeControls !== controls) restore()
        fun child(childId: Int): View? = directChild(song, childId)
        val title = child(titleId) ?: return unavailable()
        val artist = child(artistId) ?: return unavailable()
        val more = child(moreId) ?: return unavailable()
        val start = child(startId) ?: return unavailable()
        val end = child(endId) ?: return unavailable()
        val seek = directChild(controls, seekId) as? ViewGroup ?: return unavailable()
        val progress = directChild(seek, progressId) ?: return unavailable()
        val artwork = child(artworkId) as? ViewGroup ?: return unavailable()
        val card = if (cardId != 0) artwork.findViewById<View>(cardId) else null
        if (card == null || !card.isShown || card.width <= 0 || card.height <= 0 ||
            title.visibility == View.GONE || artist.visibility == View.GONE || more.visibility == View.GONE) return unavailable()
        // Host-verified profile aliases supply the obfuscated native constraint contract.
        fun anchored(view: View, field: java.lang.reflect.Field, target: Int): Boolean {
            val params = view.layoutParams
            return params is ViewGroup.MarginLayoutParams && field.declaringClass.isInstance(params) && field.getInt(params) == target
        }
        if (!anchored(title, startAnchor, startId) || !anchored(artist, startAnchor, startId) ||
            !anchored(more, endAnchor, endId)) return unavailable()

        val coverAxis = axisToRoot(card) ?: return unavailable()
        val songAxis = axisToRoot(song) ?: return unavailable()
        val controlsAxis = axisToRoot(controls) ?: return unavailable()
        val density = root.resources.displayMetrics.density
        val playingAxis = TabletPlayerContentWidthGeometry.playingAxis(coverAxis, card.scaleX, card.pivotX)
            ?: return unavailable()
        val sample = TabletPlayerContentWidthGeometry.cover(card.width.toFloat(), playingAxis,
            root.width.toFloat(), density, stableTabletSong = true) ?: return unavailable()
        if (viewportWidth != root.width || viewportHeight != root.height) settlement.clear()
        viewportWidth = root.width; viewportHeight = root.height
        val region = settlement.observe(sample)
        if (abs(region.width - sample.width) > .5f) root.postInvalidateOnAnimation()
        val local = songAxis.local(region) ?: return unavailable()
        val padding = TabletPlayerContentWidthGeometry.padding(region, controlsAxis, controls.width) ?: return unavailable()
        val rtl = song.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val titleMargin = TabletPlayerContentWidthGeometry.startMargin(local, start.left.toFloat(), title.paddingStart, rtl)
            ?: return unavailable()
        val artistMargin = TabletPlayerContentWidthGeometry.startMargin(local, start.left.toFloat(), artist.paddingStart, rtl)
            ?: return unavailable()
        val moreMargin = TabletPlayerContentWidthGeometry.endMargin(local, end.left.toFloat(), more.paddingEnd, rtl)
            ?: return unavailable()
        if (margins.any { (view, saved) -> view.layoutParams !== saved.params ||
                (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) != saved.rtl } ||
            trackMargins.any { (view, saved) -> view.layoutParams !== saved.params }) return unavailable()
        activeSong = song
        activeControls = controls
        margin(title, true, titleMargin)
        margin(artist, true, artistMargin)
        margin(more, false, moreMargin)
        horizontalPadding(controls, padding.left, padding.right)
        zeroHorizontalMargins(seek)
        zeroHorizontalMargins(progress)
        horizontalPadding(seek, 0, 0)
        horizontalPadding(progress, 0, 0)
        return region
    }

    private fun margin(view: View, start: Boolean, value: Int) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        margins.getOrPut(view) {
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            MarginOriginal(params, start, if (start) params.marginStart else params.marginEnd,
                params.isMarginRelative, if (start != rtl) params.leftMargin else params.rightMargin, rtl)
        }
        if ((if (start) params.marginStart else params.marginEnd) == value) return
        if (start) params.marginStart = value else params.marginEnd = value
        params.resolveLayoutDirection(view.layoutDirection)
        view.requestLayout()
    }

    private fun horizontalPadding(view: View, left: Int, right: Int) {
        paddings.getOrPut(view) { PaddingOriginal(view.paddingLeft, view.paddingRight,
            view.paddingStart, view.paddingEnd, view.isPaddingRelative) }
        if (view.paddingLeft != left || view.paddingRight != right)
            view.setPadding(left, view.paddingTop, right, view.paddingBottom)
    }

    private fun zeroHorizontalMargins(view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.leftMargin == 0 && params.rightMargin == 0 && params.marginStart == 0 && params.marginEnd == 0) return
        trackMargins.getOrPut(view) { HorizontalMarginsOriginal(params, params.leftMargin, params.rightMargin,
            params.marginStart, params.marginEnd, params.isMarginRelative) }
        params.marginStart = 0
        params.marginEnd = 0
        params.leftMargin = 0
        params.rightMargin = 0
        params.resolveLayoutDirection(view.layoutDirection)
        view.requestLayout()
    }

    /** Build the full descendant matrix, including pivots, ancestor scale/translation and scroll. */
    private fun axisToRoot(view: View): TabletPlayerContentWidthGeometry.Axis? {
        val path = ArrayList<View>()
        var current = view
        while (current !== root) {
            if (current.animation?.hasEnded() == false) return null
            path += current
            current = current.parent as? View ?: return null
        }
        val matrix = Matrix()
        path.asReversed().forEach { child ->
            val parent = child.parent as View
            matrix.preTranslate((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
            matrix.preConcat(child.matrix)
        }
        val values = FloatArray(9)
        matrix.getValues(values)
        // Horizontal layout adjustments cannot faithfully follow rotations, shear or perspective.
        if (values.any { !it.isFinite() } || abs(values[Matrix.MSKEW_X]) > .00001f ||
            abs(values[Matrix.MSKEW_Y]) > .00001f || abs(values[Matrix.MPERSP_0]) > .00001f ||
            abs(values[Matrix.MPERSP_1]) > .00001f || abs(values[Matrix.MPERSP_2] - 1f) > .00001f ||
            values[Matrix.MSCALE_Y] <= 0f) return null
        return TabletPlayerContentWidthGeometry.Axis(values[Matrix.MSCALE_X], values[Matrix.MTRANS_X])
            .takeIf { it.valid }
    }

    /** Restore only owned horizontal properties; vertical sizing/padding belongs to other helpers. */
    fun restore() {
        margins.forEach { (view, saved) ->
            if (view.layoutParams === saved.params) {
                val params = saved.params
                val value = if (saved.relative) saved.value else Int.MIN_VALUE // unset relative margin
                if (saved.start) params.marginStart = value else params.marginEnd = value
                if (!saved.relative) {
                    if (saved.start != saved.rtl) params.leftMargin = saved.physical else params.rightMargin = saved.physical
                }
                params.resolveLayoutDirection(view.layoutDirection)
                view.requestLayout()
            }
        }
        margins.clear()
        trackMargins.forEach { (view, saved) ->
            if (view.layoutParams === saved.params) {
                saved.params.marginStart = if (saved.relative) saved.start else Int.MIN_VALUE
                saved.params.marginEnd = if (saved.relative) saved.end else Int.MIN_VALUE
                saved.params.leftMargin = saved.left
                saved.params.rightMargin = saved.right
                saved.params.resolveLayoutDirection(view.layoutDirection)
                view.requestLayout()
            }
        }
        trackMargins.clear()
        paddings.forEach { (view, saved) ->
            if (saved.relative) view.setPaddingRelative(saved.start, view.paddingTop, saved.end, view.paddingBottom)
            else view.setPadding(saved.left, view.paddingTop, saved.right, view.paddingBottom)
        }
        paddings.clear()
        activeSong = null
        activeControls = null
        settlement.clear()
    }

    override fun close() = restore()
}

/** Playback cover scale can animate; resize controls only once the new width has settled. */
internal class TabletContentWidthSettlement {
    private var applied: Float? = null
    private var candidate: Float? = null
    private var count = 0
    fun observe(region: TabletPlayerContentWidth.Region): TabletPlayerContentWidth.Region {
        if (applied == null) applied = region.width
        count = if (candidate?.let { abs(it - region.width) <= .5f } == true) minOf(count + 1, 2) else 1
        candidate = region.width
        if (count >= 2) applied = region.width
        val width = checkNotNull(applied)
        val center = (region.left + region.right) / 2
        return TabletPlayerContentWidth.Region(center - width / 2, center + width / 2)
    }
    fun clear() { applied = null; candidate = null; count = 0 }
}

/** Stateless calculations always start from current cover bounds/native anchors, never prior deltas. */
internal object TabletPlayerContentWidthGeometry {
    data class Axis(val scale: Float, val offset: Float) {
        val valid: Boolean get() = scale.isFinite() && scale > 0f && offset.isFinite()
        fun local(region: TabletPlayerContentWidth.Region): TabletPlayerContentWidth.Region? =
            if (!valid || !validRegion(region)) null else
                TabletPlayerContentWidth.Region((region.left - offset) / scale, (region.right - offset) / scale)
                    .takeIf(::validRegion)
    }
    data class Padding(val left: Int, val right: Int)

    private fun validRegion(region: TabletPlayerContentWidth.Region) =
        region.left.isFinite() && region.right.isFinite() && region.width.isFinite() && region.width > 0f

    /** Remove only the verified card-local playback scale; retain ancestor placement and scale. */
    fun playingAxis(rendered: Axis, cardScale: Float, pivot: Float): Axis? {
        if (!rendered.valid || !cardScale.isFinite() || cardScale <= 0f || !pivot.isFinite()) return null
        val scale = rendered.scale / cardScale
        return Axis(scale, rendered.offset - scale * pivot * (1f - cardScale)).takeIf { it.valid }
    }

    fun cover(width: Float, axis: Axis, rootWidth: Float, density: Float,
        stableTabletSong: Boolean): TabletPlayerContentWidth.Region? {
        if (!stableTabletSong || !axis.valid || !width.isFinite() || width <= 0f ||
            !rootWidth.isFinite() || rootWidth <= 0f || !density.isFinite() || density <= 0f) return null
        val region = TabletPlayerContentWidth.Region(axis.offset, axis.offset + width * axis.scale)
        // Do not shrink the cover or invent clipped bounds when the native pane cannot fit.
        return region.takeIf { validRegion(it) && it.left >= 0f && it.right <= rootWidth && it.width >= 44f * density }
    }

    fun padding(region: TabletPlayerContentWidth.Region, axis: Axis, containerWidth: Int): Padding? {
        val local = axis.local(region) ?: return null
        if (containerWidth <= 0 || local.left < 0f || local.right > containerWidth.toFloat()) return null
        return Padding(local.left.roundToInt(), (containerWidth - local.right).roundToInt())
    }

    // Align visible text/icon content, retaining the trailing action's native padded touch target.
    fun startMargin(local: TabletPlayerContentWidth.Region, anchor: Float, contentInset: Int, rtl: Boolean): Int? =
        margin(local, anchor, contentInset, if (rtl) anchor - local.right else local.left - anchor)

    fun endMargin(local: TabletPlayerContentWidth.Region, anchor: Float, contentInset: Int, rtl: Boolean): Int? =
        margin(local, anchor, contentInset, if (rtl) local.left - anchor else anchor - local.right)

    private fun margin(region: TabletPlayerContentWidth.Region, anchor: Float, inset: Int, distance: Float): Int? {
        val value = distance - inset
        if (!validRegion(region) || !anchor.isFinite() || inset < 0 || !value.isFinite() ||
            value <= Int.MIN_VALUE.toFloat() || value >= Int.MAX_VALUE.toFloat()) return null
        return value.roundToInt()
    }

}
