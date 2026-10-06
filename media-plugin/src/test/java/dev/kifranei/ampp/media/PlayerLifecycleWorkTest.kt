package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerLifecycleWorkTest {
    @Test fun reentrantAccessorAndRepeatedRequestsDoNotCreateFeedback() {
        val posted = mutableListOf<Runnable>()
        var calls = 0
        lateinit var tasks: PlayerLifecycleWork<Any>
        val owner = Any()
        tasks = PlayerLifecycleWork(posted::add, posted::remove, { true }) { calls++; tasks.request(it) }
        repeat(10) { tasks.request(owner) }
        assertEquals(1, posted.size)
        posted.removeAt(0).run()
        assertEquals(1, calls)
        assertTrue(posted.isEmpty())
    }
    @Test fun destroyedPageCannotReviveUntilItsNextCreation() {
        val posted = mutableListOf<Runnable>()
        var calls = 0
        val owner = Any()
        val tasks = PlayerLifecycleWork<Any>(posted::add, posted::remove, { true }) { calls++ }
        tasks.created(owner)
        val stale = posted.single()
        tasks.destroyed(owner)
        stale.run()
        tasks.request(owner)
        assertEquals(0, calls); assertTrue(posted.isEmpty())
        tasks.created(owner)
        stale.run()
        assertEquals(1, posted.size)
        posted.removeAt(0).run()
        assertEquals(1, calls)
    }
    @Test fun closedScopeDiscardsAlreadyDispatchedWork() {
        val posted = mutableListOf<Runnable>()
        var active = true
        var calls = 0
        val tasks = PlayerLifecycleWork<Any>(posted::add, posted::remove, { active }) { calls++ }
        tasks.request(Any())
        val stale = posted.single()
        active = false; tasks.close(); stale.run()
        assertEquals(0, calls); assertTrue(posted.isEmpty())
    }
}
