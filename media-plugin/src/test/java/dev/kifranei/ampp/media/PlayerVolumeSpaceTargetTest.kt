package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeSpaceTargetTest {
    private class Node(val id: String, val height: Int, val constraint: Boolean = false, val parent: Node? = null)

    private fun target(controls: Node) = PlayerVolumeSpaceTarget.find(controls,
        isConstraint = { it.constraint && it.height == 0 }, parent = { it.parent },
        isWrapper = { it.id == "controls" }, fillsParent = { it.height == -1 })

    @Test fun `song reserves its percent controls and lyrics and queue reserve their immediate wrapper`() {
        val songPane = Node("player_container", -1)
        val song = Node("player_controls", 0, constraint = true, parent = songPane)
        assertSame(song, target(song))
        listOf("lyrics", "queue").forEach { page ->
            val pane = Node(page, -1)
            val wrapper = Node("controls", 0, constraint = true, parent = pane)
            val controls = Node("player_controls", -1, parent = wrapper)
            assertSame(wrapper, target(controls))
        }
    }

    @Test fun `fixed shared-element ancestor must never become a reservation target`() {
        val animation = Node("animation", 2130)
        val pane = Node("container", -1, parent = animation)
        val controls = Node("player_controls", -1, parent = pane)
        assertNull(target(controls))
        assertNull(target(Node("player_controls", 981, constraint = true, parent = pane)))
    }

    @Test fun `missing or changed wrapper cannot redirect a reservation to another constraint ancestor`() {
        val ancestor = Node("container", 0, constraint = true)
        val wrapper = Node("controls", -1, parent = ancestor)
        assertNull(target(Node("player_controls", -1, parent = wrapper)))
        val other = Node("current_player_item", 0, constraint = true, parent = ancestor)
        assertNull(target(Node("player_controls", -1, parent = other)))
        assertNull(target(Node("player_controls", -1)))
    }

    @Test fun `wrap-content controls cannot consume an otherwise valid parent wrapper`() {
        val wrapper = Node("controls", 0, constraint = true)
        assertNull(target(Node("player_controls", -2, parent = wrapper)))
    }
}
