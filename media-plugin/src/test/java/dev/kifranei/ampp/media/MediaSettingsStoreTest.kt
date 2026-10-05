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
        assertEquals(MediaSettings(), MediaSettings.decode("{\"lyricon\":\"true\",\"ios_controls\":1}"))
    }
    @Test fun `sequential writes preserve independent flags and survive a new store`() {
        val store = MediaSettingsStore(temporary.root)
        store.write(MediaSettings(lyricon = true, qualityGlass = true))
        store.write(store.read().copy(iosControls = true))
        assertEquals(MediaSettings(lyricon = true, iosControls = true, qualityGlass = true), MediaSettingsStore(temporary.root).read())
        assertFalse(File(temporary.root, "settings.json.tmp").exists())
    }
    @Test fun `failed write leaves the previously saved settings intact`() {
        val store = MediaSettingsStore(temporary.root)
        store.write(MediaSettings(lyricsSharing = true))
        File(temporary.root, "settings.json.tmp").mkdir()
        assertThrows(Exception::class.java) { store.write(MediaSettings(lyricon = true)) }
        assertEquals(MediaSettings(lyricsSharing = true), store.read())
    }
}
