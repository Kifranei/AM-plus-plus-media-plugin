package dev.kifranei.ampp.media

import android.app.Application
import android.view.View
import dev.amenhancer.plugin.api.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.lang.reflect.Executable

class PluginProfileSelectionTest {
    private val assets = File("src/main/assets")
    private val sources = File("src/main/java")
    private val supported = listOf(1606L, 1607L, 1609L, 1611L, 1613L, 1614L)
    // 1614 dropped the "-beta" suffix from versionName; selection stays exact on the name each build reports.
    private fun versionName(code: Long) = if (code >= 1614L) "7.0.0" else "7.0.0-beta"

    private fun entries() = ProfileIndex.parse(File(assets, ProfileIndex.ASSET).readText())
    private fun document(entry: ProfileEntry) = JSONObject(File(assets, entry.asset).readText())

    private class Host(val pkg: String, val name: String, val code: Long, val files: File) : PluginContext {
        override fun getApplication(): Application = error("Not used")
        override fun getHostClassLoader(): ClassLoader = javaClass.classLoader!!
        override fun getHostPackageName() = pkg
        override fun getHostVersionName() = name
        override fun getHostVersionCode() = code
        override fun getDataDirectory(): File = error("Not used")
        override fun getCacheDirectory(): File = error("Not used")
        override fun openAsset(path: String): InputStream = File(files, path).inputStream()
        override fun log(message: String, error: Throwable?) = Unit
        override fun onClose(action: Runnable) = Unit
        override fun claimResource(key: String, exclusive: Boolean) = PluginRegistration { }
        override fun getHooks(): PluginHooks = object : PluginHooks {
            override fun hook(target: Executable, exclusive: Boolean, callback: PluginHook) = PluginRegistration { }
            override fun observe(target: Executable, callback: PluginObserver) = PluginRegistration { }
        }
    }

    @Test fun `index ships exactly the supported versions and each asset exists`() {
        assertEquals(supported, entries().map { it.versionCode })
        entries().forEach { assertTrue(it.asset, File(assets, it.asset).isFile) }
        // Every applemusic-*.json asset must be indexed, so adding a version cannot be half done.
        assertEquals(entries().map { it.asset }.toSet(),
            assets.listFiles()!!.map { it.name }.filter { it.startsWith("applemusic-") && it.endsWith(".json") }.toSet())
    }

    @Test fun `selection is exact on package version name and code`() {
        val all = entries()
        supported.forEach { code ->
            assertEquals(code, ProfileIndex.select(all, "com.apple.android.music", versionName(code), code)?.versionCode)
        }
        assertNull(ProfileIndex.select(all, "com.apple.android.music", "7.0.0-beta", 1608))
        assertNull(ProfileIndex.select(all, "com.apple.android.music", "7.0.0-beta", 1612))
        assertNull(ProfileIndex.select(all, "com.apple.android.music", "7.0.0-beta", 1614))
        assertNull(ProfileIndex.select(all, "com.apple.android.music", "7.0.0", 1615))
        assertNull(ProfileIndex.select(all, "com.apple.android.music", "7.0.0-beta", 1615))
        assertNull(ProfileIndex.select(all, "com.apple.android.music", "7.0.0", 1611))
        assertNull(ProfileIndex.select(all, "com.example.music", "7.0.0-beta", 1611))
    }

    @Test fun `duplicate versions are rejected`() {
        val text = """{"packageName":"p","profiles":[{"versionName":"a","versionCode":1,"asset":"x"},{"versionName":"a","versionCode":1,"asset":"y"}]}"""
        assertThrows(IllegalStateException::class.java) { ProfileIndex.parse(text) }
    }

    @Test fun `load selects the profile for the host build and find matches only it`() {
        supported.forEach { code ->
            PluginProfiles.load(Host("com.apple.android.music", versionName(code), code, assets))
            assertEquals(code, PluginProfiles.document.getLong("versionCode"))
            assertNotNull(PluginProfiles.find("com.apple.android.music", versionName(code), code))
            assertNull(PluginProfiles.find("com.apple.android.music", versionName(code), code + 1))
        }
    }

    @Test fun `unsupported host throws with the supported list`() {
        val error = assertThrows(PluginUnsupportedException::class.java) {
            PluginProfiles.load(Host("com.apple.android.music", "7.0.0-beta", 1608, assets))
        }
        supported.forEach { assertTrue(error.message, error.message!!.contains("${versionName(it)} / $it")) }
        assertTrue(error.message, error.message!!.contains("1608"))
        assertThrows(PluginUnsupportedException::class.java) {
            PluginProfiles.load(Host("com.example.music", "7.0.0-beta", 1611, assets))
        }
    }

