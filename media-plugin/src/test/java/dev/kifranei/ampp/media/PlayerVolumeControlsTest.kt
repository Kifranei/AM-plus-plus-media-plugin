package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerVolumeControlsTest {
    private class Node(val name: String, val controls: Boolean = false, val list: Boolean = false,
        var opacity: Float? = null, val children: List<Node> = emptyList())

    @Test fun `cached duplicate controls follow cover lyrics and queue without changing the volume view`() {
        val song = Node("song", controls = true, opacity = 1f)
        val lyrics = Node("lyrics", controls = true)
        val queue = Node("queue", controls = true)
        val groups = PlayerVolumeControls.discover(Node("player", children = listOf(song, lyrics, queue)),
            { it.controls }, { it.children })
        assertEquals(listOf(song, lyrics, queue), groups)
        assertSame(song, PlayerVolumeControls.select(groups) { it.opacity })
        song.opacity = null; lyrics.opacity = 1f
        assertSame(lyrics, PlayerVolumeControls.select(groups) { it.opacity })
        lyrics.opacity = null; queue.opacity = 1f
        assertSame(queue, PlayerVolumeControls.select(groups) { it.opacity })
        queue.opacity = null; song.opacity = 1f
        assertSame(song, PlayerVolumeControls.select(groups) { it.opacity })
    }
    @Test fun `pane crossfade keeps a control group and prefers the layer drawn on top at equal opacity`() {
        val song = Node("song", opacity = .6f)
        val lyrics = Node("lyrics", opacity = .4f)
        val groups = listOf(song, lyrics)
        assertSame(song, PlayerVolumeControls.select(groups) { it.opacity })
        song.opacity = .5f; lyrics.opacity = .5f
        assertSame(lyrics, PlayerVolumeControls.select(groups) { it.opacity })
        song.opacity = 0f; lyrics.opacity = 1f
        assertSame(lyrics, PlayerVolumeControls.select(groups) { it.opacity })
        lyrics.opacity = Float.NaN
        assertNull(PlayerVolumeControls.select(groups) { it.opacity })
    }
    @Test fun `discovery stops at control groups and excludes scrolling lyrics and queue content`() {
        val nested = Node("duplicate play IDs", controls = true)
        val controls = Node("controls", controls = true, children = listOf(nested))
        val list = Node("lyrics rows", list = true, children = listOf(Node("row", controls = true)))
        var listVisited = false
        val groups = PlayerVolumeControls.discover(Node("player", children = listOf(list, controls)),
            { it.controls }) { node ->
                if (node.list) { listVisited = true; emptyList() } else node.children
            }
        assertTrue(listVisited)
        assertEquals(listOf(controls), groups)
    }
}
