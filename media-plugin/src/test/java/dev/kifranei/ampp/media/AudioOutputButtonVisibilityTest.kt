package dev.kifranei.ampp.media

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputButtonVisibilityTest {
    private class NativeButton(initial: Int = View.INVISIBLE) {
        val state = AudioOutputButtonVisibility(initial)
        var visibility = initial
        var shareplay = false
        var writes = 0
        fun nativeRequest(value: Int) {
            writes++
            visibility = state.requested(value, shareplay)
        }
        fun draw() = state.synchronize(visibility, shareplay, ::nativeRequest)
        fun unload() = state.restore { visibility = it }
    }

    @Test fun `cold cover page and later unavailable Cast callbacks stay visible without changing panes`() {
        val button = NativeButton()
        button.nativeRequest(View.INVISIBLE) // Cast initialization, no available route.
        assertEquals(View.VISIBLE, button.visibility)
        button.draw()
        button.nativeRequest(View.INVISIBLE) // Bluetooth changes the native route provider.
        assertEquals(View.VISIBLE, button.visibility)
        button.nativeRequest(View.GONE) // A later binding must not remove the system picker.
        assertEquals(View.VISIBLE, button.visibility)
        button.unload()
        assertEquals(View.GONE, button.visibility)
    }

    @Test fun `SharePlay binding order is reconciled before drawing and ending the session restores output`() {
        val button = NativeButton()
        button.nativeRequest(View.INVISIBLE)
        button.shareplay = true // Native binding updates the badge after the route button.
        button.draw()
        assertEquals(View.INVISIBLE, button.visibility)
        button.nativeRequest(View.VISIBLE) // A route callback cannot overlap the shared slot.
        assertEquals(View.INVISIBLE, button.visibility)
        button.shareplay = false // No additional route callback is required on session end.
        button.draw()
        assertEquals(View.VISIBLE, button.visibility)
        button.unload()
        assertEquals(View.VISIBLE, button.visibility)
    }

    @Test fun `discovering an already hidden button preserves native state and repeated frames do not write`() {
        val button = NativeButton(View.GONE)
        button.draw()
        assertEquals(View.VISIBLE, button.visibility)
        val firstWrites = button.writes
        repeat(120) { button.draw() }
        assertEquals(firstWrites, button.writes)
        button.unload()
        assertEquals(View.GONE, button.visibility) // Internal setVisibility must not replace the native request.
    }

    @Test fun `failed synchronization clears the internal write guard for the next native request`() {
        val state = AudioOutputButtonVisibility(View.INVISIBLE)
        runCatching { state.synchronize(View.INVISIBLE, false) { error("detached view") } }
        state.requested(View.GONE, false)
        var restored = View.VISIBLE
        state.restore { restored = it }
        assertEquals(View.GONE, restored)
    }
}
