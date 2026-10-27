package dev.kifranei.ampp.media

import android.content.res.Configuration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CardRenderDayModeTest {
    @Test fun `day ui mode replaces only the night bits`() {
        val type = Configuration.UI_MODE_TYPE_NORMAL
        assertEquals(type or Configuration.UI_MODE_NIGHT_NO, CardRenderDayMode.dayUiMode(type or Configuration.UI_MODE_NIGHT_YES))
        assertEquals(type or Configuration.UI_MODE_NIGHT_NO, CardRenderDayMode.dayUiMode(type or Configuration.UI_MODE_NIGHT_NO))
        assertEquals(type or Configuration.UI_MODE_NIGHT_NO, CardRenderDayMode.dayUiMode(type or Configuration.UI_MODE_NIGHT_UNDEFINED))
        assertEquals(Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO,
            CardRenderDayMode.dayUiMode(Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES))
        assertFalse(CardRenderDayMode.isNight(CardRenderDayMode.dayUiMode(type or Configuration.UI_MODE_NIGHT_YES)))
    }

    @Test fun `night detection reads only the night mask`() {
        assertTrue(CardRenderDayMode.isNight(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES))
        assertFalse(CardRenderDayMode.isNight(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO))
        assertFalse(CardRenderDayMode.isNight(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test fun `render window follows nested host render calls`() {
        val window = CardRenderWindow()
        assertFalse(window.isActive)
        window.enter(true)
        assertTrue(window.isActive)
        window.enter(false) // a native (non-plugin) render nested in ours is never pinned
        assertFalse(window.isActive)
        window.exit()
        assertTrue(window.isActive)
        window.exit()
        assertFalse(window.isActive)
        window.exit() // unbalanced exit is harmless
        assertFalse(window.isActive)
        window.enter(false)
        assertFalse(window.isActive)
        window.exit()
    }

    @Test fun `render window is per thread and clearable`() {
        val window = CardRenderWindow()
        window.enter(true)
        val other = AtomicBoolean(true)
        Thread { other.set(window.isActive) }.apply { start(); join() }
        assertFalse(other.get())
        assertTrue(window.isActive)
        window.clear()
        assertFalse(window.isActive)
    }

    @Test fun `every profile names the card renderer members the integration reads`() {
        val source = File("src/main/java/dev/kifranei/ampp/media/CardSharingIntegration.kt").readText()
        val keys = Regex("""names\.getString\("([^"]+)"\)""").findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue(keys.containsAll(listOf("stickerViewClass", "rendererMethod", "rendererOptionGetter")))
        val assets = File("src/main/assets")
        val profiles = assets.listFiles()!!.filter { it.name.startsWith("applemusic-") && it.name.endsWith(".json") }
        assertEquals(6, profiles.size)
        profiles.forEach { file ->
            val card = JSONObject(file.readText()).getJSONObject("cardSharing")
            keys.forEach { assertTrue("${file.name} cardSharing lacks $it", card.optString(it).isNotEmpty()) }
            assertEquals("subscribeToSharePlatformIntent", card.getString("rendererMethod"))
            assertEquals("getShareOptionItem", card.getString("rendererOptionGetter"))
            assertTrue(card.getString("stickerViewClass").startsWith("com.apple.android.music.common.views."))
        }
    }
}
