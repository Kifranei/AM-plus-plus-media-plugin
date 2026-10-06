package dev.kifranei.ampp.media

internal object PlayerChromeGeometry {
    fun handleAlpha(expansion: Float, fullscreenArtwork: Boolean = false): Int {
        if (fullscreenArtwork || !expansion.isFinite()) return 0
        return (((expansion - .75f) / .25f).coerceIn(0f, 1f) * 150f).toInt()
    }

    fun motionRadius(system: Float, collapsed: Float, nativeCollapse: Float): Float {
        val progress = if (nativeCollapse.isFinite()) nativeCollapse.coerceIn(0f, 1f) else 1f
        return system + (collapsed - system) * progress
    }
}
