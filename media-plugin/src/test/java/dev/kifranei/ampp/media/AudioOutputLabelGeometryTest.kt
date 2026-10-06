package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class AudioOutputLabelGeometryTest {
    @Test fun labelAlignsWithTheIconAndStaysInTheLeftColumn() {
        val result = checkNotNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(80, 820, 60, 60),
            1200, 900, 560, null, 24, 8, 18, 240))
        assertEquals(130, result.left)
        assertEquals(841, result.top)
        assertEquals(240, result.width)
        assertTrue(result.left + result.width <= 560)
    }
    @Test fun collapsedLyricsColumnMovesTheNameWithTheOutputButton() {
        val original = checkNotNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(80, 820, 60, 60),
            1200, 900, 560, null, 24, 8, 18, 240))
        val centered = checkNotNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(380, 820, 60, 60),
            1200, 900, 860, null, 24, 8, 18, 240))
        assertEquals(original.left + 300, centered.left)
        assertEquals(original.top, centered.top)
        assertEquals(original.width, centered.width)
    }
    @Test fun nearbyNativeActionsLimitTheDeviceNameRatherThanBeingCovered() {
        val result = checkNotNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(380, 820, 60, 60),
            1200, 900, 860, 500, 24, 8, 18, 240))
        assertEquals(62, result.width)
        assertEquals(492, result.left + result.width)
        assertNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(380, 820, 60, 60),
            1200, 900, 860, 438, 24, 8, 18, 240))
    }
    @Test fun rotationAndFontSizeUseCurrentBounds() {
        val portrait = checkNotNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(80, 1420, 60, 60),
            900, 1500, 860, null, 24, 8, 28, 240))
        assertEquals(1436, portrait.top)
        assertNull(AudioOutputLabelGeometry.beside(PlayerVolumeRegion(80, 1420, 60, 60),
            1500, 900, 720, null, 24, 8, 28, 240))
    }
}
