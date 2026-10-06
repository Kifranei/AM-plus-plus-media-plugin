package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class TabletPlayerActionsGeometryTest {
    @Test fun dualColumnPlacesActionsOnOppositeSides() {
        val actual = TabletPlayerActionsGeometry.place(2560, 1600, 108, 1064, 180, 1440, 135, 135, 135, 72, 36)!!
        assertEquals(180, actual.outputLeft)
        assertEquals(2182, actual.lyricsLeft)
        assertEquals(2353, actual.queueLeft)
        assertEquals(1440, actual.top)
    }
    @Test fun singleColumnKeepsNativeArrangement() {
        assertNull(TabletPlayerActionsGeometry.place(1600, 2560, 72, 1456, 72, 2400, 120, 120, 120, 72, 36))
    }
    @Test fun transitionOriginIsRejected() {
        assertNull(TabletPlayerActionsGeometry.place(2560, 1600, 108, 1064, 180, 0, 135, 135, 135, 72, 36))
    }
    @Test fun safeEdgeAndGapScaleWithDensity() {
        val actual = TabletPlayerActionsGeometry.place(2560, 1600, 108, 1064, 214, 1370, 198, 198, 198, 146, 53)!!
        assertEquals(2560 - 146, actual.queueLeft + 198)
        assertEquals(53, actual.queueLeft - actual.lyricsLeft - 198)
    }
    @Test fun modesFitBesideNativeTransportButtonsAtMultipleDensities() {
        val standard = TabletPlayerActionsGeometry.modes(108, 1064, 347, 933, 99, 72, 9)!!
        assertTrue(standard.shuffleLeft + standard.size + 9 <= 347)
        assertTrue(standard.repeatLeft - 9 >= 933)
        val dense = TabletPlayerActionsGeometry.modes(108, 1064, 277, 1003, 146, 106, 13)!!
        assertTrue(dense.shuffleLeft + dense.size + 13 <= 277)
        assertTrue(dense.repeatLeft - 13 >= 1003)
    }
    @Test fun crowdedModeTargetsKeepNativeControlsAccessible() {
        assertNull(TabletPlayerActionsGeometry.modes(108, 300, 150, 360, 99, 72, 9))
    }
    @Test fun centeredCompactColumnMayRequireNativeRowProxiesToKeepLyricsAccessible() {
        assertNotNull(TabletPlayerActionsGeometry.place(600, 500, 48, 204, 60, 410, 60, 60, 60, 64, 16))
        // The same column remains centered even when the right-edge action pair no longer fits.
        assertNull(TabletPlayerActionsGeometry.place(600, 500, 198, 204, 210, 410, 60, 60, 60, 64, 16))
    }
}
