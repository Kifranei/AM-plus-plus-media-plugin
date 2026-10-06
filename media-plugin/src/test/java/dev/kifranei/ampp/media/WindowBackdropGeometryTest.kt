package dev.kifranei.ampp.media

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowBackdropGeometryTest {
    @Test fun `lowered menu samples playback pixels at the same screen height`() {
        assertEquals(Pair(0f, 644f), WindowBackdropGeometry.origin(0, 0, 0, 644, 0f, 0f))
        assertEquals(Pair(0f, 1200f), WindowBackdropGeometry.origin(0, 0, 0, 1200, 0f, 0f))
    }
    @Test fun `dialog screen offsets and content padding are included once`() {
        assertEquals(Pair(60f, -10f), WindowBackdropGeometry.origin(0, 130, 50, 100, 10f, 20f))
    }
    @Test fun `compose bleed cancels its own content offset`() {
        assertEquals(Pair(30f, 700f), WindowBackdropGeometry.origin(0, 0, -74, 596, 104f, 104f))
    }

    @Test fun `drag invalidates observed drawing before another background capture`() {
        val position = WindowBackdropPosition()
        Snapshot.sendApplyNotifications()
        var redraws = 0
        val scope = Any()
        val changed: (Any) -> Unit = { redraws++ }
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            for (height in listOf(644f, 660f, 700f, 680f)) {
                observer.observeReads(scope, changed) { position.origin }
                position.update(Pair(0f, height))
                assertEquals(Pair(0f, height), position.origin)
            }
            // No frame clock or PixelCopy callback was dispatched between moves.
            assertEquals(4, redraws)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun `stationary menu does not request another position redraw`() {
        val position = WindowBackdropPosition()
        position.update(Pair(12f, 644f))
        var redraws = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> redraws++ }) { position.origin }
            repeat(10) { position.update(Pair(12f, 644f)) }
            Snapshot.sendApplyNotifications()
            assertEquals(0, redraws)
        } finally { observer.stop(); observer.clear() }
    }
}
