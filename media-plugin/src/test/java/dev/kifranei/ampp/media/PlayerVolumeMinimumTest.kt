package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerVolumeMinimumTest {
    @Test fun `layout and background cycles never add space to the previous height`() {
        val minimum = PlayerVolumeMinimum(0)
        var height = minimum.update(0, 264)
        repeat(500) { height = minimum.update(height, 264) }
        assertEquals(264, height)
        assertEquals(0, minimum.restore(height))
    }

    @Test fun `rotation and density use new child sizes and preserve native changes`() {
        val minimum = PlayerVolumeMinimum(0)
        assertEquals(264, minimum.update(0, 264))
        assertEquals(594, minimum.update(264, 594))
        assertEquals(264, minimum.update(594, 264))
        assertEquals(700, minimum.update(700, 594))
        assertEquals(700, minimum.restore(700))
    }

    @Test fun `fixed size and wrap minimum are restored when the feature closes`() {
        val fixed = PlayerVolumeMinimum(220)
        assertEquals(264, fixed.update(220, 264))
        assertEquals(220, fixed.restore(264))
        val wrap = PlayerVolumeMinimum(-2)
        assertEquals(264, wrap.update(-2, 264))
        assertEquals(-2, wrap.restore(264))
    }
}