    @Test fun `lyricon pointer field is the SongInfoPtr field verified on the lyrics install owner`() {
        // 1613 shipped the 1611 name g1, which still exists on PlayerLyricsViewFragment but is an int there,
        // so LyriconIntegration's check(type == SongInfoPtr) failed at startup.
        val pointer = "Lcom/apple/android/music/ttml/javanative/model/SongInfo\$SongInfoPtr;"
        entries().forEach { entry ->
            val doc = document(entry)
            val owner = doc.getJSONObject("indexed").getJSONObject("methodContracts")
                .getJSONObject("lyrics-install-method").getString("owner")
            val fields = doc.getJSONObject("verification").getJSONObject("fieldTypes")
                .getJSONObject("L" + owner.replace('.', '/') + ";")
            val field = doc.getJSONObject("lyricon").getString("installedPointerField")
            assertEquals("${entry.asset} lyricon.installedPointerField", pointer, fields.optString(field))
            assertEquals("${entry.asset} SongInfoPtr fields", listOf(field),
                fields.keys().asSequence().filter { fields.getString(it) == pointer }.toList())
        }
    }

    @Test fun `profile types resolve array descriptors`() {
        PluginProfiles.load(Host("com.apple.android.music", "7.0.0-beta", 1613, assets))
        assertEquals(Array<String>::class.java, PluginProfiles.type("[Ljava.lang.String;"))
        assertEquals(java.lang.Float.TYPE, PluginProfiles.type("float"))
    }

    @Test fun `each profile describes its own build and carries every section the code reads`() {
        val sections = listOf("audioOutput", "lyricon", "audioQualityDialog", "cardSharing", "indexed", "verification",
            "playerMoreMenu", "playerDialogs", "playerVolume", "playerRecovery", "contentDownloads")
        entries().forEach { entry ->
            val doc = document(entry)
            assertEquals(entry.packageName, doc.getString("packageName"))
            assertEquals(entry.versionName, doc.getString("versionName"))
            assertEquals(entry.versionCode, doc.getLong("versionCode"))
            assertEquals(entry.versionCode.toString(), doc.getJSONObject("verification").getString("version_code"))
            sections.forEach { assertTrue("${entry.asset} lacks $it", doc.has(it)) }
        }
    }

    @Test fun `each profile defines every contract key referenced by the plugin code`() {
        val text = sources.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val methods = Regex("""PluginProfiles\.method\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] }.toSet()
        val fields = Regex("""PluginProfiles\.field\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(methods.size > 20 && fields.size > 20)
        entries().forEach { entry ->
            val indexed = document(entry).getJSONObject("indexed")
            val method = indexed.getJSONObject("methodContracts")
            val field = indexed.getJSONObject("fieldContracts")
            methods.forEach { assertTrue("${entry.asset} lacks method contract $it", method.has(it)) }
            fields.forEach { assertTrue("${entry.asset} lacks field contract $it", field.has(it)) }
        }
    }

    @Test fun `more menu click arguments bind by type for both parameter orders`() {
        val view = Any(); val item = Any()
        val types = { names: List<Class<*>> -> names.toTypedArray() }
        // 1606/1607/1609/1613/1614: (int, View, item)
        assertArrayEquals(arrayOf<Any?>(3, view, item), bindMoreMenuClickArguments(
            types(listOf(Integer.TYPE, View::class.java, Any::class.java)), 3, view, item))
        // 1611: (item, View, int)
        assertArrayEquals(arrayOf<Any?>(item, view, 3), bindMoreMenuClickArguments(
            types(listOf(Any::class.java, View::class.java, Integer.TYPE)), 3, view, item))
    }

    @Test fun `profiles record the click parameter order the binder handles`() {
        entries().forEach { entry ->
            val params = document(entry).getJSONObject("indexed").getJSONObject("methodContracts")
                .getJSONObject("more-action-click").getJSONArray("parameters")
            val list = (0 until params.length()).map(params::getString)
            assertEquals(entry.asset, setOf("int", "android.view.View", "com.apple.android.music.model.CollectionItemView"), list.toSet())
            // 1611 is the only build with (item, View, int); 1613 and 1614 use (int, View, item).
            val expected = if (entry.versionCode == 1611L) listOf("com.apple.android.music.model.CollectionItemView", "android.view.View", "int")
                else listOf("int", "android.view.View", "com.apple.android.music.model.CollectionItemView")
            assertEquals(entry.asset, expected, list)
        }
    }
    @Test fun `diagnostic plugin build matches plugin json`() {
        val manifest = JSONObject(File("plugin.json").readText())
        assertEquals("${manifest.getString("versionName")}/${manifest.getInt("versionCode")}", PLUGIN_BUILD)
    }
}
