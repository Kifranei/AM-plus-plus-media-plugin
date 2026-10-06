package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class TabletLyricsPaneStateTest {
    @Test fun lyricsCanCollapseAndReopenWhileNativePaneRemainsSong() {
        val state = TabletLyricsPaneState()
        assertTrue(state.expanded)
        assertTrue(state.lyricsClick(songVisible = true))
        assertFalse(state.expanded)
        assertTrue(state.lyricsClick(songVisible = true))
        assertTrue(state.expanded)
    }
    @Test fun lyricsFromQueueReopensRightPaneAndDelegatesNativeSwitch() {
        val state = TabletLyricsPaneState()
        state.lyricsClick(songVisible = true)
        assertFalse(state.lyricsClick(songVisible = false))
        assertTrue(state.expanded)
    }
    @Test fun centeredColumnPreservesNativeTranslationAcrossDensities() {
        assertEquals(500f, TabletLyricsPaneGeometry.centeredTranslation(2000, 96, 808, 0f)!!, .01f)
        assertEquals(1270f, TabletLyricsPaneGeometry.centeredTranslation(5000, 240, 2020, 20f)!!, .01f)
        // Recompute from layout bounds and the saved native translation, never from last plugin position.
        assertEquals(500f, TabletLyricsPaneGeometry.centeredTranslation(2000, 96, 808, 0f)!!, .01f)
    }
    @Test fun singleColumnAndUnmeasuredHostsAreRejected() {
        assertNull(TabletLyricsPaneGeometry.centeredTranslation(2000, 96, 1808, 0f))
        assertNull(TabletLyricsPaneGeometry.centeredTranslation(0, 0, 1, 0f))
        assertNull(TabletLyricsPaneGeometry.centeredTranslation(2000, 96, 0, 0f))
        assertNull(TabletLyricsPaneGeometry.centeredTranslation(2000, 1800, 800, 0f))
    }
}
