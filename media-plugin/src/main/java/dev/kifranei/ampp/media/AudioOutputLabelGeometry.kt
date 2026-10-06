package dev.kifranei.ampp.media

internal data class AudioOutputLabelPlacement(val left: Int, val top: Int, val width: Int, val height: Int)

/** Use the live native column, including the translated column when tablet lyrics are collapsed. */
internal object AudioOutputLabelGeometry {
    fun beside(button: PlayerVolumeRegion, parentWidth: Int, parentHeight: Int, columnRight: Int,
        blockedAt: Int?, iconWidth: Int, gap: Int, labelHeight: Int, maximumWidth: Int): AudioOutputLabelPlacement? {
        if (button.width <= 0 || button.height <= 0 || parentWidth <= 0 || parentHeight <= 0 ||
            iconWidth <= 0 || gap < 0 || labelHeight <= 0 || maximumWidth <= 0) return null
        val left = button.left + (button.width + minOf(iconWidth, button.width)) / 2 + gap
        val top = button.top + (button.height - labelHeight) / 2
        val right = minOf(parentWidth, columnRight, blockedAt?.minus(gap) ?: parentWidth)
        val width = minOf(maximumWidth, right - left)
        if (left < 0 || top < 0 || width <= 0 || top.toLong() + labelHeight > parentHeight) return null
        return AudioOutputLabelPlacement(left, top, width, labelHeight)
    }
}
