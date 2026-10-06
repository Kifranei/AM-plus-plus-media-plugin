package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class TabletPaneTransitionGateTest {
    @Test fun phonePaneAnimationsNeverEnterTheTabletButtonHidingPath() {
        listOf(360, 411, 530, 599).forEach { width ->
            assertEquals(PlayerVolumeMotion.Mode.HIDE, TabletPlayerPresentationMode.mode(width, 3, 1f, true))
            assertEquals(PlayerVolumeMotion.Mode.HIDE, TabletPlayerPresentationMode.mode(width, 3, 1f, false))
        }
        assertEquals(PlayerVolumeMotion.Mode.HOLD, TabletPlayerPresentationMode.mode(600, 3, 1f, true))
        assertEquals(PlayerVolumeMotion.Mode.LAYOUT, TabletPlayerPresentationMode.mode(800, 3, 1f, false))
        assertEquals(PlayerVolumeMotion.Mode.HOLD, TabletPlayerPresentationMode.mode(800, 3, .5f, true))
    }
    @Test fun shallowSheetDragsRetainTabletGeometryAndSettledStateIgnoresTheLastDragFraction() {
        repeat(30) {
            assertEquals(PlayerVolumeMotion.Mode.HOLD, TabletPlayerPresentationMode.mode(800, 1, .97f, false, true))
            assertEquals(PlayerVolumeMotion.Mode.HOLD, TabletPlayerPresentationMode.mode(800, 2, .985f, false, true))
            // AM may not deliver a final slide(1) callback before its expanded state callback.
            assertEquals(PlayerVolumeMotion.Mode.LAYOUT, TabletPlayerPresentationMode.mode(800, 3, .985f, false, true))
            assertEquals(PlayerVolumeMotion.Mode.LAYOUT, TabletPlayerPresentationMode.mode(800, 3, Float.NaN, false, true))
        }
        assertFalse(TabletPlayerPresentationMode.nearExpanded(1, .98f, false)) // No cold-start frame to hold.
        assertFalse(TabletPlayerPresentationMode.nearExpanded(2, Float.NaN, true))
        assertTrue(TabletPlayerPresentationMode.suspend(1, true)) // Deep drag hides proxies, retains styles.
        assertFalse(TabletPlayerPresentationMode.suspend(4, true)) // Actual collapse releases ownership.
        assertEquals(PlayerVolumeMotion.Mode.HIDE, TabletPlayerPresentationMode.mode(800, 4, .99f, false, true))
    }
    @Test fun outgoingOrUnlaidOutControlsCannotReleaseTheHeldForeground() {
        assertFalse(TabletPaneTransitionGate.ready(1, 16, true))
        assertFalse(TabletPaneTransitionGate.ready(2, 32, false))
        assertTrue(TabletPaneTransitionGate.ready(2, 32, true))
    }
    @Test fun cancelledNativeHandoffCannotKeepThePageFrozen() {
        assertFalse(TabletPaneTransitionGate.ready(40, 749, false))
        assertTrue(TabletPaneTransitionGate.ready(40, 750, false))
    }
}
