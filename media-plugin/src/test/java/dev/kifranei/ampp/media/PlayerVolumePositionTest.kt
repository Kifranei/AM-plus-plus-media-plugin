package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumePositionTest {
    private val bottom = PlayerVolumePlacement(32, 970, 536, 44, 600, 1200, 1f)

    @Test fun `parent relayout at the origin is corrected on every held transition frame`() {
        val position = PlayerVolumePosition()
        var left = 0
        var top = 0
        val apply: (PlayerVolumePlacement) -> Unit = { left = it.left; top = it.top }
        assertTrue(position.place(bottom, apply))
        repeat(120) {
            left = 0; top = 0 // FrameLayout positions the extra child before pre-draw.
            assertTrue(position.hold(600, 1200, apply))
            assertEquals(32, left)
            assertEquals(970, top)
        }
    }

    @Test fun `temporary top coordinates cannot replace the last valid bottom placement`() {
        val position = PlayerVolumePosition()
        position.place(bottom) {}
        assertFalse(position.place(bottom.copy(top = 0)) { fail("Never draw at the status bar") })
        var drawn: PlayerVolumePlacement? = null
        assertTrue(position.hold(600, 1200) { drawn = it })
        assertEquals(bottom, drawn)
    }

    @Test fun `viewport changes and missing placements hide the bar until valid new bounds exist`() {
        val position = PlayerVolumePosition()
        assertFalse(position.hold(600, 1200) { fail("No position has been measured") })
        position.place(bottom) {}
        assertFalse(position.hold(800, 1400) { fail("Old coordinates belong to another viewport") })
        val resized = bottom.copy(left = 32, top = 1100, width = 736, rootWidth = 800, rootHeight = 1400)
        assertTrue(position.place(resized) {})
        assertTrue(position.hold(800, 1400) { assertEquals(resized, it) })
        position.clear()
        assertFalse(position.hold(800, 1400) { fail("The destroyed player has no cached position") })
    }

    @Test fun `offscreen and nonvisible geometry is never cached for a transition`() {
        val position = PlayerVolumePosition()
        listOf(bottom.copy(left = -1), bottom.copy(top = 1190), bottom.copy(width = 601),
            bottom.copy(height = 0), bottom.copy(alpha = Float.NaN), bottom.copy(alpha = 0f))
            .forEach { assertFalse(position.place(it) { fail("Invalid geometry") }) }
    }

    @Test fun `tablet and short panes stay in their own control column during transitions`() {
        val column = PlayerVolumeRegion(108, 990, 1064, 574)
        val placement = PlayerVolumePlacement(180, 1260, 920, 99, 2560, 1600, 1f, column)
        val position = PlayerVolumePosition()
        assertTrue(position.place(placement) {})
        assertFalse(position.place(placement.copy(left = 1280)) { fail("Never cover the lyrics column") })
        assertTrue(position.hold(2560, 1600) { assertEquals(placement, it) })
        // A compact column can start above the window midpoint and still be bottom anchored.
        assertTrue(placement.copy(top = 730, column = column.copy(top = 700, height = 864)).fits(2560, 1600))
        assertFalse(placement.copy(top = 0, column = column.copy(top = 0, height = 700)).fits(2560, 1600))
    }
}
