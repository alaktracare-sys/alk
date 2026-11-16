package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class ArtworkColorExtractorTest {

    @Test
    fun `extracts dominant color from JPEG EXIF bytes and PNG tEXt chunk bytes`() {
        val jpegSegment = (
            "\u00FF\u00D8\u00FF\u00E1\u0000\u0040Exif\u0000\u0000" +
                "AlaktraMusic DOMINANT_COLOR:#e91e63\u0000"
            ).toByteArray(StandardCharsets.ISO_8859_1)

        assertEquals("#E91E63", ArtworkColorExtractor.extractMetadataColorHexFromBytes(jpegSegment))

        val pngTextChunk = (
            "\u0089PNG\r\n\u001A\n\u0000\u0000\u0000\u001DtEXtComment\u0000DOMINANT_COLOR:#3B82F6"
            ).toByteArray(StandardCharsets.ISO_8859_1)

        assertEquals("#3B82F6", ArtworkColorExtractor.extractMetadataColorHexFromBytes(pngTextChunk))
    }

    @Test
    fun `scans both head and tail of large offline cover files for embedded metadata`() {
        val tempFile = File.createTempFile("offline_cover_test_", ".png")
        try {
            // Write > 100 KB of body data followed by a PNG tEXt metadata chunk near EOF
            val padding = ByteArray(110 * 1024) { 0x42 }
            val footerTag = "tEXtDescription\u0000DOMINANT_COLOR:#ff6b35IEND".toByteArray(StandardCharsets.ISO_8859_1)
            tempFile.outputStream().use { out ->
                out.write(padding)
                out.write(footerTag)
            }

            assertEquals("#FF6B35", ArtworkColorExtractor.scanFileBytesForMetadataHex(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `normalizes 6 and 8 digit hex strings and ignores unrelated binary data`() {
        assertEquals("#1ED760", ArtworkColorExtractor.normalizeHex("#1ed760"))
        assertEquals("#1ED760", ArtworkColorExtractor.normalizeHex("1ed760"))
        assertEquals("#1ED760", ArtworkColorExtractor.normalizeHex("#FF1ED760"))
        assertNull(ArtworkColorExtractor.normalizeHex("not-a-color"))

        val untaggedBytes = ByteArray(512) { (it % 251).toByte() }
        assertNull(ArtworkColorExtractor.extractMetadataColorHexFromBytes(untaggedBytes))
    }
}
