package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeGeometryTest {
    @Test fun `volume bar shifts the transport even on roomy layouts and restores native placement without it`() {
        assertEquals(-16, PlayerVolumeGeometry.transportShift(120, 186, 84, 280, 44, 8, preferredShift = 16))
        assertEquals(-24, PlayerVolumeGeometry.transportShift(120, 186, 84, 222, 44, 8, preferredShift = 16))
        assertEquals(-3, PlayerVolumeGeometry.transportShift(95, 160, 84, 280, 44, 8, preferredShift = 16))
        // Phone buttons sit 8dp higher; cramped layouts still stop short of the progress row.
        assertEquals(-24, PlayerVolumeGeometry.transportShift(120, 186, 84, 280, 44, 8, preferredShift = 24))
        assertEquals(-3, PlayerVolumeGeometry.transportShift(95, 160, 84, 280, 44, 8, preferredShift = 24))
    }
    @Test fun `compact player reserves volume space without covering progress`() {
        assertEquals(-24, PlayerVolumeGeometry.transportShift(120, 186, 84, 222, 44, 8))
        assertEquals(0, PlayerVolumeGeometry.transportShift(120, 186, 84, 260, 44, 8))
        assertNull(PlayerVolumeGeometry.transportShift(100, 166, 84, 202, 44, 8))
    }
    @Test fun `volume touch target fits between transport and actions without overlapping`() {
        assertEquals(158, PlayerVolumeGeometry.top(100, 260, 44, 8))
        assertEquals(108, PlayerVolumeGeometry.top(100, 160, 44, 8))
        assertNull(PlayerVolumeGeometry.top(100, 159, 44, 8))
        assertNull(PlayerVolumeGeometry.top(200, 100, 44, 8))
    }
    @Test fun `stream ranges clamp endpoints and quantize to actual volume steps`() {
        assertEquals(2, PlayerVolumeGeometry.level(-1f, 2, 15))
        assertEquals(15, PlayerVolumeGeometry.level(2f, 2, 15))
        assertEquals(9, PlayerVolumeGeometry.level(.5f, 2, 15))
        assertEquals(2, PlayerVolumeGeometry.level(Float.NaN, 2, 15))
        assertEquals(0f, PlayerVolumeGeometry.fraction(0, 2, 15))
        assertEquals(1f, PlayerVolumeGeometry.fraction(25, 2, 15))
        assertEquals(0f, PlayerVolumeGeometry.fraction(3, 3, 3))
    }

    @Test fun `tablet controls reserve enough room for the full slider at every density`() {
        // USB tablet: progress 53dp, transport/action 60dp, bottom inset 14dp.
        // The native 220dp controls do not have enough free space.
        listOf(1f, 2.25f, 3.3125f, 4f).forEach { density ->
            fun px(dp: Int) = kotlin.math.round(dp * density).toInt()
            val progress = px(53); val transport = px(60); val actions = px(60)
            val bar = px(44); val gap = px(8)
            val controls = PlayerVolumeGeometry.controlsHeight(progress, transport, actions, px(14), bar, gap)
            val actionsTop = controls - px(14) - actions
            val transportTop = progress + (actionsTop - progress - transport) / 2
            val shift = PlayerVolumeGeometry.transportShift(transportTop, transportTop + transport,
                progress, actionsTop, bar, gap, px(16))
            assertNotNull("The slider fits at density $density", shift)
            assertTrue(transportTop + shift!! >= progress + gap)
            assertNotNull(PlayerVolumeGeometry.top(transportTop + transport + shift, actionsTop, bar, gap))
        }
    }

    @Test fun `slider follows the current player column and clips split window bounds`() {
        assertEquals(180 to 920, PlayerVolumeGeometry.column(108, 1064, 2560, 72))
        assertEquals(32 to 1016, PlayerVolumeGeometry.column(0, 1080, 1080, 32))
        assertEquals(32 to 436, PlayerVolumeGeometry.column(-20, 520, 1280, 32))
        assertNull(PlayerVolumeGeometry.column(0, 50, 1080, 32))
    }
    @Test fun `compact ipad rows reserve a full volume target with smaller safe gaps`() {
        listOf(1f, 2.25f, 3.3125f).forEach { density ->
            fun px(dp: Int) = kotlin.math.round(dp * density).toInt()
            val progress = px(41); val transport = px(52); val actions = px(44)
            val bar = px(44); val gap = px(4)
            val height = PlayerVolumeGeometry.controlsHeight(progress, transport, actions, px(8), bar, gap)
            val actionsTop = height - px(8) - actions
            val transportTop = progress + (actionsTop - progress - transport) / 2
            val shift = checkNotNull(PlayerVolumeGeometry.transportShift(transportTop, transportTop + transport,
                progress, actionsTop, bar, gap, px(16)))
            assertTrue(transportTop + shift >= progress + gap)
            val volumeTop = checkNotNull(PlayerVolumeGeometry.top(transportTop + transport + shift, actionsTop, bar, gap))
            assertTrue(volumeTop + bar <= actionsTop - gap)
            assertTrue(height < px(230))
        }
    }

    @Test fun `phone native row sizes leave progress clearance and a full volume target across densities`() {
        // Existing phone captures at 440/530: progress 139/163, transport 182/219,
        // actions 165/199, bottom inset 39/46. Include 640 without a DPI-specific branch.
        data class Capture(val density: Float, val progress: Int, val transport: Int, val actions: Int, val bottom: Int)
        val captures = listOf(
            Capture(2.75f, 139, 182, 165, 39),
            Capture(3.3125f, 163, 219, 199, 46),
            Capture(4f, 200, 264, 240, 56)
        )
        captures.forEach { capture ->
            val density = capture.density
            fun px(dp: Int) = kotlin.math.round(dp * density).toInt()
            val progress = capture.progress
            val transport = capture.transport
            val actions = capture.actions
            val bar = px(44)
            val gap = px(8)
            val bottom = capture.bottom
            val height = PlayerVolumeGeometry.controlsHeight(progress, transport, actions, bottom, bar, gap)
            val actionsTop = height - bottom - actions
            // Native XML centers transport between seek_bar_controls and player_lyrics.
            val transportTop = progress + (actionsTop - progress - transport) / 2
            val shift = checkNotNull(PlayerVolumeGeometry.transportShift(transportTop, transportTop + transport,
                progress, actionsTop, bar, gap, px(24)))
            val volumeTop = checkNotNull(PlayerVolumeGeometry.top(transportTop + transport + shift, actionsTop, bar, gap))
            assertTrue(transportTop + shift >= progress + gap)
            assertTrue(transportTop + shift - progress <= bar)
            assertTrue(volumeTop >= transportTop + transport + shift + gap)
            assertTrue(volumeTop + bar <= actionsTop - gap)
        }
    }
}
