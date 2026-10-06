package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MotionArtworkDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val options = ContentExportOptions()
    private fun control(settings: ContentExportOptions = options) = ContentExportControl(settings).apply { start() }

    @Test fun `Divide Deluxe Apple private average bandwidth attribute preserves full video variant selection`() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=14865260,_AVG-BANDWIDTH=14865260," +
            "BANDWIDTH=20694175,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE,CODECS=\"hvc1.2.20000000.H150.B0\"," +
            "FRAME-RATE=30.000,RESOLUTION=2160x2160,STABLE-VARIANT-ID=\"private-id\"\nvideo.m3u8\n"
        val master = HlsArtworkParser.parse("https://cdn.example/master.m3u8", text, options) as HlsArtworkPlaylist.Master
        val variant = HlsArtworkParser.selectVideo(master.variants, options)
        assertEquals("https://cdn.example/video.m3u8", variant.url)
        assertEquals(20694175L, variant.bandwidth)
        assertEquals(2160L * 2160, variant.pixels)
        val summary = hlsArtworkStructure(text)
        assertTrue(summary.contains("malformedVariantAttributes=0"))
        assertTrue(summary.contains("codecKinds=[video:hvc1]"))
        assertFalse(summary.contains("private-id"))
    }

    @Test fun `private HLS attributes do not relax duplicate and malformed attribute validation`() {
        for (attributes in listOf("BANDWIDTH=1000,_AVG-BANDWIDTH=500,_AVG-BANDWIDTH=600", "BANDWIDTH=1000,_AVG-BANDWIDTH=", "BANDWIDTH=1000,bad.key=5")) {
            assertExportReason(ContentExportReason.UNSUPPORTED_HLS) { HlsArtworkParser.attributes(attributes) }
        }
    }

    @Test fun `native Apple byte range cover reads one bounded MP4 prefix without fetching it once per segment`() {
        val source = "https://cdn.example/ranges.m3u8"
        val asset = "https://cdn.example/cover.mp4?signature=private"
        val init = ContentExportTestMedia.initialization()
        val one = ContentExportTestMedia.fragment(0)
        val two = ContentExportTestMedia.fragment(1000)
        val expected = init + one + two
        val body = "#EXTM3U\n#EXT-X-MAP:URI=\"$asset\",BYTERANGE=\"${init.size}@0\"\n" +
            "#EXTINF:1,\n#EXT-X-BYTERANGE:${one.size}@${init.size}\n$asset\n" +
            "#EXTINF:1,\n#EXT-X-BYTERANGE:${two.size}\n$asset\n#EXT-X-ENDLIST\n"
        val network = ContentExportTestNetwork(mapOf(source to body.toByteArray(), asset to expected + "unselected trailing bytes".toByteArray()))
        val output = temporary.newFile()
        assertEquals(ContentExportFileFormat.MP4, MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()))
        assertArrayEquals(expected, output.readBytes())
        assertEquals(listOf(source, asset), network.requests)
    }

    @Test fun `range gap overlap and changed file are refused before copying a misleading movie`() {
        val source = "https://cdn.example/ranges.m3u8"
        for ((range, address) in listOf("10@99" to "asset.mp4", "10@101" to "asset.mp4", "10@100" to "other.mp4")) {
            val body = "#EXTM3U\n#EXT-X-MAP:URI=\"asset.mp4\",BYTERANGE=\"100@0\"\n#EXTINF:1,\n#EXT-X-BYTERANGE:$range\n$address\n#EXT-X-ENDLIST\n"
            val network = ContentExportTestNetwork(mapOf(source to body.toByteArray()))
            val output = temporary.newFile()
            assertExportReason(ContentExportReason.UNSUPPORTED_HLS) { MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()) }
            assertEquals(listOf(source), network.requests)
            assertFalse(output.exists())
        }
    }

    @Test fun `implicit range without a previous range for the same resource and overflow are rejected`() {
        for (rows in listOf("#EXT-X-BYTERANGE:10\na.mp4", "#EXT-X-BYTERANGE:10@9223372036854775807\na.mp4",
            "#EXT-X-BYTERANGE:10@100\na.mp4\n#EXTINF:1,\n#EXT-X-BYTERANGE:10\nb.mp4")) {
            val body = "#EXTM3U\n#EXT-X-MAP:URI=\"a.mp4\",BYTERANGE=\"100@0\"\n#EXTINF:1,\n$rows\n#EXT-X-ENDLIST\n"
            assertExportReason(ContentExportReason.UNSUPPORTED_HLS) { HlsArtworkParser.parse("https://cdn.example/list.m3u8", body, options) }
        }
    }

    @Test fun `a byte range movie exceeding the remaining budget fails before requesting the MP4`() {
        val source = "https://cdn.example/ranges.m3u8"
        val body = "#EXTM3U\n#EXT-X-MAP:URI=\"asset.mp4\",BYTERANGE=\"100@0\"\n#EXTINF:1,\n#EXT-X-BYTERANGE:100@100\nasset.mp4\n#EXT-X-ENDLIST\n"
        val settings = options.copy(maxBytes = body.toByteArray().size + 199L)
        val network = ContentExportTestNetwork(mapOf(source to body.toByteArray()))
        val output = temporary.newFile()
        assertExportReason(ContentExportReason.SIZE_LIMIT) { MotionArtworkDownload(network, settings).download(listOf(MotionArtworkSource(source)), output, control(settings)) }
        assertEquals(listOf(source), network.requests)
        assertFalse(output.exists())
    }

    @Test fun `Apple video master seek previews do not prevent downloading the ordinary video rendition`() {
        val address = "https://cdn.example/master.m3u8"
        val master = "#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100,CODECS=\"hvc1.1\",URI=\"preview.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.42E01E\",RESOLUTION=1920x1080\nvideo.m3u8\n"
        val network = ContentExportTestNetwork(mapOf(
            address to master.toByteArray(),
            "https://cdn.example/video.m3u8" to playlist("init.mp4", "one.m4s").toByteArray(),
            "https://cdn.example/init.mp4" to ContentExportTestMedia.initialization(),
            "https://cdn.example/one.m4s" to ContentExportTestMedia.fragment(0),
        ))
        val output = temporary.newFile()
        assertEquals(ContentExportFileFormat.MP4, MotionArtworkDownload(network).download(listOf(MotionArtworkSource(address)), output, control()))
        assertTrue(output.length() > 0)
        assertFalse(network.requests.any { it.endsWith("preview.m3u8") })
    }

    @Test fun `HLS structure diagnostics whitelist tags and formats without leaking any URI or attribute values`() {
        val summary = hlsArtworkStructure("""#EXTM3U
            #EXT-X-MAP:URI="https://SECRET_CDN/SECRET_PATH/init.mp4?token=SECRET_QUERY"
            #EXT-X-DEFINE:NAME="SECRET_DEFINE",VALUE="SECRET_VALUE"
            #EXT-X-KEY:METHOD=AES-128,URI="SECRET_KEY.bin"
            #EXT-X-BYTERANGE:123@0
            #EXT-X-SECRET_CUSTOM:SECRET_VALUE
            #EXTINF:1,
            https://SECRET_CDN/SECRET_PATH/one.m4s?signature=SECRET_QUERY
            #EXTINF:1,
            https://SECRET_CDN/SECRET_PATH/opaque.SECRET_EXTENSION
            #EXT-X-ENDLIST
        """.trimIndent())
        assertTrue(summary.contains("#EXT-X-MAP"))
        assertTrue(summary.contains("#EXT-X-BYTERANGE"))
        assertTrue(summary.contains("segments=2"))
        assertTrue(summary.contains("mapFormats=[mp4]"))
        assertTrue(summary.contains("segmentFormats=[m4s, opaque]"))
        assertTrue(summary.contains("unknownTags=1"))
        assertTrue(summary.contains("hasEndList=true"))
        assertTrue(summary.contains("hasMap=true"))
        assertTrue(summary.contains("hasByteRanges=true"))
        assertTrue(summary.contains("keyMethods=[AES-128]"))
        assertFalse(summary.contains("SECRET"))
        assertFalse(summary.contains("https://"))
        assertFalse(summary.contains("token="))
    }

    @Test fun `an unsupported HLS structure is reported before parsing even when the diagnostic listener throws`() {
        val address = "https://cdn.example/artist.m3u8"
        val body = playlist("init.mp4", "one.m4s").replace("#EXTINF", "#EXT-X-DISCONTINUITY\n#EXTINF")
        val network = ContentExportTestNetwork(mapOf(address to body.toByteArray()))
        val summaries = ArrayList<String>()
        val output = temporary.newFile()
        assertExportReason(ContentExportReason.UNSUPPORTED_HLS) {
            MotionArtworkDownload(network, onStructure = { summaries += it; error("Test diagnostic failure") })
                .download(listOf(MotionArtworkSource(address)), output, control())
        }
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().contains("stage=parse reason=UNSUPPORTED_HLS"))
        assertTrue(summaries.single().contains("#EXT-X-DISCONTINUITY"))
        assertFalse(summaries.single().contains(address))
        assertEquals(listOf(address), network.requests)
        assertFalse(output.exists())
    }

    @Test fun `variant rejection reports only known codec families and audio group presence`() {
        val address = "https://SECRET-CDN.example/SECRET_PATH/artist.m3u8?token=SECRET_QUERY"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"hvc1.SECRET_PROFILE,mp4a.40.2\",AUDIO=\"SECRET_GROUP\"\nchild.m3u8?token=SECRET_QUERY\n"
        val network = ContentExportTestNetwork(mapOf(address to master.toByteArray()))
        val summaries = ArrayList<String>()
        val output = temporary.newFile()
        assertExportReason(ContentExportReason.AUDIO_NOT_ALLOWED) {
            MotionArtworkDownload(network, onStructure = { summaries += it }).download(listOf(MotionArtworkSource(address)), output, control())
        }
        assertTrue(summaries.last().contains("stage=variant reason=AUDIO_NOT_ALLOWED"))
        assertTrue(summaries.last().contains("codecKinds=[audio:mp4a, video:hvc1]"))
        assertTrue(summaries.last().contains("audioGroups=1"))
        assertTrue(summaries.none { it.contains("SECRET") || it.contains("https://") || it.contains("token=") })
        assertEquals(listOf(address), network.requests)
        assertFalse(output.exists())
    }

    @Test fun `structure diagnostic recognizes initialization byte ranges and ignores leading empty lines`() {
        val summary = hlsArtworkStructure("\n\n#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"100@0\"\n#EXTINF:1,\none.m4s\n#EXT-X-ENDLIST\n")
        assertTrue(summary.contains("header=true"))
        assertTrue(summary.contains("hasByteRanges=true"))
        assertTrue(summary.contains("hasMap=true"))
        assertTrue(summary.contains("hasEndList=true"))
        assertFalse(summary.contains("100@0"))
    }

    @Test fun `empty artist and album motion sources keep their own missing reason without output or requests`() {
        for (reason in listOf(ContentExportReason.NO_ARTIST_MOTION_ARTWORK, ContentExportReason.NO_MOTION_ARTWORK)) {
            val output = temporary.newFile()
            val network = ContentExportTestNetwork(emptyMap())
            assertExportReason(reason) {
                MotionArtworkDownload(network, options, reason).download(listOf(MotionArtworkSource(" ")), output, control())
            }
            assertFalse(output.exists())
            assertTrue(network.requests.isEmpty())
        }
    }

    @Test fun `a native direct MP4 is preferred over an HLS source without opening the playlist`() {
        val direct = "https://cdn.example/direct.mp4?token=private"
        val hls = "https://cdn.example/master.m3u8"
        val bytes = ContentExportTestMedia.initialization() + ContentExportTestMedia.fragment(0)
        val network = ContentExportTestNetwork(mapOf(direct to bytes))
        val output = temporary.newFile("video.part")
        assertEquals(ContentExportFileFormat.MP4, MotionArtworkDownload(network).download(listOf(
            MotionArtworkSource(hls, MotionArtworkSourceType.HLS), MotionArtworkSource(direct, MotionArtworkSourceType.DIRECT_MP4),
        ), output, control()))
        assertEquals(listOf(direct), network.requests)
        assertArrayEquals(bytes, output.readBytes())
        assertEquals(1, network.closed.get())
    }

    @Test fun `relative HLS map and fragments are joined after selecting one permitted video variant`() {
        val entry = "https://origin.example/original.m3u8?token=private"
        val final = "https://cdn.example/covers/master.m3u8?token=private"
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160,CODECS="avc1.640028"
            high/list.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="avc1.640028"
            video/list.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="music"
            audio/list.m3u8
        """.trimIndent()
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXT-X-MAP:URI="../init.mp4?sig=a,b"
            #EXTINF:1.0,
            ../fragments/one.m4s
            #EXTINF:1.0,
            ../fragments/two.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val init = ContentExportTestMedia.initialization()
        val first = ContentExportTestMedia.fragment(0)
        val second = ContentExportTestMedia.fragment(1000)
        val network = ContentExportTestNetwork(mapOf(
            entry to master.toByteArray(),
            "https://cdn.example/covers/video/list.m3u8" to media.toByteArray(),
            "https://cdn.example/covers/init.mp4?sig=a,b" to init,
            "https://cdn.example/covers/fragments/one.m4s" to first,
            "https://cdn.example/covers/fragments/two.m4s" to second,
        ), mapOf(entry to final))
        val settings = options.copy(maxVideoPixels = 1920L * 1080)
        val output = temporary.newFile("hls.part")
        MotionArtworkDownload(network, settings).download(listOf(MotionArtworkSource(entry)), output, control(settings))
        assertArrayEquals(init + first + second, output.readBytes())
        assertEquals(listOf(entry, "https://cdn.example/covers/video/list.m3u8", "https://cdn.example/covers/init.mp4?sig=a,b",
            "https://cdn.example/covers/fragments/one.m4s", "https://cdn.example/covers/fragments/two.m4s"), network.requests)
        assertEquals(network.requests.size, network.closed.get())
        assertEquals(1L, MotionArtworkMp4.validate(output, control()).id)
    }

    @Test fun `an actual HLS response is detected even if its source hint and URL claim MP4`() {
        val source = "https://cdn.example/art.mp4"
        val init = ContentExportTestMedia.initialization()
        val fragment = ContentExportTestMedia.fragment(0)
        val network = ContentExportTestNetwork(mapOf(source to playlist("init.mp4", "one.m4s").toByteArray(),
            "https://cdn.example/init.mp4" to init, "https://cdn.example/one.m4s" to fragment))
        val output = temporary.newFile()
        MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source, MotionArtworkSourceType.DIRECT_MP4)), output, control())
        assertArrayEquals(init + fragment, output.readBytes())
    }

    @Test fun `playlist text mislabeled video cannot become a successful MP4`() {
        val source = "https://cdn.example/art.mp4"
        val network = ContentExportTestNetwork(mapOf(source to "<html>expired token</html>".toByteArray()))
        val output = temporary.newFile()
        assertExportReason(ContentExportReason.INVALID_MEDIA) {
            MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control())
        }
        assertFalse(output.exists())
    }

    @Test fun `actual track dimensions enforce the pixel limit even without native size hints`() {
        val source = "https://cdn.example/art.mp4"
        val network = ContentExportTestNetwork(mapOf(source to (ContentExportTestMedia.initialization() + ContentExportTestMedia.fragment(0))))
        val output = temporary.newFile()
        val settings = options.copy(maxVideoPixels = 320L * 240 - 1)
        assertExportReason(ContentExportReason.PLAYLIST_LIMIT) { MotionArtworkDownload(network, settings).download(listOf(MotionArtworkSource(source)), output, control(settings)) }
        assertFalse(output.exists())
    }

    @Test fun `opaque transport stream segment URLs still return an explicit unsupported TS reason`() {
        val source = "https://cdn.example/list.m3u8"
        val ts = ByteArray(376).apply { this[0] = 0x47; this[188] = 0x47 }
        val network = ContentExportTestNetwork(mapOf(source to playlist("init.mp4", "opaque").toByteArray(),
            "https://cdn.example/init.mp4" to ContentExportTestMedia.initialization(), "https://cdn.example/opaque" to ts))
        val output = temporary.newFile()
        assertExportReason(ContentExportReason.UNSUPPORTED_TS) { MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()) }
        assertFalse(output.exists())
    }

    @Test fun `encrypted HLS refuses keys and never opens a key or media URL`() {
        val source = "https://cdn.example/list.m3u8"
        listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin?token=private\"", "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\",URI=\"skd://private\"")
            .forEach { key ->
                val network = ContentExportTestNetwork(mapOf(source to ("#EXTM3U\n$key\n" + playlist("init.mp4", "one.m4s").substringAfter('\n')).toByteArray()))
                val output = temporary.newFile()
                assertExportReason(ContentExportReason.ENCRYPTED_MEDIA) { MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()) }
                assertEquals(listOf(source), network.requests)
                assertFalse(output.exists())
            }
    }

    @Test fun `audio variants and audio or DRM MP4 containers are excluded`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS=\"mp4a.40.2\"\naudio.m3u8\n"
        val parsed = HlsArtworkParser.parse("https://cdn.example/list.m3u8", master, options) as HlsArtworkPlaylist.Master
        assertExportReason(ContentExportReason.AUDIO_NOT_ALLOWED) { HlsArtworkParser.selectVideo(parsed.variants, options) }
        listOf(ContentExportReason.AUDIO_NOT_ALLOWED to ContentExportTestMedia.initialization("soun"),
            ContentExportReason.ENCRYPTED_MEDIA to ContentExportTestMedia.initialization(encrypted = true)).forEach { (reason, init) ->
            val source = "https://cdn.example/art.mp4"
            val output = temporary.newFile()
            val network = ContentExportTestNetwork(mapOf(source to (init + ContentExportTestMedia.fragment(0))))
            assertExportReason(reason) { MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()) }
            assertFalse(output.exists())
        }
    }

    @Test fun `unsupported finite playlist forms never fetch segments or disguise TS as MP4`() {
        val cases = listOf(
            "#EXTM3U\n#EXTINF:1,\none.ts\n#EXT-X-ENDLIST\n" to ContentExportReason.UNSUPPORTED_TS,
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\none.m4s\n" to ContentExportReason.LIVE_PLAYLIST,
            playlist("init.mp4", "one.m4s").replace("#EXTINF", "#EXT-X-BYTERANGE:10@0\n#EXTINF") to ContentExportReason.UNSUPPORTED_HLS,
            playlist("init.mp4", "one.m4s").replace("#EXTINF", "#EXT-X-DISCONTINUITY\n#EXTINF") to ContentExportReason.UNSUPPORTED_HLS,
            playlist("init.mp4", "one.m4s").replace("#EXT-X-ENDLIST", "#EXT-X-MAP:URI=\"different.mp4\"\n#EXT-X-ENDLIST") to ContentExportReason.UNSUPPORTED_HLS,
            "#EXTM3U\n#EXTINF:1,\none.m4s\n#EXT-X-MAP:URI=\"late.mp4\"\n#EXT-X-ENDLIST\n" to ContentExportReason.UNSUPPORTED_HLS,
        )
        for ((body, reason) in cases) {
            val source = "https://cdn.example/list.m3u8"
            val network = ContentExportTestNetwork(mapOf(source to body.toByteArray()))
            val output = temporary.newFile()
            assertExportReason(reason) { MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()) }
            assertEquals(listOf(source), network.requests)
            assertFalse(output.exists())
        }
    }

    @Test fun `HLS size segment duration and nesting limits are enforced before further requests`() {
        val base = "https://cdn.example/list.m3u8"
        assertExportReason(ContentExportReason.PLAYLIST_LIMIT) {
            HlsArtworkParser.parse(base, playlist("init.mp4", "one.m4s", "two.m4s"), options.copy(maxSegments = 1))
        }
        assertExportReason(ContentExportReason.PLAYLIST_LIMIT) {
            HlsArtworkParser.parse(base, playlist("init.mp4", "one.m4s"), options.copy(maxHlsDurationSeconds = 0.5))
        }
        val network = ContentExportTestNetwork(mapOf(base to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.4d001e\"\nchild.m3u8\n".toByteArray()))
        val output = temporary.newFile()
        val depth = options.copy(maxPlaylistDepth = 1)
        assertExportReason(ContentExportReason.UNSUPPORTED_HLS) { MotionArtworkDownload(network, depth).download(listOf(MotionArtworkSource(base)), output, control(depth)) }
        assertEquals(listOf(base), network.requests)
        val tooSmall = options.copy(maxPlaylistBytes = 8)
        assertExportReason(ContentExportReason.SIZE_LIMIT) { MotionArtworkDownload(network, tooSmall).download(listOf(MotionArtworkSource(base)), output, control(tooSmall)) }
        assertFalse(output.exists())
    }

    @Test fun `truncated or absolute-addressed fragments do not publish a corrupt concatenation`() {
        val source = "https://cdn.example/list.m3u8"
        val init = ContentExportTestMedia.initialization()
        val cases = listOf(
            ContentExportTestMedia.fragment(0).dropLast(1).toByteArray() to ContentExportReason.INVALID_MEDIA,
            ContentExportTestMedia.fragment(0, absoluteAddress = true) to ContentExportReason.UNSUPPORTED_HLS,
            ContentExportTestMedia.fragment(0, encrypted = true) to ContentExportReason.ENCRYPTED_MEDIA,
            ContentExportTestMedia.fragment(0, trackId = 2) to ContentExportReason.INVALID_MEDIA,
        )
        for ((fragment, reason) in cases) {
            val network = ContentExportTestNetwork(mapOf(source to playlist("init.mp4", "one.m4s").toByteArray(),
                "https://cdn.example/init.mp4" to init, "https://cdn.example/one.m4s" to fragment))
            val output = temporary.newFile()
            assertExportReason(reason) { MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source)), output, control()) }
            assertFalse(output.exists())
        }
    }

    @Test fun `a real HLS alternative may replace a failed direct native asset`() {
        val direct = "https://cdn.example/expired.mp4"
        val source = "https://cdn.example/list.m3u8"
        val init = ContentExportTestMedia.initialization()
        val fragment = ContentExportTestMedia.fragment(0)
        val network = ContentExportTestNetwork(mapOf(direct to "expired".toByteArray(), source to playlist("init.mp4", "one.m4s").toByteArray(),
            "https://cdn.example/init.mp4" to init, "https://cdn.example/one.m4s" to fragment))
        val output = temporary.newFile()
        MotionArtworkDownload(network).download(listOf(MotionArtworkSource(source, MotionArtworkSourceType.HLS),
            MotionArtworkSource(direct, MotionArtworkSourceType.DIRECT_MP4)), output, control())
        assertEquals(direct, network.requests.first())
        assertArrayEquals(init + fragment, output.readBytes())
    }

    private fun playlist(init: String, vararg segments: String): String =
        "#EXTM3U\n#EXT-X-MAP:URI=\"$init\"\n" + segments.joinToString("") { "#EXTINF:1.0,\n$it\n" } + "#EXT-X-ENDLIST\n"
}

internal fun assertExportReason(reason: ContentExportReason, action: () -> Unit) {
    val error = assertThrows(ContentExportException::class.java) { action() }
    assertEquals(reason, error.failure.reason)
}

internal class ContentExportTestNetwork(private val create: (String) -> ContentExportResponse) : ContentExportNetwork {
    val requests = CopyOnWriteArrayList<String>()
    val closed = AtomicInteger()
    constructor(assets: Map<String, ByteArray>, redirected: Map<String, String> = emptyMap()) : this({ url ->
        val data = assets[url] ?: throw IOException("No test asset")
        ContentExportResponse(redirected[url] ?: url, data.inputStream(), data.size.toLong())
    })
    override fun open(url: String, control: ContentExportControl): ContentExportResponse {
        control.check()
        requests += url
        val response = create(url)
        return ContentExportResponse(response.finalUrl, response.input, response.contentLength, response.mimeType) {
            try { response.close() } finally { closed.incrementAndGet() }
        }
    }
}

/** Synthetic ISO-BMFF boxes test addressing/track policy, not hardware decoding of a real CDN clip. */
internal object ContentExportTestMedia {
    fun initialization(handler: String = "vide", encrypted: Boolean = false): ByteArray {
        val tkhd = ByteArray(84).also { bytes -> ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 7); putInt(12, 1); putInt(40, 0x10000); putInt(56, 0x10000); putInt(72, 0x40000000)
            putInt(76, 320 shl 16); putInt(80, 240 shl 16)
        } }
        val mvhd = ByteArray(100).also { bytes -> ByteBuffer.wrap(bytes).apply {
            putInt(12, 1000); putInt(20, 0x10000); putShort(24, 0x100); putInt(36, 0x10000)
            putInt(52, 0x10000); putInt(68, 0x40000000); putInt(96, 2)
        } }
        val mdhd = ByteArray(24).also { bytes -> ByteBuffer.wrap(bytes).apply { putInt(12, 1000); putShort(20, 0x55c4) } }
        val entry = ByteArray(78).also { bytes -> ByteBuffer.wrap(bytes).apply {
            putShort(6, 1); putShort(24, 320); putShort(26, 240); putInt(28, 0x480000); putInt(32, 0x480000)
            putShort(40, 1); putShort(74, 24); putShort(76, -1)
        } }
        val description = box("stsd", ints(0, 1), box(if (encrypted) "encv" else "avc1", entry,
            box("avcC", byteArrayOf(1, 0x42, 0, 0x1e, -1, -32, 0))))
        val sampleTable = box("stbl", description, box("stts", ints(0, 0)), box("stsc", ints(0, 0)), box("stsz", ints(0, 0, 0)), box("stco", ints(0, 0)))
        val media = box("mdia", box("mdhd", mdhd), box("hdlr", ints(0, 0), ascii(handler), ByteArray(12), ascii("Artwork\u0000")),
            box("minf", box("vmhd", ints(1), ByteArray(8)), box("dinf", box("dref", ints(0, 1), box("url ", ints(1)))), sampleTable))
        return box("ftyp", ascii("iso6"), ints(1), ascii("iso6mp41")) + box("moov", box("mvhd", mvhd), box("trak", box("tkhd", tkhd), media),
            box("mvex", box("trex", ints(0, 1, 1, 1000, 4, 0))))
    }

    fun fragment(time: Int, absoluteAddress: Boolean = false, encrypted: Boolean = false, trackId: Int = 1): ByteArray {
        fun moof(offset: Int): ByteArray = box("moof", box("mfhd", ints(0, time / 1000 + 1)), box("traf",
            box("tfhd", ints(if (absoluteAddress) 1 else 0x020000, trackId), if (absoluteAddress) ByteArray(8) else ByteArray(0)),
            box("tfdt", ints(0, time)), box("trun", ints(0x301, 1, offset, 1000, 4)), if (encrypted) box("senc", ints(0, 1)) else ByteArray(0)))
        val provisional = moof(0)
        return moof(provisional.size + 8) + box("mdat", byteArrayOf(0, 0, 0, 1))
    }

    private fun ascii(value: String) = value.toByteArray(StandardCharsets.US_ASCII)
    private fun ints(vararg values: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data -> values.forEach(data::writeInt) }
    }.toByteArray()
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(8 + payload.sumOf { it.size }); data.write(ascii(type)); payload.forEach(data::write)
        }
        return output.toByteArray()
    }
}
