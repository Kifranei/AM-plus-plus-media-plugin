package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerViewBootstrapTest {
    @Test fun `a player created before plugin startup binds from getView without recursive feedback`() {
        val owner = Any(); val root = Any(); var calls = 0
        lateinit var bootstrap: PlayerViewBootstrap<Any, Any>
        bootstrap = PlayerViewBootstrap { receiver, view ->
            calls++
            bootstrap.observed(receiver, view)
        }
        repeat(100) { bootstrap.observed(owner, root) }
        assertEquals(1, calls)
    }
    @Test fun `old getView results after destroy cannot resurrect a detached player`() {
        val owner = Any(); val old = Any(); val replacement = Any(); val roots = ArrayList<Any>()
        val bootstrap = PlayerViewBootstrap<Any, Any> { _, root -> roots += root }
        bootstrap.observed(owner, old)
        bootstrap.destroyed(owner)
        repeat(100) { bootstrap.observed(owner, old); bootstrap.observed(owner, replacement) }
        bootstrap.created(owner, replacement)
        bootstrap.observed(owner, replacement)
        assertEquals(listOf(old, replacement), roots)
    }
    @Test fun `native view replacement on the same fragment binds the new root exactly once`() {
        val owner = Any(); val first = Any(); val second = Any(); val roots = ArrayList<Any>()
        val bootstrap = PlayerViewBootstrap<Any, Any> { _, root -> roots += root }
        bootstrap.observed(owner, first)
        bootstrap.created(owner, first)
        bootstrap.observed(owner, second)
        bootstrap.observed(owner, second)
        assertEquals(listOf(first, second), roots)
    }
}
