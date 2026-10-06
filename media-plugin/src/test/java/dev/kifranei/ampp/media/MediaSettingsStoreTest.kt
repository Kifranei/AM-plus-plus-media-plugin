package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class MediaSettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `absent file and untyped booleans leave all features disabled`() {
        assertEquals(MediaSettings(), MediaSettingsStore(temporary.root).read())
        assertEquals(MediaSettings(), MediaSettings.decode("{\"lyricon\":\"true\",\"ios_controls\":1,\"player_glass\":\"true\",\"quality_glass\":1}"))
    }
    @Test fun `sequential writes preserve independent flags and survive a new store`() {
        val store = MediaSettingsStore(temporary.root)
        store.write(MediaSettings(lyricon = true, playerGlass = true))
        store.write(store.read().copy(iosControls = true))
        store.write(store.read().copy(iosControls = true, playerHandle = true, systemCorners = true))
        assertEquals(MediaSettings(lyricon = true, iosControls = true, playerGlass = true, playerHandle = true, systemCorners = true), MediaSettingsStore(temporary.root).read())
        assertFalse(File(temporary.root, "settings.json.tmp").exists())
    }
    @Test fun `legacy combined switch migrates to both separate switches`() {
        assertEquals(MediaSettings(playerHandle = true, systemCorners = true),
            MediaSettings.decode("{\"player_chrome\":true}"))
    }
    @Test fun `explicit separate switches override legacy and remain independent`() {
        val decoded = MediaSettings.decode("{\"player_chrome\":true,\"player_handle\":false,\"system_corners\":true,\"more_glass\":true}")
        assertFalse(decoded.playerHandle)
        assertTrue(decoded.systemCorners)
        assertTrue(decoded.playerGlass)
        assertEquals(decoded, MediaSettings.decode(decoded.encode()))
        assertFalse(decoded.encode().contains("player_chrome"))
        assertEquals(MediaSettings(playerHandle = true), MediaSettings.decode(MediaSettings(playerHandle = true).encode()))
    }
    @Test fun `unified glass switch can be disabled without changing volume or previous settings`() {
        val previous = MediaSettings.decode("{\"lyricon\":true,\"more_glass\":true}")
        assertTrue(previous.playerGlass); assertFalse(previous.volumeBar)
        val store = MediaSettingsStore(temporary.root)
        store.write(previous.copy(volumeBar = true))
        store.write(store.read().copy(playerGlass = false))
        assertEquals(previous.copy(playerGlass = false, volumeBar = true), MediaSettingsStore(temporary.root).read())
    }
    @Test fun `each old glass switch migrates to the unified setting and saves only the new key`() {
        val legacyKeys = listOf("quality_glass", "more_glass", "confirmation_glass", "sleep_glass")
        legacyKeys.forEach { key ->
            val decoded = MediaSettings.decode("{\"$key\":true,\"ios_controls\":true}")
            assertEquals(MediaSettings(iosControls = true, playerGlass = true), decoded)
            val saved = decoded.encode()
            assertTrue(saved.contains("player_glass"))
            legacyKeys.forEach { assertFalse(saved.contains(it)) }
            assertEquals(decoded, MediaSettings.decode(saved))
        }
        assertFalse(MediaSettings.decode("{\"quality_glass\":false,\"more_glass\":\"true\",\"sleep_glass\":1}").playerGlass)
    }
    @Test fun `explicit unified setting overrides legacy glass switches in both directions`() {
        assertFalse(MediaSettings.decode("{\"player_glass\":false,\"quality_glass\":true,\"more_glass\":true,\"confirmation_glass\":true,\"sleep_glass\":true}").playerGlass)
        assertTrue(MediaSettings.decode("{\"player_glass\":true,\"quality_glass\":false}").playerGlass)
    }
    @Test fun `song and lyrics sharing share one migrated flag and explicit disable wins`() {
        val migrated = MediaSettings.decode("{\"lyrics_sharing\":true,\"player_glass\":true}")
        assertEquals(MediaSettings(sharing = true, playerGlass = true), migrated)
        assertFalse(MediaSettings.decode("{\"lyrics_sharing\":true,\"sharing\":false}").sharing)
        assertTrue(MediaSettings.decode("{\"lyrics_sharing\":false,\"sharing\":true}").sharing)
        val store = MediaSettingsStore(temporary.root)
        store.write(migrated.copy(sharing = false))
        assertEquals(MediaSettings(playerGlass = true), MediaSettingsStore(temporary.root).read())
        assertFalse(store.read().encode().contains("lyrics_sharing"))
    }
    @Test fun `failed write leaves the previously saved settings intact`() {
        val store = MediaSettingsStore(temporary.root)
        store.write(MediaSettings(sharing = true))
        File(temporary.root, "settings.json.tmp").mkdir()
        assertThrows(Exception::class.java) { store.write(MediaSettings(lyricon = true)) }
        assertEquals(MediaSettings(sharing = true), store.read())
    }
    @Test fun `content downloads opt in without changing legacy media flags`() {
        val original = MediaSettings.decode("{\"ios_controls\":true,\"volume_bar\":true,\"lyrics_sharing\":true,\"more_glass\":true}")
        assertFalse(original.contentDownloads)
        val store = MediaSettingsStore(temporary.root)
        store.write(original.copy(contentDownloads = true))
        assertEquals(original.copy(contentDownloads = true), MediaSettingsStore(temporary.root).read())
        store.write(store.read().copy(contentDownloads = false))
        assertEquals(original, store.read())
    }
}
