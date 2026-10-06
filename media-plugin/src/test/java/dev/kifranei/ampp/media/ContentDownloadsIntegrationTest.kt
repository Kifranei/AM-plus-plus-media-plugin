package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ContentDownloadsIntegrationTest {
    @Test fun `button visible glyph follows the title with a bounded forty dp touch area`() {
        val title = ContentDownloadBounds(100f, 200f, 220f, 236f)
        val placed = checkNotNull(contentDownloadButtonPlacement(title, 360, 800, 1f, false))
        assertEquals(40, placed.size)
        val iconLeft = placed.left + placed.iconLeft
        assertTrue(iconLeft >= title.right + 4)
        assertTrue(placed.left >= 0 && placed.left + placed.size <= 360)
        assertTrue(placed.top >= 0 && placed.top + placed.size <= 800)
        assertEquals((title.top + title.bottom) / 2, placed.top + placed.iconTop + placed.iconSize / 2f, 1f)
    }

    @Test fun `RTL places the glyph before the native title and narrow edge uses the smaller icon`() {
        val title = ContentDownloadBounds(100f, 200f, 220f, 236f)
        val rtl = checkNotNull(contentDownloadButtonPlacement(title, 360, 800, 1f, true))
        assertTrue(rtl.left + rtl.iconLeft + rtl.iconSize <= title.left - 4)
        val narrow = checkNotNull(contentDownloadButtonPlacement(ContentDownloadBounds(16f, 50f, 344f, 85f), 360, 800, 1f, false))
        assertEquals(14, narrow.iconSize)
        assertTrue(narrow.iconLeft >= 0 && narrow.iconLeft + narrow.iconSize <= narrow.size)
    }

    @Test fun `offscreen invalid and impossible title positions cannot produce a guessed button`() {
        listOf(ContentDownloadBounds(0f, -10f, 100f, 20f), ContentDownloadBounds(0f, 780f, 100f, 820f),
            ContentDownloadBounds(Float.NaN, 0f, 100f, 20f), ContentDownloadBounds(0f, 0f, 0f, 0f)).forEach {
            assertNull(contentDownloadButtonPlacement(it, 360, 800, 1f, false))
        }
        assertNull(contentDownloadButtonPlacement(ContentDownloadBounds(0f, 20f, 360f, 50f), 360, 800, 1f, false))
        assertNull(contentDownloadButtonPlacement(ContentDownloadBounds(5f, 10f, 10f, 20f), 20, 800, 1f, false))
    }

    @Test fun `title normalization preserves identity while ignoring direction marks and repeated whitespace`() {
        assertEquals("艺术家 Artist", normalizeContentDownloadTitle("\u202e艺术家  \n Artist\u202c"))
        assertNotEquals(normalizeContentDownloadTitle("Artist"), normalizeContentDownloadTitle("Other Artist"))
    }

    @Test fun `native semantic properties are read by precise field contract without collecting unrelated metadata`() {
        val contracts = fakeContracts()
        val anchor = ContentDownloadComposeAnchor(contracts)
        val node = FakeNode(listOf(
            FakeKey("Text") to listOf("Artist"), FakeKey("Heading") to true,
            FakeKey("Authorization") to "NEVER_CAPTURE", FakeKey("GetTextLayoutResult") to FakeAction(FakeFunction(emptyList())),
        ))
        val values = anchor.semanticsProperties(node)
        assertEquals(setOf("Text", "Heading", "GetTextLayoutResult"), values.keys)
        assertFalse(values.toString().contains("NEVER_CAPTURE"))
    }

    @Test fun `native glyph layout uses only the last drawn title line and handles RTL coordinates`() {
        val contracts = fakeContracts()
        val anchor = ContentDownloadComposeAnchor(contracts)
        val result = anchor.glyphBounds(FakeAction(FakeFunction(listOf(
            FakeRect(100f, 0f, 110f, 20f), FakeRect(110f, 0f, 120f, 20f),
            FakeRect(110f, 24f, 120f, 44f), FakeRect(100f, 24f, 110f, 44f),
        ))), 4)
        assertEquals(ContentDownloadBounds(100f, 24f, 120f, 44f), result)
        assertNull(anchor.glyphBounds(FakeAction(FakeFunction(emptyList())), 0))
        assertNull(anchor.glyphBounds(FakeAction(FakeFunction(emptyList())), 513))
        assertNull(anchor.glyphBounds(null, 4))
    }

    @Test fun `tablet tight semantic bounds do not add wide paragraph glyph origins twice`() {
        val native = ContentDownloadBounds(1053f, 883f, 1508f, 974f)
        val line = ContentDownloadBounds(977f, 0f, 1430.3496f, 91f)
        val placed = checkNotNull(contentDownloadComposeGlyphBounds(native, ContentDownloadGlyphGeometry(line, line)))
        assertEquals(native, placed)
        val button = checkNotNull(contentDownloadButtonPlacement(placed, 2000, 1400, 1f, false))
        assertEquals(1512f, (button.left + button.iconLeft).toFloat(), 1f)
        assertEquals(ContentDownloadBounds(1073f, 913f, 1528f, 1004f), placed.translated(20f, 30f))
    }

    @Test fun `tight multiline semantic bounds retain the final line indentation relative to all text`() {
        val geometry = checkNotNull(ContentDownloadComposeAnchor(fakeContracts()).glyphGeometry(FakeAction(FakeFunction(listOf(
            FakeRect(977f, 0f, 1000f, 91f), FakeRect(1000f, 0f, 1430.3496f, 91f),
            FakeRect(1200f, 91f, 1300f, 182f), FakeRect(1100f, 91f, 1200f, 182f),
        ))), 4))
        assertEquals(ContentDownloadBounds(977f, 0f, 1430.3496f, 182f), geometry.text)
        assertEquals(ContentDownloadBounds(1100f, 91f, 1300f, 182f), geometry.lastLine)
        assertEquals(ContentDownloadBounds(1176f, 974f, 1376f, 1065f),
            contentDownloadComposeGlyphBounds(ContentDownloadBounds(1053f, 883f, 1508f, 1065f), geometry))
    }

    @Test fun `wide full layout semantic bounds retain legitimate paragraph alignment offsets`() {
        val line = ContentDownloadBounds(977f, 0f, 1430.3496f, 91f)
        val placed = checkNotNull(contentDownloadComposeGlyphBounds(ContentDownloadBounds(80f, 200f, 1880f, 291f), ContentDownloadGlyphGeometry(line, line)))
        assertEquals(1057f, placed.left, .01f)
        assertEquals(1510.3496f, placed.right, .01f)
        assertEquals(200f, placed.top, .01f)
        assertEquals(291f, placed.bottom, .01f)
    }

    @Test fun `clipped or mismatched full text bounds cannot invent a final-line offset`() {
        val text = ContentDownloadBounds(977f, 0f, 1430.3496f, 182f)
        val geometry = ContentDownloadGlyphGeometry(text, ContentDownloadBounds(1100f, 91f, 1300f, 182f))
        listOf(ContentDownloadBounds(1053f, 883f, 1453f, 1065f), ContentDownloadBounds(1053f, 883f, 1508f, 1003f),
            ContentDownloadBounds(1053f, 883f, 1508f, 974f), ContentDownloadBounds(Float.NaN, 0f, 455f, 182f)).forEach {
            assertNull(contentDownloadComposeGlyphBounds(it, geometry))
        }
        assertNull(contentDownloadComposeGlyphBounds(ContentDownloadBounds(0f, 0f, 455f, 182f),
            ContentDownloadGlyphGeometry(text, ContentDownloadBounds(1400f, 91f, 1500f, 182f))))
    }

    @Test fun `tight horizontal bounds and padded vertical bounds normalize each axis independently`() {
        val line = ContentDownloadBounds(977f, 20f, 1430.3496f, 111f)
        val geometry = ContentDownloadGlyphGeometry(line, line)
        assertEquals(ContentDownloadBounds(1053f, 903f, 1508f, 994f),
            contentDownloadComposeGlyphBounds(ContentDownloadBounds(1053f, 883f, 1508f, 1014f), geometry))
        assertEquals(ContentDownloadBounds(1053f, 883f, 1508f, 974f),
            contentDownloadComposeGlyphBounds(ContentDownloadBounds(1053f, 883f, 1508f, 974f), geometry))
    }

    @Test fun `reflection rejects mismatched receivers and failed native getters without affecting host state`() {
        val getter = Broken::class.java.getMethod("getValue")
        val contracts = ContentDownloadContracts(mapOf("value" to getter), emptyMap())
        assertNull(contracts.call("value", Any()))
        assertNull(contracts.call("value", Broken()))
        assertNull(contracts.call("unknown", Broken()))
    }

    @Test fun `install diagnostics retain exception types and native frames while omitting secret values`() {
        val cause = NoSuchMethodException("https://cdn.example/cover.mp4?token=SECRET_QUERY")
        val original = IllegalStateException("Cookie: SECRET_COOKIE; Bearer SECRET_TOKEN", cause).apply {
            addSuppressed(IllegalArgumentException("SECRET_SUPPRESSED"))
            stackTrace = arrayOf(
                StackTraceElement("dev.kifranei.ampp.media.HostContracts", "method", "HostContracts.kt", 36),
                StackTraceElement("dev.kifranei.ampp.media.MediaRuntime", "hookMethod", "https://private.example/?token=SECRET_FILE", 22),
            )
        }
        val diagnostic = contentDownloadInstallError(original)
        val output = java.io.StringWriter().also { diagnostic.printStackTrace(java.io.PrintWriter(it)) }.toString()
        assertTrue(output.contains("java.lang.IllegalStateException <- java.lang.NoSuchMethodException"))
        assertTrue(output.contains("HostContracts.method(HostContracts.kt:36)"))
        assertFalse(output.contains("SECRET"))
        assertFalse(output.contains("https://"))
        assertNull(diagnostic.cause)
        assertTrue(diagnostic.suppressed.isEmpty())
        assertSame(cause, original.cause)
        assertEquals(1, original.suppressed.size)
    }

    @Test fun `install diagnostics bound stack size and terminate cyclic exception causes`() {
        val first = IllegalStateException("SECRET_FIRST")
        val second = IllegalArgumentException("SECRET_SECOND")
        first.initCause(second); second.initCause(first)
        first.stackTrace = Array(100) { StackTraceElement("native.Loader", "load", "Loader.java", it) }
        val diagnostic = contentDownloadInstallError(first)
        assertEquals(24, diagnostic.stackTrace.size)
        assertEquals("Exception types: java.lang.IllegalStateException <- java.lang.IllegalArgumentException; original messages omitted", diagnostic.message)
    }

    @Test fun `array-bearing loader contract resolves exact parameter return and static types`() {
        val definition = JSONObject().put("owner", ArrayLoader::class.java.name).put("name", "load")
            .put("parameters", org.json.JSONArray(listOf("long", "[Ljava.lang.String;", "long", "[Ljava.lang.String;", "java.lang.Object")))
            .put("returns", "java.lang.Object").put("static", false)
        val types: (String) -> Class<*> = { name -> when (name) {
            "long" -> java.lang.Long.TYPE
            else -> Class.forName(name)
        } }
        val hostLoader = checkNotNull(ArrayLoader::class.java.classLoader)
        val method = contentDownloadArrayMethod(definition, hostLoader, types)
        assertEquals(listOf(java.lang.Long.TYPE, Array<String>::class.java, java.lang.Long.TYPE, Array<String>::class.java, Any::class.java), method.parameterTypes.toList())
        assertEquals("original", method.invoke(ArrayLoader(), 100L, arrayOf("en"), 1L, arrayOf("zh"), "original"))
        assertThrows(IllegalStateException::class.java) { contentDownloadArrayMethod(JSONObject(definition.toString()).put("static", true), hostLoader, types) }
        assertThrows(IllegalStateException::class.java) { contentDownloadArrayMethod(JSONObject(definition.toString()).put("returns", "java.lang.String"), hostLoader, types) }
    }

    @Test fun `UI reports actual location localized progress and asset-specific missing reasons`() {
        val shared = ContentExportFile("content://downloads/1", "Artist.jpg", "image/jpeg", 100, ContentExportLocation.SHARED_DOWNLOADS)
        val owned = shared.copy(location = ContentExportLocation.APP_DOWNLOADS)
        assertTrue(ContentDownloadUi.saved("en", shared).contains("Download/AM++"))
        assertTrue(ContentDownloadUi.saved("zh", owned).contains("应用下载目录"))
        assertFalse(ContentDownloadUi.saved("en", owned).contains("Download/AM++"))
        val state = ContentExportStatus("job", ContentExportKind.ARTIST_ARTWORK, ContentExportPhase.DOWNLOADING, bytesCopied = 2048)
        assertTrue(ContentDownloadUi.progress("en", state).contains("Downloading"))
        assertTrue(ContentDownloadUi.progress("zh", state).contains("正在下载"))
        assertTrue(ContentDownloadUi.progress("en", state).contains("2 KiB"))
        assertNotEquals(ContentDownloadUi.pageNotReady("en"), ContentDownloadUi.pageNotReady("zh"))
        assertNotEquals(ContentExportReason.NO_TTML.message("en"), ContentExportReason.NO_TTML.message("zh"))
    }

    class FakeKey(@JvmField val name: String)
    class FakeNode(private val entries: List<Pair<FakeKey, Any>>) {
        fun config(): Iterable<Map.Entry<FakeKey, Any>> = entries.associate { it }.entries
    }
    class FakeAction(@JvmField val action: FakeFunction)
    class FakeFunction(private val boxes: List<FakeRect>) {
        fun invoke(output: Any): Any {
            @Suppress("UNCHECKED_CAST") (output as MutableList<Any>).add(FakeLayout(boxes))
            return true
        }
    }
    class FakeLayout(private val boxes: List<FakeRect>) { fun bounds(index: Int) = boxes[index] }
    class FakeRect(@JvmField val left: Float, @JvmField val top: Float, @JvmField val right: Float, @JvmField val bottom: Float)
    class Broken { fun getValue(): String = error("unsupported getter") }
    class ArrayLoader {
        @Suppress("UNUSED_PARAMETER") fun load(id: Long, first: Array<String>, queue: Long, second: Array<String>, original: Any): Any = original
    }

    private fun fakeContracts() = ContentDownloadContracts(
        mapOf("content-download-semantics-config" to FakeNode::class.java.getMethod("config"),
            "content-download-function-invoke" to FakeFunction::class.java.getMethod("invoke", Any::class.java),
            "content-download-glyph-bounds" to FakeLayout::class.java.getMethod("bounds", Int::class.javaPrimitiveType)),
        mapOf("content-download-semantics-key-name" to FakeKey::class.java.getField("name"),
            "content-download-semantics-action" to FakeAction::class.java.getField("action"),
            "content-download-rect-left" to FakeRect::class.java.getField("left"),
            "content-download-rect-top" to FakeRect::class.java.getField("top"),
            "content-download-rect-right" to FakeRect::class.java.getField("right"),
            "content-download-rect-bottom" to FakeRect::class.java.getField("bottom")),
    )
}
