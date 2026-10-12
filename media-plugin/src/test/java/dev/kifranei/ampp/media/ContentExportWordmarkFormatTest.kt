package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ContentExportWordmarkFormatTest {
    private fun file(bytes: ByteArray) = File.createTempFile("wordmark", ".part").apply { deleteOnExit(); writeBytes(bytes) }
    private val iend = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    @Test fun `complete PNG is kept as PNG, JPEG fallback as JPEG, anything else rejected`() {
        assertEquals(ContentExportFileFormat.PNG, detectExportWordmarkFormat(file(signature + ByteArray(13) + iend)))
        assertEquals(ContentExportFileFormat.JPEG, detectExportWordmarkFormat(file(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0, 0xff.toByte(), 0xd9.toByte()))))
        assertExportReason(ContentExportReason.UNSUPPORTED_WORDMARK_FORMAT) { detectExportWordmarkFormat(file(signature + ByteArray(40))) }
        assertExportReason(ContentExportReason.UNSUPPORTED_WORDMARK_FORMAT) { detectExportWordmarkFormat(file("<svg/>".toByteArray())) }
    }
}
