package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class ContentDownloadArtistAnchorTest {
    private val viewport = ContentDownloadBounds(0f, 0f, 360f, 800f)
    private val labels = ContentDownloadArtistLabels(mapOf(
        ContentDownloadArtistControl.INFO to setOf("Info", "信息"),
        ContentDownloadArtistControl.PLAY to setOf("Play", "播放"),
        ContentDownloadArtistControl.BACK to setOf("Navigate up.", "向上导航"),
        ContentDownloadArtistControl.TOOLBAR_ACTION to setOf("Share", "More Options", "分享", "更多选项"),
    ))

    @Test fun `static and motion wordmarks without text layout use a safe native topbar slot`() {
        val anchor = ContentDownloadArtistAnchor(contracts(), "com.apple.android.music")
        for (motion in listOf(false, true)) {
            val page = nativePage().apply {
                // The native PNG logo has no Text or GetTextLayoutResult. The video/poster is also decorative.
                children += FakeNode(emptyList(), FakeRect(20f, 270f, 340f, 345f))
                if (motion) children += FakeNode(emptyList(), FakeRect(0f, 0f, 360f, 360f))
            }
            val snapshot = checkNotNull(anchor.snapshot(page, labels))
            val result = checkNotNull(place(snapshot))
            assertEquals(40, result.size)
            assertEquals(52, result.top)
            assertSafeTarget(result, snapshot)
        }
    }

    @Test fun `ordinary title keeps the original position and impossible title cannot change to fallback`() {
        val title = ContentDownloadBounds(20f, 200f, 220f, 236f)
        val state = ContentDownloadArtistEntryState()
        fun entry(glyph: ContentDownloadBounds?) = state.placement(glyph, true, 360, 800, 1f, false) {
            error("Ordinary titles must not look up Info, Play, Back or toolbar controls")
        }
        assertEquals(contentDownloadButtonPlacement(title, 360, 800, 1f, false), entry(title))
        assertNull(entry(ContentDownloadBounds(0f, 200f, 360f, 236f)))
        assertNull(entry(null))
    }

    @Test fun `scrolling away from header hides entry even with the artist name in collapsed topbar`() {
        val scrolled = scene().map { node ->
            if (node.control in setOf(ContentDownloadArtistControl.INFO, ContentDownloadArtistControl.PLAY))
                node.copy(bounds = node.bounds.translated(0f, -340f)) else node
        }
        assertNull(place(scrolled))
        val state = ContentDownloadArtistEntryState()
        assertNotNull(state.placement(null, false, 360, 800, 1f, false) { place(scene()) })
        val collapsedTitle = ContentDownloadBounds(80f, 62f, 240f, 82f)
        assertNull(state.placement(collapsedTitle, true, 360, 800, 1f, false) { place(scrolled) })
        state.clear()
        assertEquals(contentDownloadButtonPlacement(collapsedTitle, 360, 800, 1f, false),
            state.placement(collapsedTitle, true, 360, 800, 1f, false) { error("New ordinary page must reset fallback mode") })
        assertNull(place(scene().filterNot { it.control == ContentDownloadArtistControl.INFO }))
    }

    @Test fun `current semantic bounds and hidden ancestors remove stale header controls`() {
        val anchor = ContentDownloadArtistAnchor(contracts(), "com.apple.android.music")
        val page = nativePage()
        assertNotNull(place(checkNotNull(anchor.snapshot(page, labels))))
        val info = page.children.first { it.entries.any { entry -> entry.second == listOf("Info") } }
        info.rect = FakeRect(72f, -20f, 120f, 28f)
        assertNull(place(checkNotNull(anchor.snapshot(page, labels))))
        val hiddenHeader = FakeNode(listOf(FakeKey("InvisibleToUser") to true), children = nativePage().children.drop(3))
        val hiddenPage = FakeNode(emptyList(), children = nativePage().children.take(3) + hiddenHeader)
        assertNull(place(checkNotNull(anchor.snapshot(hiddenPage, labels))))
    }

    @Test fun `all native targets including an extra topbar action keep their full touch clearance`() {
        val nodes = scene() + ContentDownloadArtistNode(ContentDownloadBounds(212f, 60f, 244f, 84f), actionable = true)
        val result = checkNotNull(place(nodes))
        assertTrue(result.left < 200)
        assertSafeTarget(result, nodes)
        val filled = nodes + ContentDownloadArtistNode(ContentDownloadBounds(60f, 58f, 250f, 86f), hasText = true)
        assertNull(place(filled))
    }

    @Test fun `RTL follows the actual mirrored topbar and viewport clipping cannot invent space`() {
        val normal = checkNotNull(place(scene()))
        val mirrored = scene().map { it.copy(bounds = ContentDownloadBounds(360 - it.bounds.right, it.bounds.top, 360 - it.bounds.left, it.bounds.bottom)) }
        val rtl = checkNotNull(place(mirrored))
        assertEquals(360 - normal.left - normal.size, rtl.left)
        assertSafeTarget(rtl, mirrored)
        assertNull(place(scene(), visible = ContentDownloadBounds(0f, 80f, 360f, 800f)))
        assertNull(place(scene(), visible = ContentDownloadBounds(0f, 0f, 360f, 380f)))
        assertNull(place(scene(), density = Float.NaN))
    }

    @Test fun `absent ambiguous and crowded native controls cannot produce a guessed entry`() {
        assertNull(place(scene().filterNot { it.control == ContentDownloadArtistControl.TOOLBAR_ACTION }))
        assertNull(place(scene() + scene().first { it.control == ContentDownloadArtistControl.INFO }))
        assertNull(place(scene().map { if (it.control == ContentDownloadArtistControl.TOOLBAR_ACTION) it.copy(bounds = it.bounds.translated(-180f, 0f)) else it }))
        assertNull(place(scene().map { if (it.control == ContentDownloadArtistControl.PLAY) it.copy(bounds = it.bounds.translated(0f, 100f)) else it }))
    }

    @Test fun `fractional density preserves full target clearance in native pixel coordinates`() {
        for (density in listOf(1.5f, 2.625f)) {
            val nodes = scene().map { it.copy(bounds = ContentDownloadBounds(it.bounds.left * density, it.bounds.top * density,
                it.bounds.right * density, it.bounds.bottom * density)) }
            val visible = ContentDownloadBounds(0f, 0f, 360 * density, 800 * density)
            val result = checkNotNull(contentDownloadArtistButtonPlacement(nodes, visible, (360 * density).toInt(), (800 * density).toInt(), density))
            assertSafeTarget(result, nodes, density)
        }
    }

    @Test fun `localized native labels match exactly and positioning reads only needed semantics`() {
        assertEquals(ContentDownloadArtistControl.INFO, labels.control(listOf("\u202e信息\u202c")))
        assertEquals(ContentDownloadArtistControl.PLAY, labels.control(listOf("播放")))
        assertNull(labels.control(listOf("Info about someone else")))
        assertNull(labels.control(listOf("Info", "Play")))
        val anchor = ContentDownloadArtistAnchor(contracts(), "com.apple.android.music")
        val properties = anchor.semanticsProperties(FakeNode(listOf(
            FakeKey("ContentDescription") to listOf("Info"), FakeKey("OnClick") to true,
            FakeKey("Authorization") to "NEVER_CAPTURE", FakeKey("GetTextLayoutResult") to "NEVER_INVOKE",
        )))
        assertEquals(setOf("ContentDescription", "OnClick"), properties.keys)
        assertFalse(properties.toString().contains("NEVER_"))
    }

    @Test fun `incomplete semantic traversal fails closed instead of missing a blocker`() {
        val anchor = ContentDownloadArtistAnchor(contracts(), "com.apple.android.music")
        val page = nativePage().apply { children += List(512) { FakeNode(emptyList()) } }
        assertNull(anchor.snapshot(page, labels))
    }

    @Test fun `unmerged parent click target inherits description from its icon and retains native touch bounds`() {
        val anchor = ContentDownloadArtistAnchor(contracts(), "com.apple.android.music")
        val page = layeredPage()
        val snapshot = checkNotNull(anchor.snapshot(page, labels))
        val info = snapshot.single { it.actionable && it.control == ContentDownloadArtistControl.INFO }
        assertEquals(ContentDownloadBounds(72f, 373f, 120f, 421f), info.bounds)
        assertSafeTarget(checkNotNull(place(snapshot)), snapshot)
        val tracked = checkNotNull(anchor.track(checkNotNull(anchor.scan(page, labels)), 1f))
        assertNotNull(place(checkNotNull(anchor.currentSnapshot(tracked, page, labels))))
        page.children[3].children[0].entries += FakeKey("HideFromAccessibility") to true
        assertNull(anchor.currentSnapshot(tracked, page, labels))
    }

    @Test fun `cached identities read fresh bounds and reject a new toolbar blocker before displaying`() {
        val anchor = ContentDownloadArtistAnchor(contracts(), "com.apple.android.music")
        val page = layeredPage()
        val content = FakeNode(emptyList(), children = List(100) { FakeNode(emptyList()) })
        page.children += content
        val tracked = checkNotNull(anchor.track(checkNotNull(anchor.scan(page, labels)), 1f))
        val contentReads = content.configReads + content.children.sumOf { it.configReads }
        repeat(30) { assertNotNull(place(checkNotNull(anchor.currentSnapshot(tracked, page, labels)))) }
        assertEquals(contentReads, content.configReads + content.children.sumOf { it.configReads })
        page.children[3].rect = FakeRect(72f, -20f, 120f, 28f)
        page.children[3].children[0].rect = FakeRect(84f, -8f, 108f, 16f)
        assertNull(place(checkNotNull(anchor.currentSnapshot(tracked, page, labels))))
        page.children += FakeNode(listOf(FakeKey("OnClick") to true), FakeRect(210f, 53f, 250f, 91f))
        assertNull(anchor.currentSnapshot(tracked, page, labels))
    }

    @Test fun `full snapshots are cached for five hundred milliseconds including a failed lookup`() {
        val cache = ContentDownloadArtistSnapshotCache<Int>()
        var lookups = 0
        fun read(now: Long) = cache.get(now) { ++lookups }
        assertEquals(1, read(0))
        for (now in 1L..499L) assertEquals(1, read(now))
        assertEquals(1, lookups)
        assertEquals(2, read(500))
        cache.invalidate()
        assertEquals(3, read(501))
        cache.clear()
        assertNull(cache.get(502) { lookups++; null })
        assertNull(cache.get(503) { error("A missing header must not trigger a scan every frame") })
    }

    private fun scene() = listOf(
        ContentDownloadArtistNode(ContentDownloadBounds(10f, 53f, 48f, 91f), ContentDownloadArtistControl.BACK, true),
        ContentDownloadArtistNode(ContentDownloadBounds(262f, 53f, 300f, 91f), ContentDownloadArtistControl.TOOLBAR_ACTION, true),
        ContentDownloadArtistNode(ContentDownloadBounds(312f, 53f, 350f, 91f), ContentDownloadArtistControl.TOOLBAR_ACTION, true),
        ContentDownloadArtistNode(ContentDownloadBounds(72f, 373f, 120f, 421f), ContentDownloadArtistControl.INFO, true),
        ContentDownloadArtistNode(ContentDownloadBounds(144f, 360f, 216f, 432f), ContentDownloadArtistControl.PLAY, true),
        ContentDownloadArtistNode(ContentDownloadBounds(240f, 373f, 288f, 421f), actionable = true),
    )

    private fun place(nodes: List<ContentDownloadArtistNode>, visible: ContentDownloadBounds = viewport, density: Float = 1f) =
        contentDownloadArtistButtonPlacement(nodes, visible, 360, 800, density)

    private fun assertSafeTarget(button: ContentDownloadButtonPlacement, nodes: List<ContentDownloadArtistNode>, density: Float = 1f) {
        val target = ContentDownloadBounds(button.left.toFloat(), button.top.toFloat(), (button.left + button.size).toFloat(), (button.top + button.size).toFloat())
        nodes.filter { it.actionable }.forEach { node ->
            val extraX = ((48f * density - node.bounds.width).coerceAtLeast(0f)) / 2 + 8 * density
            val extraY = ((48f * density - node.bounds.height).coerceAtLeast(0f)) / 2 + 8 * density
            val overlaps = target.left < node.bounds.right + extraX && target.right > node.bounds.left - extraX &&
                target.top < node.bounds.bottom + extraY && target.bottom > node.bounds.top - extraY
            assertFalse("Native control ${node.control} overlaps the download touch target", overlaps)
        }
    }

    private fun nativePage(): FakeNode {
        val titles = listOf("Navigate up.", "Share", "More Options", "Info", "Play", "Favorite")
        return FakeNode(emptyList(), children = scene().zip(titles).map { (node, title) ->
            FakeNode(listOf(FakeKey("ContentDescription") to listOf(title), FakeKey("OnClick") to true),
                FakeRect(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom))
        })
    }

    private fun layeredPage() = FakeNode(emptyList(), children = nativePage().children.map { original ->
        val box = original.rect
        FakeNode(listOf(FakeKey("OnClick") to true), box, listOf(FakeNode(original.entries.filterNot { it.first.name == "OnClick" },
            FakeRect(box.left + 12, box.top + 12, box.right - 12, box.bottom - 12))))
    })

    class FakeKey(@JvmField val name: String)
    class FakeRect(@JvmField val left: Float, @JvmField val top: Float, @JvmField val right: Float, @JvmField val bottom: Float)
    class FakeNode(var entries: List<Pair<FakeKey, Any>>, var rect: FakeRect = FakeRect(0f, 0f, 0f, 0f), var children: List<FakeNode> = emptyList()) {
        @JvmField val g = ids.incrementAndGet()
        var configReads = 0
        fun config(): Iterable<Map.Entry<FakeKey, Any>> { configReads++; return entries.associate { it }.entries }
        @Suppress("UNUSED_PARAMETER") fun children(first: Boolean, second: Boolean): List<FakeNode> = children
        fun bounds(): FakeRect = rect
        companion object { private val ids = java.util.concurrent.atomic.AtomicInteger() }
    }

    private fun contracts() = ContentDownloadContracts(
        mapOf("content-download-semantics-config" to FakeNode::class.java.getMethod("config"),
            "content-download-semantics-children" to FakeNode::class.java.getMethod("children", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
            "content-download-semantics-bounds" to FakeNode::class.java.getMethod("bounds")),
        mapOf("content-download-semantics-key-name" to FakeKey::class.java.getField("name"),
            "content-download-semantics-id" to FakeNode::class.java.getField("g"),
            "content-download-rect-left" to FakeRect::class.java.getField("left"),
            "content-download-rect-top" to FakeRect::class.java.getField("top"),
            "content-download-rect-right" to FakeRect::class.java.getField("right"),
            "content-download-rect-bottom" to FakeRect::class.java.getField("bottom")),
    )
}
