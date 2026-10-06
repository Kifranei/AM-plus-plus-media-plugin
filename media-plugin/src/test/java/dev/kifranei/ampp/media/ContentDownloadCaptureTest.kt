package dev.kifranei.ampp.media

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ContentDownloadCaptureTest {
    private fun raw(word: String) = "<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><head><metadata>translated</metadata></head><body><p begin=\"1s\"><span>$word</span></p></body></tt>\r\n"

    @Test fun `prefetch and current song lyrics remain separately indexed with original documents`() {
        val capture = ContentDownloadCapture()
        val first = raw("first 原词"); val second = raw("second")
        assertTrue(capture.recordTtml(100, first)); assertTrue(capture.recordTtml(200, second))
        assertSame(first, capture.ttmlAsset(100, "current song").rawTtml)
        assertSame(second, capture.ttmlAsset(200, "prefetched song").rawTtml)
        assertEquals(ContentExportReason.NO_TTML, capture.ttmlAsset(300, "uncaptured song").unavailableReason())
    }

    @Test fun `loader contexts are nested per execution and unknown inner IDs suppress guesses`() {
        val capture = ContentDownloadCapture()
        val a = Any(); val b = Any()
        capture.beginLoad(100, a); capture.beginLoad(200, b)
        assertEquals(100L, capture.parserSongId(a)); assertEquals(200L, capture.parserSongId(b))
        capture.beginLoad(null, a)
        assertNull(capture.parserSongId(a))
        capture.endLoad(a)
        assertEquals(100L, capture.parserSongId(a))
        capture.endLoad(a); capture.endLoad(b)
        assertNull(capture.parserSongId(a)); assertNull(capture.parserSongId(b))
    }

    @Test fun `parallel native loader threads cannot borrow each other's song ID`() {
        val capture = ContentDownloadCapture()
        val ready = CountDownLatch(2); val release = CountDownLatch(1)
        val observed = java.util.concurrent.ConcurrentHashMap<Long, Long?>()
        val threads = listOf(100L, 200L).map { id -> Thread {
            capture.beginLoad(id); ready.countDown(); release.await(5, TimeUnit.SECONDS)
            capture.parserSongId()?.let { observed[id] = it }
            capture.endLoad()
        }.apply { start() } }
        assertTrue(ready.await(5, TimeUnit.SECONDS)); release.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(mapOf(100L to 100L, 200L to 200L), observed)
    }

    @Test fun `depth and execution limits suppress excess contexts without corrupting outer frames`() {
        val capture = ContentDownloadCapture(ContentDownloadCaptureOptions(maxLoaderDepth = 1, maxLoaderContexts = 1))
        val a = Any(); val b = Any()
        capture.beginLoad(100, a); capture.beginLoad(200, a); capture.beginLoad(300, a)
        capture.beginLoad(400, b)
        assertNull(capture.parserSongId(a)); assertNull(capture.parserSongId(b))
        capture.endLoad(a); assertNull(capture.parserSongId(a))
        capture.endLoad(a); assertEquals(100L, capture.parserSongId(a))
        capture.endLoad(a); capture.endLoad(b)
        capture.beginLoad(500, b); assertEquals(500L, capture.parserSongId(b))
    }

    @Test fun `resumed coroutine uses its saved loader ID and never a current-song fallback`() {
        assertEquals(123L, contentDownloadLoaderSongId(123L, 0, null))
        assertEquals(456L, contentDownloadLoaderSongId(0L, Int.MIN_VALUE or 1, 456L))
        assertEquals(456L, contentDownloadLoaderSongId(999L, Int.MIN_VALUE or 1, 456L))
        assertNull(contentDownloadLoaderSongId(0L, 1, 456L))
        assertNull(contentDownloadLoaderSongId(999L, Int.MIN_VALUE or 1, null))
        assertNull(contentDownloadLoaderSongId(-1L, null, null))
    }

    @Test fun `LRU and total character budgets evict complete songs without truncating lyrics`() {
        val document = raw("words")
        val capture = ContentDownloadCapture(ContentDownloadCaptureOptions(maxSongs = 2, maxTtmlChars = document.length + 20,
            maxTotalTtmlChars = 2 * (document.length + 20)))
        capture.recordTtml(1, document); capture.recordTtml(2, document)
        capture.ttmlAsset(1, "one")
        capture.recordTtml(3, document)
        assertNull(capture.ttmlAsset(2, "evicted").rawTtml)
        assertSame(document, capture.ttmlAsset(1, "one").rawTtml)
        assertFalse(capture.recordTtml(4, document + " ".repeat(30)))
        assertNull(capture.ttmlAsset(4, "too large").rawTtml)
        val single = ContentDownloadCapture(ContentDownloadCaptureOptions(maxTtmlChars = document.length, maxTotalTtmlChars = document.length, maxSongs = 10))
        single.recordTtml(1, document); single.recordTtml(2, document)
        assertNull(single.ttmlAsset(1, "one").rawTtml)
    }

    @Test fun `closing capture clears all threads pages and lyrics and prevents later queued writes`() {
        val capture = ContentDownloadCapture()
        capture.beginLoad(100, Any()); capture.recordTtml(100, raw("word"))
        val page = ContentDownloadPage(ContentDownloadPageKind.ARTIST, "100", "artist")
        capture.recordPage(page); capture.close()
        assertNull(capture.ttmlAsset(100, "song").rawTtml)
        assertNull(capture.page(page.kind, page.id))
        assertFalse(capture.recordTtml(100, raw("late callback")))
        assertFalse(capture.recordPage(page))
        capture.beginLoad(100); assertNull(capture.parserSongId())
    }

    @Test fun `artist and album pages with the same numeric ID cannot overwrite each other`() {
        val capture = ContentDownloadCapture(ContentDownloadCaptureOptions(maxPages = 2))
        val artist = ContentDownloadPage(ContentDownloadPageKind.ARTIST, "100", "artist")
        val album = ContentDownloadPage(ContentDownloadPageKind.ALBUM, "100", "album")
        capture.recordPage(artist); capture.recordPage(album)
        assertEquals(artist, capture.page(artist.kind, "100")); assertEquals(album, capture.page(album.kind, "100"))
        assertEquals(listOf(ContentExportKind.ARTIST_ARTWORK, ContentExportKind.ARTIST_MOTION_ARTWORK, ContentExportKind.ARTIST_BIOGRAPHY), artist.assets().map { it.kind })
        assertEquals(listOf(ContentExportKind.ALBUM_MOTION_ARTWORK, ContentExportKind.ALBUM_EDITORIAL), album.assets().map { it.kind })
        capture.recordPage(ContentDownloadPage(ContentDownloadPageKind.ARTIST, "200", "other"))
        assertNull(capture.page(ContentDownloadPageKind.ARTIST, "100"))
    }

    @Test fun `native artist fields keep artwork motion and biography without querying credentials`() {
        val image = Any(); val video = Any(); val file = Any(); val attributes = Any(); val entity = Any()
        val requested = ArrayList<String>()
        val access = ContentDownloadNativeAccess { owner, key ->
            requested += key
            when (owner) {
                entity -> when (key) { "content-download-entity-id" -> "100"; "content-download-entity-type" -> "artists";
                    "media-entity-get-title-method" -> "Artist"; "media-entity-get-attributes-method" -> attributes; else -> null }
                attributes -> when (key) { "content-download-attrs-artwork" -> image; "content-download-attrs-biography" -> "<p>Full biography &amp; context</p>";
                    "content-download-attrs-videos" -> mapOf("motionArtistSquare" to video); else -> null }
                image -> when (key) { "content-download-artwork-url" -> "https://cdn.example/{w}x{h}.{f}?sig=private";
                    "content-download-artwork-width" -> 4000; "content-download-artwork-height" -> 3000; else -> null }
                video -> when (key) { "content-download-video-files" -> listOf(file); "content-download-video-hls" -> "https://cdn.example/artist.m3u8"; else -> null }
                file -> when (key) { "content-download-file-url" -> "https://cdn.example/artist.mp4";
                    "content-download-file-width" -> 1920; "content-download-file-height" -> 1080; else -> null }
                else -> null
            }
        }
        val page = checkNotNull(ContentDownloadPageDecoder().fromNative(entity, access))
        assertEquals("https://cdn.example/4000x3000.jpg?sig=private", page.artwork?.jpegUrl())
        assertEquals("Full biography & context", page.biography?.plainText())
        assertEquals(listOf(MotionArtworkSourceType.DIRECT_MP4, MotionArtworkSourceType.HLS), page.motion.map { it.type })
        assertEquals(1920, page.motion.first().width)
        assertEquals(1080, page.motion.first().height)
        assertEquals(page.motion, (page.assets()[1] as ContentExportAsset.ArtistMotionArtwork).sources)
        assertTrue(page.assets().all { it.unavailableReason() == null })
        assertTrue(requested.none { it.contains("token", true) || it.contains("cookie", true) || it.contains("auth", true) })
        assertFalse(page.toString().contains("sig=private"))
    }

    @Test fun `already requested JSON yields only editorial motion URLs and full album notes`() {
        val json = JSONObject("""{"token":"NEVER_CAPTURE","data":[{"id":"200","type":"albums","attributes":{
          "name":"Album","previews":[{"url":"https://cdn.example/music.m4a"}],"editorialVideo":{"motionDetailSquare":{
          "video":"https://cdn.example/cover.m3u8","videoFile":[{"assetUrl":"https://cdn.example/cover.mp4","width":1920,"height":1080}]}},
          "editorialNotes":{"standard":"<p>Full introduction.</p>","short":"short snippet"},"authorization":"NEVER_CAPTURE"}}]}""")
        val page = checkNotNull(ContentDownloadPageDecoder().fromJson(json))
        assertEquals(listOf(MotionArtworkSourceType.DIRECT_MP4, MotionArtworkSourceType.HLS), page.motion.map { it.type })
        assertEquals(1920, page.motion.first().width)
        assertEquals("Full introduction.", page.editorial?.plainText())
        assertFalse(page.toString().contains("NEVER_CAPTURE"))
        assertTrue(page.motion.none { it.url.contains("music.m4a") })
        assertTrue(page.assets().all { it.unavailableReason() == null })
    }

    @Test fun `missing or failing optional native getters remain honestly unavailable`() {
        val page = checkNotNull(ContentDownloadPageDecoder().fromJson(JSONObject("""{"id":"100","type":"artists","attributes":{"name":"Artist"}}""")))
        assertEquals(listOf(ContentExportReason.NO_ARTIST_ARTWORK, ContentExportReason.NO_ARTIST_MOTION_ARTWORK, ContentExportReason.NO_BIOGRAPHY),
            page.assets().map { it.unavailableReason() })
        assertNull(ContentDownloadPageDecoder().fromJson(JSONObject("""{"id":"100","type":"songs","attributes":{"name":"Music"}}""")))
        assertNull(ContentDownloadPageDecoder().fromJson(JSONObject("""{"id":"https://private?token=secret","type":"artists"}""")))
        val entity = Any()
        val access = ContentDownloadNativeAccess { _, key -> when (key) {
            "content-download-entity-id" -> "100"; "content-download-entity-type" -> "artists";
            "media-entity-get-title-method" -> "Artist"; "media-entity-get-attributes-method" -> throw IllegalStateException("unsupported attribute"); else -> null
        } }
        assertTrue(checkNotNull(ContentDownloadPageDecoder().fromNative(entity, access)).assets().all { it.unavailableReason() != null })
        val capture = ContentDownloadCapture()
        assertFalse(capture.recordTtml(100, "rendered word word"))
        assertFalse(capture.recordTtml(0, raw("word")))
    }

    @Test fun `bounded video lists retain a real HLS fallback rather than filling with MP4 renditions`() {
        val json = JSONObject("""{"id":"200","type":"albums","attributes":{"editorialVideo":{"square":{
          "video":"https://cdn.example/cover.m3u8","videoFile":[{"assetUrl":"https://cdn.example/1.mp4"},{"assetUrl":"https://cdn.example/2.mp4"},{"assetUrl":"https://cdn.example/3.mp4"}]}}}}""")
        for (type in listOf("albums", "artists")) {
            val page = checkNotNull(ContentDownloadPageDecoder(ContentDownloadCaptureOptions(maxSources = 2)).fromJson(json.put("type", type)))
            assertEquals(2, page.motion.size)
            assertEquals(MotionArtworkSourceType.DIRECT_MP4, page.motion.first().type)
            assertEquals(MotionArtworkSourceType.HLS, page.motion.last().type)
        }
    }

    @Test fun `catalog and library artist JSON preserve editorial motion while excluding music previews`() {
        for (type in listOf("artists", "library-artists")) {
            val json = JSONObject("""{"id":"artist.100","type":"$type","attributes":{
                "name":"Artist","artwork":{"url":"https://cdn.example/static.jpg"},"artistBio":"<p>Full biography.</p>",
                "editorialVideo":{"motionArtistSquare":{"video":"https://cdn.example/artist.m3u8",
                  "videoFile":[{"assetUrl":"https://cdn.example/artist.mp4","width":1080,"height":1080}]}},
                "previews":[{"url":"https://cdn.example/music.m4a"}],"authorization":"NEVER_CAPTURE"}}""")
            val page = checkNotNull(ContentDownloadPageDecoder().fromJson(json))
            assertEquals(ContentDownloadPageKind.ARTIST, page.kind)
            assertEquals(listOf(ContentExportKind.ARTIST_ARTWORK, ContentExportKind.ARTIST_MOTION_ARTWORK, ContentExportKind.ARTIST_BIOGRAPHY),
                page.assets().map { it.kind })
            assertEquals(listOf("https://cdn.example/artist.mp4", "https://cdn.example/artist.m3u8"), page.motion.map { it.url })
            assertTrue(page.assets().all { it.unavailableReason() == null })
            assertFalse(page.toString().contains("NEVER_CAPTURE"))
            assertTrue(page.motion.none { it.url.contains("music.m4a") })
            val capture = ContentDownloadCapture()
            capture.recordPage(page)
            assertEquals(page.motion, (checkNotNull(capture.page(page.kind, page.id)).assets()[1] as ContentExportAsset.ArtistMotionArtwork).sources)
        }
    }
}
