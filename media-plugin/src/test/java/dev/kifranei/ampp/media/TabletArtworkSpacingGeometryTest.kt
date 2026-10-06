package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class TabletArtworkSpacingGeometryTest {
    @Test fun titleSpacingAndTopClearanceUseTheAvailableTabletHeight() {
        assertEquals(92, TabletArtworkSpacingGeometry.top(37, 480, 620, 24, 68, 45))
        assertEquals(85, TabletArtworkSpacingGeometry.top(37, 480, 610, 24, 68, 45))
    }
    @Test fun shortWindowDoesNotInventSpaceOrResizeArtwork() {
        assertNull(TabletArtworkSpacingGeometry.top(37, 480, 500, 24, 68, 45))
        assertEquals(37, TabletArtworkSpacingGeometry.top(37, 480, 550, 24, 68, 33))
    }
    @Test fun recomputingFromNativeBoundsNeverAccumulatesThePriorTranslation() {
        repeat(60) { assertEquals(92, TabletArtworkSpacingGeometry.top(37, 480, 620, 24, 68, 45)) }
        assertEquals(130, TabletArtworkSpacingGeometry.top(130, 480, 700, 24, 68, 45))
    }
}
