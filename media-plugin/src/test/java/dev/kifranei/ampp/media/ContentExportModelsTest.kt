package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class ContentExportModelsTest {
    @Test fun `each native asset has a distinct missing reason and a bilingual label`() {
        val assets = listOf(
            ContentExportAsset.ArtistArtwork("artist", null) to ContentExportReason.NO_ARTIST_ARTWORK,
            ContentExportAsset.ArtistMotionArtwork("artist", emptyList()) to ContentExportReason.NO_ARTIST_MOTION_ARTWORK,
            ContentExportAsset.AlbumMotionArtwork("album", emptyList()) to ContentExportReason.NO_MOTION_ARTWORK,
            ContentExportAsset.TtmlLyrics("song", null) to ContentExportReason.NO_TTML,
            ContentExportAsset.ArtistBiography("artist", null) to ContentExportReason.NO_BIOGRAPHY,
            ContentExportAsset.AlbumEditorial("album", null) to ContentExportReason.NO_EDITORIAL,
        )
        assertEquals(6, assets.map { it.first.kind }.toSet().size)
        assets.forEach { (asset, reason) ->
            assertEquals(reason, asset.unavailableReason())
            assertNotEquals(reason.message("en"), reason.message("zh-CN"))
            assertNotEquals(asset.kind.label("en"), asset.kind.label("zh-Hant"))
        }
        assertTrue(assets[0].first.kind.label("en").contains("still JPEG"))
        assets.filter { it.first is ContentExportAsset.ArtistMotionArtwork || it.first is ContentExportAsset.AlbumMotionArtwork }.forEach {
            assertTrue(it.first.kind.label("en").contains("silent MP4"))
            assertTrue(it.first.kind.label("zh").contains("MP4"))
        }
    }

    @Test fun `native artwork template resolves full dimensions without altering signed query parameters`() {
        val source = ArtworkImageSource("https://cdn.example/art/{w}x{h}{c}.{f}?sig=a%2Fb&token=private", 4000, 3000)
        assertEquals("https://cdn.example/art/4000x3000bb.jpg?sig=a%2Fb&token=private", source.jpegUrl())
        assertEquals("https://cdn.example/art/4000x3000bb.jpg?token=200x200.jpg", ArtworkImageSource(
            "https://cdn.example/art/200x200bb.jpg?token=200x200.jpg", 4000, 3000,
        ).jpegUrl())
        assertEquals("https://cdn.example/art/200x200bb.jpg", ArtworkImageSource("https://cdn.example/art/200x200bb.jpg").jpegUrl())
        assertEquals("https://cdn.example/art/4000x3000bb.jpg?next=/200x200.jpg", ArtworkImageSource(
            "https://cdn.example/art/200x200bb.jpg?next=/200x200.jpg", 4000, 3000,
        ).jpegUrl())
        assertEquals("https://cdn.example/art/200x200bb.jpg", ArtworkImageSource("https://cdn.example/art/200x200bb.jpg", 4000, 3000).jpegUrl(false))
    }

    @Test fun `unknown artwork dimensions never fabricate an original size`() {
        val error = assertThrows(ContentExportException::class.java) { ArtworkImageSource("https://cdn.example/{w}x{h}.jpg").jpegUrl() }
        assertEquals(ContentExportReason.ARTWORK_DIMENSIONS_UNKNOWN, error.failure.reason)
    }

    @Test fun `literal dimension placeholders are case insensitive and signed query templates stay untouched`() {
        val source = ArtworkImageSource("https://cdn.example/{W}x{H}{c}.{f}?next={w}&signature=private", 2400, 1600)
        assertEquals("https://cdn.example/2400x1600bb.jpg?next={w}&signature=private", source.jpegUrl())
        for (template in listOf("{W}", "{H}")) {
            assertExportReason(ContentExportReason.ARTWORK_DIMENSIONS_UNKNOWN) { ArtworkImageSource("https://cdn.example/$template.jpg").jpegUrl() }
        }
        assertEquals("https://cdn.example/artist.jpg?width={w}", ArtworkImageSource("https://cdn.example/artist.jpg?width={w}").jpegUrl())
    }

    @Test fun `URL-bearing models redact signed assets in diagnostic text`() {
        val secret = "cookie=private&token=private"
        val source = MotionArtworkSource("https://cdn.example/secret-path.mp4?$secret")
        val artist = ContentExportAsset.ArtistArtwork("artist", ArtworkImageSource("https://cdn.example/secret-path.jpg?$secret"))
        listOf(source.toString(), artist.toString(), ContentExportAsset.ArtistMotionArtwork("artist", listOf(source)).toString(),
            ContentExportAsset.AlbumMotionArtwork("album", listOf(source)).toString()).forEach {
            assertFalse(it.contains(secret)); assertFalse(it.contains("secret-path"))
        }
        assertFalse(ContentExportException(ContentExportFailure(ContentExportReason.NETWORK_ERROR)).toString().contains("http"))
    }

    @Test fun `editorial HTML preserves readable paragraphs lists and Unicode entities`() {
        val html = "<p>A &amp; B &mdash; &#x1F3B5;</p><script>token=private</script><style>.hidden{}</style>" +
            "<p>Next<br>line with <a href='https://example/?token=private'>link</a>.</p><ul><li>One</li><li>Two &rsquo; &#233;</li></ul>"
        assertEquals("A & B — 🎵\n\nNext\nline with link.\n\n- One\n- Two ’ é", ContentExportText(html, ContentExportTextFormat.HTML).plainText())
        assertEquals("&lt;", ContentExportHtml.toPlainText("&amp;lt;"))
        assertEquals("&#xD800;", ContentExportHtml.toPlainText("&#xD800;"))
        assertEquals("  original\r\nparagraph  ", ContentExportText("  original\r\nparagraph  ").plainText())
    }

    @Test fun `filenames handle reserved names separators controls and multibyte truncation`() {
        val reserved = contentExportFileName("CON", ContentExportKind.ARTIST_ARTWORK, ContentExportFileFormat.JPEG, "id")
        assertTrue(reserved.startsWith("_CON-"))
        val dangerous = contentExportFileName(" .. /a\\b:c*?\"<>|\n\u202etext . ", ContentExportKind.TTML_LYRICS, ContentExportFileFormat.TTML, "../../id")
        assertFalse(dangerous.any { it in "/\\:*?\"<>|" || it.code < 32 || it == '\u202e' })
        assertTrue(dangerous.endsWith("-id.ttml"))
        val unicode = contentExportFileName("🎵艺术家".repeat(100), ContentExportKind.ARTIST_BIOGRAPHY, ContentExportFileFormat.TEXT, "stable")
        assertTrue(unicode.toByteArray(StandardCharsets.UTF_8).size < 255)
        assertFalse(unicode.contains('\uFFFD'))
        assertNotEquals(contentExportFileName("title", ContentExportKind.ALBUM_EDITORIAL, ContentExportFileFormat.TEXT),
            contentExportFileName("title", ContentExportKind.ALBUM_EDITORIAL, ContentExportFileFormat.TEXT))
    }

    @Test fun `correct MIME and extensions do not mislabel video or TTML as images`() {
        assertEquals("image/jpeg", ContentExportFileFormat.JPEG.mimeType)
        assertEquals("jpg", ContentExportFileFormat.JPEG.extension)
        assertEquals("video/mp4", ContentExportFileFormat.MP4.mimeType)
        assertEquals("mp4", ContentExportFileFormat.MP4.extension)
        assertEquals("application/ttml+xml", ContentExportFileFormat.TTML.mimeType)
        assertEquals("ttml", ContentExportFileFormat.TTML.extension)
        assertEquals("text/plain", ContentExportFileFormat.TEXT.mimeType)
    }

    @Test fun `artist static artwork and both motion kinds have separate legal output names`() {
        val still = contentExportFileName("Artist / Album", ContentExportKind.ARTIST_ARTWORK, ContentExportFileFormat.JPEG, "same")
        val artistMotion = contentExportFileName("Artist / Album", ContentExportKind.ARTIST_MOTION_ARTWORK, ContentExportFileFormat.MP4, "same")
        val albumMotion = contentExportFileName("Artist / Album", ContentExportKind.ALBUM_MOTION_ARTWORK, ContentExportFileFormat.MP4, "same")
        assertTrue(still.endsWith("-artist-artwork-same.jpg"))
        assertTrue(artistMotion.endsWith("-artist-motion-same.mp4"))
        assertTrue(albumMotion.endsWith("-album-motion-same.mp4"))
        assertEquals(3, setOf(still, artistMotion, albumMotion).size)
        assertNull(ContentExportAsset.ArtistMotionArtwork("artist", listOf(MotionArtworkSource("https://cdn.example/artist.mp4"))).unavailableReason())
        assertEquals(ContentExportReason.NO_ARTIST_MOTION_ARTWORK,
            ContentExportAsset.ArtistMotionArtwork("artist", listOf(MotionArtworkSource("  "))).unavailableReason())
    }

    @Test fun `TTML envelope accepts native namespace document but refuses incomplete or rendered lyrics`() {
        val complete = "\uFEFF<?xml version=\"1.0\"?>\r\n<!-- native --><tt:tt xmlns:tt=\"http://www.w3.org/ns/ttml\"><tt:body/></tt:tt>\r\n"
        assertTrue(isCompleteExportTtml(complete))
        listOf("hello world", "<tt><body/>", "<p begin=\"00:01\">word</p>", "<ttx></ttx>").forEach { assertFalse(isCompleteExportTtml(it)) }
    }

    @Test fun `configuration rejects ineffective limits and URL policy excludes credential and local schemes`() {
        assertThrows(IllegalArgumentException::class.java) { ContentExportOptions(maxBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { ContentExportOptions(maxConcurrentExports = 0) }
        assertThrows(IllegalArgumentException::class.java) { ContentExportOptions(maxHlsDurationSeconds = Double.NaN) }
        val options = ContentExportOptions()
        listOf("file:///tmp/art.mp4", "content://media/art", "http://example/art", "https://user:password@example/art").forEach { address ->
            assertEquals(ContentExportReason.UNSUPPORTED_URL, assertThrows(ContentExportException::class.java) { checkedContentExportUri(address, options) }.failure.reason)
        }
        assertEquals("https", checkedContentExportUri("https://example/art?token=private", options).scheme)
    }
}
