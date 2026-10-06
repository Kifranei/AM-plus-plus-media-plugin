package dev.kifranei.ampp.media

import android.view.View
import android.view.ViewGroup
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Compact native touch rows before measuring the stack; keep every action at least 44dp tall. */
internal class TabletPlayerControlSizing {
    private data class Original(val params: ViewGroup.LayoutParams, val height: Int,
        val top: Int, val bottom: Int, val topMargin: Int?, val bottomMargin: Int?)
    private val originals = IdentityHashMap<View, Original>()

    fun apply(transports: List<View>, actions: List<View>, times: List<View>): Boolean {
        var changed = false
        fun style(view: View, heightDp: Int?, paddingDp: Int?, marginDp: Int?, bottomDp: Int?) {
            val params = view.layoutParams ?: return
            val margin = params as? ViewGroup.MarginLayoutParams
            val saved = originals[view]?.takeIf { it.params === params } ?: Original(params, params.height,
                view.paddingTop, view.paddingBottom, margin?.topMargin, margin?.bottomMargin).also { originals[view] = it }
            fun dp(value: Int) = (value * view.resources.displayMetrics.density).roundToInt()
            var layoutChanged = false
            val height = heightDp?.let(::dp)
            if (height != null && params.height != height) { params.height = height; layoutChanged = true }
            paddingDp?.let { value ->
                val top = minOf(saved.top, dp(value)); val bottom = minOf(saved.bottom, dp(value))
                if (view.paddingTop != top || view.paddingBottom != bottom) {
                    view.setPadding(view.paddingLeft, top, view.paddingRight, bottom); changed = true
                }
            }
            marginDp?.let { value -> margin?.let {
                val top = minOf(saved.topMargin ?: 0, dp(value)); val bottom = minOf(saved.bottomMargin ?: 0, dp(value))
                if (it.topMargin != top || it.bottomMargin != bottom) { it.topMargin = top; it.bottomMargin = bottom; layoutChanged = true }
            } }
            bottomDp?.let { value -> margin?.let {
                val bottom = minOf(saved.bottomMargin ?: 0, dp(value))
                if (it.bottomMargin != bottom) { it.bottomMargin = bottom; layoutChanged = true }
            } }
            if (layoutChanged) { view.layoutParams = params; changed = true }
        }
        transports.forEach { style(it, 52, 7, null, null) }
        actions.forEach { style(it, 44, 8, null, 8) }
        times.forEach { style(it, null, null, 4, null) }
        return changed
    }
    /** Restore outgoing pane rows only after native pane animations have finished. */
    fun retain(active: Collection<View>): Boolean {
        var changed = false
        PlayerVolumeLayoutState.inactive(originals.keys, active).forEach { if (restore(it)) changed = true }
        return changed
    }
    private fun restore(view: View): Boolean {
        val saved = originals.remove(view) ?: return false
        if (view.layoutParams !== saved.params) return false
        saved.params.height = saved.height
        (saved.params as? ViewGroup.MarginLayoutParams)?.let {
            saved.topMargin?.let { value -> it.topMargin = value }; saved.bottomMargin?.let { value -> it.bottomMargin = value }
        }
        view.setPadding(view.paddingLeft, saved.top, view.paddingRight, saved.bottom)
        view.layoutParams = saved.params
        return true
    }
    fun close() { originals.keys.toList().forEach(::restore) }
}
