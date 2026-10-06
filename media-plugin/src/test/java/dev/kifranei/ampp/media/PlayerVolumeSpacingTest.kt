package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeSpacingTest {
    @Test fun `all pane layouts have the same offset before snapshots and repeated frames do not accumulate it`() {
        listOf(0, 4, 8).forEach { native ->
            val spacing = PlayerVolumeSpacing(native)
            var margin = spacing.update(native, -16)
            assertEquals(native + 32, margin)
            assertEquals(-16f, spacing.offset(margin))
            repeat(120) { margin = spacing.update(margin, -16) }
            assertEquals(native + 32, margin)
            assertEquals(native, spacing.restore(margin))
        }
    }

    @Test fun `compact layout can reserve more space without losing its original margin`() {
        val spacing = PlayerVolumeSpacing(2)
        val margin = spacing.update(2, -16)
        val shift = checkNotNull(PlayerVolumeGeometry.transportShift(120, 186, 84, 222, 44, 8, 16))
        val compact = spacing.update(margin, shift)
        assertEquals(50, compact)
        assertEquals(-24f, spacing.offset(compact))
        assertEquals(170, PlayerVolumeGeometry.top(186 + spacing.offset(compact).toInt(), 222, 44, 8))
        assertEquals(2, spacing.restore(compact))
    }

    @Test fun `native margin changes become the new baseline and are retained on cleanup`() {
        val spacing = PlayerVolumeSpacing(0)
        spacing.update(0, -16)
        assertEquals(0f, spacing.offset(7))
        assertEquals(39, spacing.update(7, -16))
        assertEquals(7, spacing.restore(39))
        spacing.update(7, -16)
        assertEquals(12, spacing.restore(12))
    }
}
