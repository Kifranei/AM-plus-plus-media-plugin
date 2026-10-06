package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerMenuLayoutTest {
    @Test fun `quick actions and sections preserve every native command once`() {
        val actions = listOf("ADD_TO_LIBRARY", "ADD_TO_PLAYLIST", "SHARE_SONG", "SHARE_LYRICS", "CREATE_STATION",
            "VIEW_SONG_CREDITS", "SLEEP_TIMER", "FAVORITE_ITEM", "SUGGEST_LESS", "OPEN_ALBUM", "OPEN_ARTIST")
            .mapIndexed { index, key -> PlayerMenuAction(key, index + 1) }
        val result = PlayerMenuLayout.groups(actions)
        assertEquals(listOf("ADD_TO_LIBRARY", "FAVORITE_ITEM", "SHARE_SONG"), result.shortcuts.map { it?.key })
        val flattened = result.shortcuts.filterNotNull() + result.sections.flatten()
        assertEquals(actions.toSet(), flattened.toSet())
        assertEquals(actions.size, flattened.size)
        assertEquals("SUGGEST_LESS", result.sections.last().single().key)
    }
    @Test fun `downloaded and favorite states retain their native undo command`() {
        val actions = listOf("REMOVE_DOWNLOAD", "UNDO_FAVORITE_ITEM", "SHARE_LYRICS", "SHARE_STATION")
            .mapIndexed { i, key -> PlayerMenuAction(key, i + 1) }
        val result = PlayerMenuLayout.groups(actions)
        assertEquals(listOf("REMOVE_DOWNLOAD", "UNDO_FAVORITE_ITEM", "SHARE_STATION"), result.shortcuts.map { it?.key })
        assertEquals("SHARE_LYRICS", result.sections.single().single().key)
    }
    @Test fun `unavailable shortcuts remain absent instead of dispatching a different action`() {
        val action = PlayerMenuAction("SLEEP_TIMER", 8)
        val result = PlayerMenuLayout.groups(listOf(action))
        assertEquals(listOf(null, null, null), result.shortcuts)
        assertEquals(listOf(listOf(action)), result.sections)
    }
    @Test fun `long menu floats clear of system insets and keeps room for player controls`() {
        val result = PlayerMenuLayout.bounds(1280, 2772, 3.25f, 130, 80, 2400)
        assertEquals((1280 * .68f).toInt(), result.width)
        assertTrue(result.left + result.width <= 1280 - 78)
        assertTrue(result.top >= 130 + 39)
        assertTrue(result.top + result.height <= (2772 * .76f).toInt())
        assertTrue(result.height < 2400)
    }
    @Test fun `short and long menus keep the original upper right position`() {
        val result = PlayerMenuLayout.bounds(393, 852, 1f, 44, 34, 180)
        assertEquals(56, result.top)
        assertEquals(393 - 24, result.left + result.width)
        assertEquals(180, result.height)
        val longer = PlayerMenuLayout.bounds(393, 852, 1f, 44, 34, 2400)
        assertEquals(result.left, longer.left)
        assertEquals(result.top, longer.top)
        val edge = PlayerMenuLayout.bounds(240, 320, 1f, 20, 20, 2000)
        assertTrue(edge.left >= 24)
        assertTrue(edge.top >= 32)
        assertTrue(edge.left + edge.width <= 216)
        assertTrue(edge.top + edge.height <= 276)
    }
    @Test fun `reference phone viewport restores the original card corner`() {
        // Previous iOS menu screenshot and hierarchy: 1280x2772, 520 dpi, status bar 152 px.
        val result = PlayerMenuLayout.bounds(1280, 2772, 3.25f, 152, 80, 2400)
        assertEquals(332, result.left)
        assertEquals(191, result.top)
        assertEquals(870, result.width)
    }
    @Test fun `display cutouts on either side remain outside the menu`() {
        val result = PlayerMenuLayout.bounds(1280, 2772, 3.25f, 130, 80, 2400, 120, 60)
        assertTrue(result.left >= 120 + 78)
        assertEquals(1280 - 60 - 78, result.left + result.width)
        assertTrue(result.top >= 130 + 39)
    }
    @Test fun `system bars already excluded from dialog content are not counted twice`() {
        val result = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(0, 130, 1280, 2562),
            PlayerMenuBounds(0, 0, 1280, 2772),
            PlayerMenuInsets(0, 130, 0, 80),
        )
        assertEquals(PlayerMenuInsets(0, 0, 0, 0), result)
    }
    @Test fun `offset windows keep local safety margins when their screen origin changes`() {
        val bars = PlayerMenuInsets(20, 50, 24, 32)
        val first = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(120, 300, 1024, 900), PlayerMenuBounds(120, 300, 1024, 900), bars)
        val moved = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(480, 620, 1024, 900), PlayerMenuBounds(480, 620, 1024, 900), bars)
        assertEquals(bars, first)
        assertEquals(first, moved)
        val contentOnly = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(140, 350, 980, 818), PlayerMenuBounds(120, 300, 1024, 900), bars)
        assertEquals(PlayerMenuInsets(0, 0, 0, 0), contentOnly)
    }
    @Test fun `viewport and density changes always produce a visible bounded card`() {
        for ((width, height) in listOf(240 to 320, 393 to 852, 1280 to 2772, 2772 to 1280, 1 to 1)) {
            for (density in listOf(.75f, 1f, 2.625f, 3.25f, 4f)) {
                val result = PlayerMenuLayout.bounds(width, height, density, height / 20, height / 24,
                    5000, width / 20, width / 30)
                assertTrue(result.width > 0 && result.height > 0)
                assertTrue(result.left >= width / 20 && result.top >= height / 20)
                assertTrue(result.left + result.width <= width - width / 30)
                assertTrue(result.top + result.height <= height - height / 24)
            }
        }
        val narrow = PlayerMenuLayout.bounds(1, 1, Float.NaN, 100, 100, 2000, 100, 100)
        assertEquals(PlayerMenuBounds(0, 0, 1, 1), narrow)
    }
}
