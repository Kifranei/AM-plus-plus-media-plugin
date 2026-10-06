package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeLayoutStateTest {
    private data class Controls(val id: String = "player_controls") {
        val spacing = PlayerVolumeSpacing(0)
        val minimum = PlayerVolumeMinimum(0)
        var margin = spacing.update(0, -16)
        var reserved = minimum.update(0, 881)
        fun restore() {
            margin = spacing.restore(margin)
            reserved = minimum.restore(reserved)
        }
    }

    @Test fun `native pane and shared controls with identical IDs keep independent layout ownership`() {
        val native = Controls()
        val shared = Controls()
        assertEquals(native, shared)
        val outgoing = PlayerVolumeLayoutState.inactive(listOf(native, shared), listOf(shared))
        assertEquals(1, outgoing.size)
        assertSame(native, outgoing.single())
        outgoing.forEach(Controls::restore)
        assertEquals(0, native.margin)
        assertEquals(0, native.reserved)
        assertEquals(32, shared.margin)
        assertEquals(881, shared.reserved)
    }

    @Test fun `song lyrics queue switches restore the outgoing pane without accumulating space`() {
        val song = Controls()
        val lyrics = Controls()
        val queue = Controls()
        val tracked = listOf(song, lyrics, queue)
        PlayerVolumeLayoutState.inactive(tracked, listOf(lyrics)).forEach(Controls::restore)
        assertEquals(0, song.reserved)
        assertEquals(32, lyrics.margin)
        assertEquals(881, lyrics.reserved)
        queue.margin = queue.spacing.update(queue.margin, -16)
        queue.reserved = queue.minimum.update(queue.reserved, 881)
        PlayerVolumeLayoutState.inactive(tracked, listOf(queue)).forEach(Controls::restore)
        assertEquals(0, lyrics.margin)
        assertEquals(0, lyrics.reserved)
        assertEquals(32, queue.margin)
        assertEquals(881, queue.reserved)
    }

    @Test fun `removed controls restore native overrides even when no replacement is visible`() {
        val outgoing = Controls()
        outgoing.margin = 7
        outgoing.reserved = 900
        PlayerVolumeLayoutState.inactive(listOf(outgoing), emptyList()).forEach(Controls::restore)
        assertEquals(7, outgoing.margin)
        assertEquals(900, outgoing.reserved)
    }

    @Test fun `switching retained phone panes does not restore bounds still used by shared elements`() {
        val song = Controls()
        val lyrics = Controls()
        val queue = Controls()
        val attached = listOf(song, lyrics, queue)
        repeat(30) {
            // The selected pane changes; all attached native groups retain ownership.
            assertTrue(PlayerVolumeLayoutState.inactive(attached, attached).isEmpty())
            attached.forEach { pane ->
                assertEquals(32, pane.margin)
                assertEquals(881, pane.reserved)
            }
        }
        val departed = PlayerVolumeLayoutState.inactive(attached, listOf(song, queue))
        assertSame(lyrics, departed.single())
        departed.forEach(Controls::restore)
        assertEquals(0, lyrics.margin)
        assertEquals(0, lyrics.reserved)
        assertEquals(32, song.margin)
        assertEquals(881, queue.reserved)
    }

    private val centered = PlayerVolumePlacement(820, 1260, 920, 99, 2560, 1600, 1f,
        PlayerVolumeRegion(748, 800, 1064, 800))

    @Test fun `restoring the left tablet column invalidates centered HOLD geometry at unchanged root size`() {
        val position = PlayerVolumePosition()
        val column = PlayerVolumeHoldColumn<Any>(position)
        val source = Any()
        assertTrue(position.place(centered) {})
        column.remember(source, 748)
        assertTrue(column.hold(748, 2560, 1600) { assertEquals(centered, it) })
        assertFalse(column.hold(108, 2560, 1600) { fail("The centered overlay must stay hidden") })
        assertNull(column.source)
        assertFalse(position.hold(2560, 1600) { fail("Discard the cached placement as well") })
        // Returning to the old X cannot revive it without a fresh LAYOUT placement.
        assertFalse(column.hold(748, 2560, 1600) { fail("Do not revive the discarded center") })
        val left = centered.copy(left = 180, column = PlayerVolumeRegion(108, 800, 1064, 800))
        assertTrue(position.place(left) {})
        column.remember(source, 108)
        assertTrue(column.hold(108, 2560, 1600) { assertEquals(left, it) })
    }

    @Test fun `a stable column keeps its cached volume placement during native pane animation`() {
        val position = PlayerVolumePosition()
        val column = PlayerVolumeHoldColumn<Any>(position)
        val source = Any()
        assertTrue(position.place(centered) {})
        column.remember(source, 748)
        repeat(60) { assertTrue(column.hold(748, 2560, 1600) { assertEquals(centered, it) }) }
        assertSame(source, column.source)
    }

    @Test fun `a detached controls source cannot retain a floating volume overlay`() {
        val position = PlayerVolumePosition()
        val column = PlayerVolumeHoldColumn<Any>(position)
        assertTrue(position.place(centered) {})
        column.remember(Any(), 748)
        assertFalse(column.hold(null, 2560, 1600) { fail("Detached source") })
        assertFalse(position.hold(2560, 1600) { fail("Detached cache") })
    }
}
