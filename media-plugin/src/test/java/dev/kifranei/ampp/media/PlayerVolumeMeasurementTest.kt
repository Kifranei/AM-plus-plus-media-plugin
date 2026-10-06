package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeMeasurementTest {
    @Test fun `unbounded pre-layout progress measurement cannot reserve a screen-sized controls pane`() {
        // The progress row contains a wrap-content ConstraintLayout, not just a seekbar leaf.
        assertNull(PlayerVolumeMeasurement.height(-2, 2130, 0, ready = false))
        assertNull(PlayerVolumeMeasurement.height(-2, 2130, 163, ready = false))
        assertEquals(163, PlayerVolumeMeasurement.height(-2, 163, 163, ready = true))
    }

    @Test fun `fixed transport height cannot be replaced by drawable intrinsic height`() {
        // Existing 530 capture: play_pause LayoutParams and native bounds are both 219px.
        assertNull(PlayerVolumeMeasurement.height(219, 298, 298, ready = true))
        assertEquals(219, PlayerVolumeMeasurement.height(219, 219, 219, ready = true))
    }

    @Test fun `density change waits for native layout instead of keeping previous row heights`() {
        assertNull(PlayerVolumeMeasurement.height(219, 182, 182, ready = false))
        assertNull(PlayerVolumeMeasurement.height(219, 219, 182, ready = true))
        assertEquals(219, PlayerVolumeMeasurement.height(219, 219, 219, ready = true))
        assertEquals(182, PlayerVolumeMeasurement.height(182, 182, 182, ready = true))
    }

    @Test fun `unlaid-out and empty rows cannot provide reservation geometry`() {
        assertNull(PlayerVolumeMeasurement.height(-2, 163, 0, ready = false))
        assertNull(PlayerVolumeMeasurement.height(-2, 0, 0, ready = true))
        assertNull(PlayerVolumeMeasurement.height(-1, 163, 0, ready = true))
    }
}
