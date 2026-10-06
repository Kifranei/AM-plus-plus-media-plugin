package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeMotionTest {
    @Test fun `pane transitions hold the bar without moving native shared elements`() {
        assertEquals(PlayerVolumeMotion.Mode.HOLD, PlayerVolumeMotion.mode(3, 1f, true))
        assertEquals(PlayerVolumeMotion.Mode.LAYOUT, PlayerVolumeMotion.mode(3, 1f, false))
    }

    @Test fun `bottom sheet dragging and settling block layout writes even with a stale expanded progress`() {
        listOf(1, 2, 4, 5).forEach { state ->
            assertEquals(PlayerVolumeMotion.Mode.HIDE, PlayerVolumeMotion.mode(state, 1f, false))
        }
        assertEquals(PlayerVolumeMotion.Mode.HIDE, PlayerVolumeMotion.mode(3, .99f, false))
        assertEquals(PlayerVolumeMotion.Mode.HIDE, PlayerVolumeMotion.mode(3, Float.NaN, false))
    }

    @Test fun `pane switches and close reopen cycles preserve the prepared snapshot geometry`() {
        val spacing = PlayerVolumeSpacing(0)
        var margin = spacing.update(0, -16)
        val phases = listOf(Triple(3, 1f, true), Triple(3, 1f, false), Triple(1, .9f, false),
            Triple(2, .6f, false), Triple(4, 0f, false), Triple(2, .6f, false), Triple(3, 1f, false))
        repeat(3) {
            phases.forEach { (state, expansion, transition) ->
                if (PlayerVolumeMotion.mode(state, expansion, transition) == PlayerVolumeMotion.Mode.LAYOUT) {
                    margin = spacing.update(margin, -16)
                }
                assertEquals(32, margin)
                assertEquals(-16f, spacing.offset(margin))
            }
        }
    }
}
