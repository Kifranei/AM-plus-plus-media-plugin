package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class MenuSurfaceCleanupTest {
    @Test fun `framework detach stops work now and removes hierarchy only after traversal`() {
        val queued = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        var insideDetach = true
        val cleanup = MenuSurfaceCleanup(queued::add,
            stop = { events += "stop" },
            restore = {
                check(!insideDetach) { "A second detach would crash Compose accessibility" }
                events += "restore"
            })
        cleanup.close(insideWindowDetach = true)
        assertTrue(cleanup.closed)
        assertEquals(listOf("stop"), events)
        // Dialog.dismiss and Fragment destruction can both follow the detach callback.
        cleanup.close()
        cleanup.close(insideWindowDetach = true)
        assertEquals(1, queued.size)
        insideDetach = false
        queued.single().invoke()
        assertEquals(listOf("stop", "restore"), events)
    }

    @Test fun `explicit removal cannot reenter cleanup when it dispatches detach`() {
        val queued = mutableListOf<() -> Unit>()
        var stops = 0
        var removals = 0
        lateinit var cleanup: MenuSurfaceCleanup
        cleanup = MenuSurfaceCleanup(queued::add, stop = { stops++ }, restore = {
            removals++
            cleanup.close(insideWindowDetach = true)
        })
        cleanup.close()
        cleanup.close()
        assertEquals(1, stops)
        assertEquals(1, removals)
        assertTrue(queued.isEmpty())
    }
}
