package dev.kifranei.ampp.media

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ContentExportStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 2, -1, -39)
    private val ttml = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
        "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">" +
        "<head><metadata><itunes:agent type=\"person\" xml:id=\"v1\"/></metadata></head>" +
        "<body><div><p begin=\"00:00:01.000\" end=\"00:00:03.000\"><span begin=\"00:00:01.000\">原词</span>" +
        "<span itunes:role=\"translation\">translated</span></p></div></body></tt>\r\n"

    @Test fun `TTML export preserves the entire captured document byte for byte`() {
        val folder = temporary.newFolder()
        val network = ContentExportTestNetwork(emptyMap())
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            val run = exportAndWait(storage, ContentExportAsset.TtmlLyrics("song", ttml))
            val result = checkNotNull(run.status.file)
            assertEquals(ContentExportPhase.SUCCEEDED, run.status.phase)
            assertEquals("application/ttml+xml", result.mimeType)
            assertTrue(result.displayName.endsWith(".ttml"))
            assertEquals(ContentExportLocation.APP_DOWNLOADS, result.location)
            assertArrayEquals(ttml.toByteArray(StandardCharsets.UTF_8), File(URI(result.uri)).readBytes())
            assertEquals(1, checkNotNull(folder.listFiles()).size)
            assertTrue(network.requests.isEmpty())
            assertEquals(1, run.states.count { it.phase.isTerminal })
        }
    }

    @Test fun `artist JPEG with native dimension placeholders resolves and publishes through storage`() {
        val folder = temporary.newFolder()
        val resolved = "https://cdn.example/2400x1600bb.jpg?signature=private"
        val network = ContentExportTestNetwork(mapOf(resolved to jpeg))
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            val run = exportAndWait(storage, ContentExportAsset.ArtistArtwork("Artist",
                ArtworkImageSource("https://cdn.example/{W}x{H}{c}.{f}?signature=private", 2400, 1600)))
            assertEquals(ContentExportPhase.SUCCEEDED, run.status.phase)
            assertEquals("image/jpeg", run.status.file?.mimeType)
            assertEquals(listOf(resolved), network.requests)
            assertArrayEquals(jpeg, File(URI(checkNotNull(run.status.file).uri)).readBytes())
        }
    }

    @Test fun `artist HLS parsing failures forward only the safe structure summary to the registered listener`() {
        val folder = temporary.newFolder()
        val address = "https://cdn.example/artist.m3u8?token=private"
        val body = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\none.m4s\n#EXT-X-ENDLIST\n"
        val network = ContentExportTestNetwork(mapOf(address to body.toByteArray()))
        val summaries = CopyOnWriteArrayList<String>()
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            storage.setMotionStructureListener { summaries += it }
            val run = exportAndWait(storage, ContentExportAsset.ArtistMotionArtwork("Artist", listOf(MotionArtworkSource(address))))
            assertEquals(ContentExportReason.UNSUPPORTED_HLS, run.status.failure?.reason)
            assertTrue(summaries.single().contains("#EXT-X-DISCONTINUITY"))
            assertTrue(summaries.single().contains("mapFormats=[mp4]"))
            assertFalse(summaries.single().contains("cdn.example"))
            assertFalse(summaries.single().contains("token="))
            assertEquals(0, checkNotNull(folder.listFiles()).size)
        }
    }

    @Test fun `biography and editorial exports contain readable text with matching MIME`() {
        val folder = temporary.newFolder()
        ContentExportStorage(AtomicFileContentExportDestination(folder)).use { storage ->
            val biography = exportAndWait(storage, ContentExportAsset.ArtistBiography("artist",
                ContentExportText("<p>First &amp; second.</p><p>中文 biography.</p>", ContentExportTextFormat.HTML)))
            val editorial = exportAndWait(storage, ContentExportAsset.AlbumEditorial("album", ContentExportText("  Native intro\r\nOriginal line.  ")))
            assertEquals("First & second.\n\n中文 biography.", File(URI(checkNotNull(biography.status.file).uri)).readText())
            assertEquals("  Native intro\r\nOriginal line.  ", File(URI(checkNotNull(editorial.status.file).uri)).readText())
            listOf(biography, editorial).forEach { run ->
                assertEquals("text/plain", run.status.file?.mimeType)
                assertTrue(checkNotNull(run.status.file).displayName.endsWith(".txt"))
            }
        }
    }

    @Test fun `TTML preserves its XML encoding and metadata without adding a duplicate BOM`() {
        val folder = temporary.newFolder()
        ContentExportStorage(AtomicFileContentExportDestination(folder)).use { storage ->
            for ((encoding, charset) in listOf("UTF-16LE" to StandardCharsets.UTF_16LE, "UTF-16" to StandardCharsets.UTF_16BE)) {
                val document = "\uFEFF<?xml version=\"1.0\" encoding=\"$encoding\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p>完整原词</p></body></tt>"
                val result = exportAndWait(storage, ContentExportAsset.TtmlLyrics("song", document)).status
                assertArrayEquals(document.toByteArray(charset), File(URI(checkNotNull(result.file).uri)).readBytes())
            }
        }
    }

    @Test fun `all missing assets fail honestly without empty files or network requests`() {
        val folder = temporary.newFolder()
        val network = ContentExportTestNetwork(emptyMap())
        val assets = listOf(ContentExportAsset.ArtistArtwork("artist", null), ContentExportAsset.AlbumMotionArtwork("album", emptyList()),
            ContentExportAsset.ArtistMotionArtwork("artist", emptyList()),
            ContentExportAsset.TtmlLyrics("song", null), ContentExportAsset.ArtistBiography("artist", null), ContentExportAsset.AlbumEditorial("album", null))
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            assets.forEach { asset ->
                val result = exportAndWait(storage, asset).status
                assertEquals(ContentExportPhase.FAILED, result.phase)
                assertEquals(asset.unavailableReason(), result.failure?.reason)
                assertNull(result.file)
            }
        }
        assertTrue(network.requests.isEmpty())
        assertEquals(0, checkNotNull(folder.listFiles()).size)
    }

    @Test fun `artist and album direct motion exports publish only complete MP4 with matching MIME and names`() {
        val folder = temporary.newFolder()
        val address = "https://cdn.example/motion.mp4"
        val bytes = ContentExportTestMedia.initialization() + ContentExportTestMedia.fragment(0) + ContentExportTestMedia.fragment(1000)
        val caller = Thread.currentThread()
        val ioThread = AtomicReference(caller)
        val network = ContentExportTestNetwork { url ->
            ioThread.set(Thread.currentThread())
            ContentExportResponse(url, bytes.inputStream(), bytes.size.toLong())
        }
        val sources = listOf(MotionArtworkSource(address, MotionArtworkSourceType.DIRECT_MP4))
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            for (asset in listOf(ContentExportAsset.ArtistMotionArtwork("same title", sources), ContentExportAsset.AlbumMotionArtwork("same title", sources))) {
                val run = exportAndWait(storage, asset)
                val result = checkNotNull(run.status.file)
                assertEquals(ContentExportPhase.SUCCEEDED, run.status.phase)
                assertEquals(asset.kind, run.status.kind)
                assertEquals("video/mp4", result.mimeType)
                assertTrue(result.displayName.contains("-${asset.kind.filenameTag}-"))
                assertTrue(result.displayName.endsWith(".mp4"))
                val output = File(URI(result.uri))
                assertEquals(output.length(), result.byteCount)
                assertStandaloneMotion(output)
                assertTrue(run.states.any { it.phase == ContentExportPhase.DOWNLOADING })
                assertEquals(1, run.states.count { it.phase.isTerminal })
            }
        }
        assertNotSame(caller, ioThread.get())
        assertEquals(2, checkNotNull(folder.listFiles()).size)
        assertEquals(2, network.closed.get())
    }

    @Test fun `captured artist and album HLS motion becomes MP4 from one video variant map and relative fragments`() {
        val folder = temporary.newFolder()
        val entry = "https://cdn.example/art/master.m3u8"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=320x240,CODECS=\"avc1.4d001e\"\nclips/video.m3u8\n"
        val media = "#EXTM3U\n#EXT-X-MAP:URI=\"../init.mp4\"\n#EXTINF:1,\n../one.m4s\n#EXTINF:1,\n../two.m4s\n#EXT-X-ENDLIST\n"
        val init = ContentExportTestMedia.initialization()
        val first = ContentExportTestMedia.fragment(0)
        val second = ContentExportTestMedia.fragment(1000)
        val network = ContentExportTestNetwork(mapOf(entry to master.toByteArray(), "https://cdn.example/art/clips/video.m3u8" to media.toByteArray(),
            "https://cdn.example/art/init.mp4" to init, "https://cdn.example/art/one.m4s" to first, "https://cdn.example/art/two.m4s" to second))
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            for (type in listOf("artists", "albums")) {
                val json = org.json.JSONObject("""{"id":"100","type":"$type","attributes":{
                    "name":"Motion page","editorialVideo":{"square":{"video":"$entry"}}}}""")
                val page = checkNotNull(ContentDownloadPageDecoder().fromJson(json))
                val asset = page.assets().single { it.kind == if (type == "artists") ContentExportKind.ARTIST_MOTION_ARTWORK else ContentExportKind.ALBUM_MOTION_ARTWORK }
                val run = exportAndWait(storage, asset)
                val result = checkNotNull(run.status.file)
                val output = File(URI(result.uri))
                assertEquals(ContentExportPhase.SUCCEEDED, run.status.phase)
                assertEquals("video/mp4", result.mimeType)
                assertTrue(result.displayName.endsWith(".mp4"))
                assertEquals(output.length(), result.byteCount)
                assertStandaloneMotion(output)
                assertEquals(1L, MotionArtworkMp4.validate(output, ContentExportControl(ContentExportOptions()).apply { start() }).id)
            }
        }
        assertEquals(2, checkNotNull(folder.listFiles()).size)
        assertEquals(10, network.requests.size)
        assertEquals(network.requests.size, network.closed.get())
    }

    @Test fun `artist encrypted or transport-stream playlists fail without publishing or fetching their media`() {
        val folder = temporary.newFolder()
        val address = "https://cdn.example/artist.m3u8"
        val bodies = listOf(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\none.m4s\n#EXT-X-ENDLIST\n" to ContentExportReason.ENCRYPTED_MEDIA,
            "#EXTM3U\n#EXTINF:1,\none.ts\n#EXT-X-ENDLIST\n" to ContentExportReason.UNSUPPORTED_TS,
        )
        for ((body, reason) in bodies) {
            val network = ContentExportTestNetwork(mapOf(address to body.toByteArray()))
            ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
                val run = exportAndWait(storage, ContentExportAsset.ArtistMotionArtwork("Artist", listOf(MotionArtworkSource(address, MotionArtworkSourceType.HLS))))
                assertEquals(ContentExportPhase.FAILED, run.status.phase)
                assertEquals(reason, run.status.failure?.reason)
                assertNull(run.status.file)
                assertEquals(listOf(address), network.requests)
                assertEquals(0, checkNotNull(folder.listFiles()).size)
            }
        }
    }

    @Test fun `queued artist motion freezes its source list before native metadata can mutate it`() {
        val folder = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val address = "https://cdn.example/artist.mp4"
        val bytes = ContentExportTestMedia.initialization() + ContentExportTestMedia.fragment(0) + ContentExportTestMedia.fragment(1000)
        val network = ContentExportTestNetwork { url ->
            if (url == address) ContentExportResponse(url, bytes.inputStream(), bytes.size.toLong()) else
                ContentExportResponse(url, object : ByteArrayInputStream(jpeg) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        entered.countDown(); release.await(5, TimeUnit.SECONDS)
                        return super.read(buffer, offset, length)
                    }
                }, jpeg.size.toLong())
        }
        ContentExportStorage(AtomicFileContentExportDestination(folder), ContentExportOptions(maxConcurrentExports = 1), network).use { storage ->
            try {
                val first = Observer()
                storage.export(ContentExportAsset.ArtistArtwork("blocking", ArtworkImageSource("https://cdn.example/blocking.jpg")), first::accept)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val sources = mutableListOf(MotionArtworkSource(address, MotionArtworkSourceType.DIRECT_MP4))
                val motion = Observer()
                val queued = storage.export(ContentExportAsset.ArtistMotionArtwork("Artist", sources), motion::accept)
                assertEquals(ContentExportPhase.QUEUED, queued.status.phase)
                sources.clear()
                release.countDown(); first.await(); motion.await()
                assertEquals(ContentExportPhase.SUCCEEDED, queued.status.phase)
                assertStandaloneMotion(File(URI(checkNotNull(queued.status.file).uri)))
                assertEquals(listOf("https://cdn.example/blocking.jpg", address), network.requests)
            } finally { release.countDown() }
        }
    }

    @Test fun `running artist motion cancellation cleans its partial output before the terminal callback`() {
        val folder = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = blockingNetwork(entered, release)).use { storage ->
            try {
                val observer = Observer()
                val job = storage.export(ContentExportAsset.ArtistMotionArtwork("Artist", listOf(MotionArtworkSource("https://cdn.example/artist.mp4"))), observer::accept)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(job.cancel())
                observer.await()
                assertEquals(ContentExportPhase.CANCELLED, job.status.phase)
                assertEquals(ContentExportReason.CANCELLED, job.status.failure?.reason)
                assertNull(job.status.file)
                assertEquals(1, observer.states.count { it.phase.isTerminal })
                assertEquals(0, checkNotNull(folder.listFiles()).size)
            } finally { release.countDown() }
        }
    }

    @Test fun `unexpected JPEG provider failures still reach the retained callback and clean staging if the callback throws`() {
        val folder = temporary.newFolder()
        val error = IOException("Test provider failure")
        val captured = AtomicReference<Throwable>()
        val destination = object : ContentExportDestination {
            override val stagingDirectory = folder
            override fun publish(staged: File, spec: ContentExportFileSpec, control: ContentExportControl,
                commit: (() -> ContentExportFile) -> ContentExportFile): ContentExportFile = throw error
        }
        ContentExportStorage(destination, network = ContentExportTestNetwork(mapOf("https://cdn.example/artist.jpg" to jpeg)),
            onUnexpectedFailure = { failure -> captured.set(failure); throw IllegalStateException("Test callback failure") }).use { storage ->
            val run = exportAndWait(storage, ContentExportAsset.ArtistArtwork("Artist", ArtworkImageSource("https://cdn.example/artist.jpg")))
            assertSame(error, captured.get())
            assertEquals(ContentExportPhase.FAILED, run.status.phase)
            assertEquals(ContentExportReason.STORAGE_ERROR, run.status.failure?.reason)
            assertNull(run.status.file)
            assertEquals(0, checkNotNull(folder.listFiles()).size)
        }
    }

    @Test fun `PNG or incomplete TTML are not mislabeled and failures remove all staging files`() {
        val folder = temporary.newFolder()
        val address = "https://cdn.example/image.jpg?token=private"
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val network = ContentExportTestNetwork(mapOf(address to png))
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            assertEquals(ContentExportReason.UNSUPPORTED_ARTWORK_FORMAT,
                exportAndWait(storage, ContentExportAsset.ArtistArtwork("artist", ArtworkImageSource(address))).status.failure?.reason)
            assertEquals(ContentExportReason.INVALID_TTML, exportAndWait(storage, ContentExportAsset.TtmlLyrics("song", "<tt><body/>")).status.failure?.reason)
            assertEquals(ContentExportReason.NO_EDITORIAL, exportAndWait(storage, ContentExportAsset.AlbumEditorial("album",
                ContentExportText("<p> </p><script>hidden()</script>", ContentExportTextFormat.HTML))).status.failure?.reason)
            assertEquals(0, checkNotNull(folder.listFiles()).size)
        }
    }

    @Test fun `known and unknown network sizes plus UTF8 text obey configured byte limits`() {
        val folder = temporary.newFolder()
        val reads = AtomicInteger()
        val address = "https://cdn.example/image.jpg"
        val settings = ContentExportOptions(maxBytes = 4, maxTextBytes = 4)
        val known = ContentExportTestNetwork { url -> ContentExportResponse(url, object : ByteArrayInputStream(jpeg) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads.incrementAndGet(); return super.read(bytes, offset, length) }
        }, jpeg.size.toLong()) }
        ContentExportStorage(AtomicFileContentExportDestination(folder), settings, known).use { storage ->
            assertEquals(ContentExportReason.SIZE_LIMIT, exportAndWait(storage, ContentExportAsset.ArtistArtwork("artist", ArtworkImageSource(address))).status.failure?.reason)
            assertEquals(0, reads.get())
        }
        val chunked = ContentExportTestNetwork { url -> ContentExportResponse(url, jpeg.inputStream()) }
        ContentExportStorage(AtomicFileContentExportDestination(folder), settings, chunked).use { storage ->
            assertEquals(ContentExportReason.SIZE_LIMIT, exportAndWait(storage, ContentExportAsset.ArtistArtwork("artist", ArtworkImageSource(address))).status.failure?.reason)
            assertEquals(ContentExportReason.SIZE_LIMIT, exportAndWait(storage, ContentExportAsset.ArtistBiography("artist", ContentExportText("中文"))).status.failure?.reason)
        }
        assertEquals(0, checkNotNull(folder.listFiles()).size)
    }

    @Test fun `exports run IO off the caller and concurrent identical titles get distinct complete files`() {
        val folder = temporary.newFolder()
        val caller = Thread.currentThread()
        val ioThread = AtomicReference(caller)
        val network = ContentExportTestNetwork { url -> ioThread.set(Thread.currentThread()); ContentExportResponse(url, jpeg.inputStream(), jpeg.size.toLong()) }
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            val first = Observer(); val second = Observer()
            val a = storage.export(ContentExportAsset.ArtistArtwork("same title", ArtworkImageSource("https://cdn.example/a.jpg")), first::accept)
            val b = storage.export(ContentExportAsset.ArtistArtwork("same title", ArtworkImageSource("https://cdn.example/b.jpg")), second::accept)
            first.await(); second.await()
            assertNotSame(caller, ioThread.get())
            assertEquals(ContentExportPhase.SUCCEEDED, a.status.phase)
            assertEquals(ContentExportPhase.SUCCEEDED, b.status.phase)
            assertNotEquals(a.status.file?.uri, b.status.file?.uri)
            assertArrayEquals(jpeg, File(URI(checkNotNull(a.status.file).uri)).readBytes())
            assertArrayEquals(jpeg, File(URI(checkNotNull(b.status.file).uri)).readBytes())
            assertEquals(2, checkNotNull(folder.listFiles()).size)
        }
    }

    @Test fun `bounded queue rejects overflow and queued cancellation releases its slot without IO`() {
        val folder = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val network = blockingNetwork(entered, release)
        val settings = ContentExportOptions(maxConcurrentExports = 1, maxQueuedExports = 1)
        ContentExportStorage(AtomicFileContentExportDestination(folder), settings, network).use { storage ->
            try {
                val first = Observer()
                storage.export(ContentExportAsset.ArtistArtwork("first", ArtworkImageSource("https://cdn.example/first.jpg")), first::accept)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val queued = Observer()
                val cancelled = storage.export(ContentExportAsset.ArtistBiography("cancelled", ContentExportText("never written")), queued::accept)
                assertEquals(ContentExportReason.QUEUE_FULL, exportAndWait(storage, ContentExportAsset.AlbumEditorial("overflow", ContentExportText("not written"))).status.failure?.reason)
                assertTrue(cancelled.cancel())
                queued.await()
                assertEquals(ContentExportPhase.CANCELLED, cancelled.status.phase)
                val replacement = Observer()
                val accepted = storage.export(ContentExportAsset.ArtistBiography("replacement", ContentExportText("written")), replacement::accept)
                assertEquals(ContentExportPhase.QUEUED, accepted.status.phase)
                release.countDown(); first.await(); replacement.await()
                assertEquals(ContentExportPhase.SUCCEEDED, accepted.status.phase)
                assertEquals(1, network.requests.size)
                assertEquals(2, checkNotNull(folder.listFiles()).size)
                assertEquals(1, queued.states.count { it.phase.isTerminal })
            } finally { release.countDown() }
        }
    }

    @Test fun `running cancellation and service close clean pending files and reject new work`() {
        val folder = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val network = blockingNetwork(entered, release)
        val storage = ContentExportStorage(AtomicFileContentExportDestination(folder), ContentExportOptions(maxConcurrentExports = 1), network)
        try {
            val running = Observer()
            val active = storage.export(ContentExportAsset.ArtistArtwork("active", ArtworkImageSource("https://cdn.example/active.jpg")), running::accept)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = Observer()
            val waiting = storage.export(ContentExportAsset.TtmlLyrics("queued", ttml), queued::accept)
            storage.close(); running.await(); queued.await()
            assertEquals(ContentExportPhase.CANCELLED, active.status.phase)
            assertEquals(ContentExportPhase.CANCELLED, waiting.status.phase)
            assertFalse(active.cancel())
            assertEquals(ContentExportReason.CLOSED, exportAndWait(storage, ContentExportAsset.TtmlLyrics("closed", ttml)).status.failure?.reason)
            assertEquals(0, checkNotNull(folder.listFiles()).size)
        } finally { release.countDown(); storage.close() }
    }

    @Test fun `cancellation before commit has no output while publication rejects a late cancel`() {
        for (alreadyPublishing in listOf(false, true)) {
            val folder = temporary.newFolder()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val atomic = AtomicFileContentExportDestination(folder)
            val destination = object : ContentExportDestination {
                override val stagingDirectory = folder
                override fun publish(staged: File, spec: ContentExportFileSpec, control: ContentExportControl,
                    commit: (() -> ContentExportFile) -> ContentExportFile): ContentExportFile {
                    val action = {
                        entered.countDown()
                        try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { control.check() }
                        atomic.publish(staged, spec, control) { it() }
                    }
                    return if (alreadyPublishing) commit(action) else action()
                }
            }
            ContentExportStorage(destination).use { storage ->
                try {
                    val observer = Observer()
                    val job = storage.export(ContentExportAsset.TtmlLyrics("song", ttml), observer::accept)
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertEquals(!alreadyPublishing, job.cancel())
                    release.countDown(); observer.await()
                    assertEquals(if (alreadyPublishing) ContentExportPhase.SUCCEEDED else ContentExportPhase.CANCELLED, job.status.phase)
                    assertEquals(if (alreadyPublishing) 1 else 0, checkNotNull(folder.listFiles()).size)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun `network errors expose a stable reason without exception text or URL query`() {
        val folder = temporary.newFolder()
        val network = ContentExportTestNetwork { throw IOException("Cookie private; https://cdn.example/art.jpg?token=private") }
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = network).use { storage ->
            val result = exportAndWait(storage, ContentExportAsset.ArtistArtwork("artist", ArtworkImageSource("https://cdn.example/art.jpg?token=private"))).status
            assertEquals(ContentExportReason.NETWORK_ERROR, result.failure?.reason)
            assertFalse(result.toString().contains("token="))
            assertEquals(0, checkNotNull(folder.listFiles()).size)
        }
    }

    @Test fun `total duration limits apply even when an input keeps delivering small chunks`() {
        val folder = temporary.newFolder()
        val settings = ContentExportOptions(maxExportDurationMillis = 10)
        val network = ContentExportTestNetwork { url -> ContentExportResponse(url, object : ByteArrayInputStream(jpeg) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                CountDownLatch(1).await(30, TimeUnit.MILLISECONDS)
                return super.read(bytes, offset, length)
            }
        }) }
        ContentExportStorage(AtomicFileContentExportDestination(folder), settings, network).use { storage ->
            assertEquals(ContentExportReason.TIMEOUT, exportAndWait(storage, ContentExportAsset.ArtistArtwork("artist", ArtworkImageSource("https://cdn.example/art.jpg"))).status.failure?.reason)
            assertEquals(0, checkNotNull(folder.listFiles()).size)
        }
    }

    @Test fun `HTTP client follows a bounded relative redirect and reports status without response or URL text`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange -> exchange.responseHeaders.add("Location", "/asset?token=private"); exchange.sendResponseHeaders(302, -1); exchange.close() }
        server.createContext("/asset") { exchange -> exchange.sendResponseHeaders(200, jpeg.size.toLong()); exchange.responseBody.use { it.write(jpeg) }; exchange.close() }
        server.createContext("/denied") { exchange -> exchange.sendResponseHeaders(403, -1); exchange.close() }
        server.createContext("/loop") { exchange -> exchange.responseHeaders.add("Location", "/loop"); exchange.sendResponseHeaders(302, -1); exchange.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val settings = ContentExportOptions(allowCleartextHttp = true, maxRedirects = 1)
            val network = UrlConnectionContentExportNetwork(settings)
            network.open("$base/redirect", ContentExportControl(settings).apply { start() }).use { response ->
                assertEquals("$base/asset?token=private", response.finalUrl)
                assertArrayEquals(jpeg, response.input.readBytes())
            }
            val denied = assertThrows(ContentExportException::class.java) { network.open("$base/denied?token=private", ContentExportControl(settings).apply { start() }) }
            assertEquals(ContentExportReason.HTTP_ERROR, denied.failure.reason)
            assertEquals(403, denied.failure.httpStatus)
            assertFalse(denied.toString().contains("token="))
            assertExportReason(ContentExportReason.HTTP_ERROR) { network.open("$base/loop", ContentExportControl(settings).apply { start() }) }
        } finally { server.stop(0) }
    }

    @Test fun `a one-frame motion file fails before publication and leaves no remux staging file`() {
        val folder = temporary.newFolder()
        val address = "https://cdn.example/still.mp4"
        val bytes = ContentExportTestMedia.initialization() + ContentExportTestMedia.fragment(0)
        ContentExportStorage(AtomicFileContentExportDestination(folder), network = ContentExportTestNetwork(mapOf(address to bytes))).use { storage ->
            val run = exportAndWait(storage, ContentExportAsset.ArtistMotionArtwork("Artist", listOf(MotionArtworkSource(address))))
            assertEquals(ContentExportPhase.FAILED, run.status.phase)
            assertEquals(ContentExportReason.INVALID_MEDIA, run.status.failure?.reason)
            assertNull(run.status.file)
        }
        assertEquals(0, checkNotNull(folder.listFiles()).size)
    }

    /** Check the published container, complete two-sample timeline and unchanged compressed data. */
    private fun assertStandaloneMotion(file: File) {
        val bytes = file.readBytes()
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun boxes(start: Int, end: Int): Map<String, Pair<Int, Int>> {
            val result = LinkedHashMap<String, Pair<Int, Int>>()
            var offset = start
            while (offset < end) {
                val size = input.getInt(offset)
                assertTrue(size >= 8 && size <= end - offset)
                val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
                result[type] = offset + 8 to offset + size
                offset += size
            }
            assertEquals(end, offset)
            return result
        }
        fun Map<String, Pair<Int, Int>>.children(type: String): Map<String, Pair<Int, Int>> =
            checkNotNull(this[type]).let { boxes(it.first, it.second) }
        val top = boxes(0, bytes.size)
        assertFalse(top.containsKey("moof"))
        val movie = top.children("moov")
        assertFalse(movie.containsKey("mvex"))
        val media = movie.children("trak").children("mdia")
        val mdhd = checkNotNull(media["mdhd"]).first
        assertEquals(1000, input.getInt(mdhd + 12))
        assertEquals(2000, input.getInt(mdhd + 16))
        val table = media.children("minf").children("stbl")
        val sizeTable = checkNotNull(table["stsz"]).first
        assertEquals(2, input.getInt(sizeTable + 8))
        val timing = checkNotNull(table["stts"]).first
        assertEquals(1, input.getInt(timing + 4))
        assertEquals(2, input.getInt(timing + 8))
        assertEquals(1000, input.getInt(timing + 12))
        val data = checkNotNull(top["mdat"])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1), bytes.copyOfRange(data.first, data.second))
    }

    private fun blockingNetwork(entered: CountDownLatch, release: CountDownLatch) = ContentExportTestNetwork { url ->
        ContentExportResponse(url, object : ByteArrayInputStream(jpeg) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return super.read(bytes, offset, length)
            }
        }, jpeg.size.toLong())
    }

    private class Observer {
        val states = CopyOnWriteArrayList<ContentExportStatus>()
        private val terminal = CountDownLatch(1)
        fun accept(state: ContentExportStatus) { states += state; if (state.phase.isTerminal) terminal.countDown() }
        fun await() { assertTrue("Export did not finish", terminal.await(5, TimeUnit.SECONDS)) }
    }

    private data class Run(val status: ContentExportStatus, val states: List<ContentExportStatus>)
    private fun exportAndWait(storage: ContentExportStorage, asset: ContentExportAsset): Run {
        val observer = Observer()
        val job = storage.export(asset, observer::accept)
        observer.await()
        return Run(job.status, observer.states.toList())
    }
}
