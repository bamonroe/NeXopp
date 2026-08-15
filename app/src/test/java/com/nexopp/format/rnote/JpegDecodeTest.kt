package com.nexopp.format.rnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The baseline JPEG decoder, checked against files ImageMagick and `cjpeg` wrote — never our own
 * code — with the expected pixels dumped by libjpeg itself. See the fixtures' README for how the
 * pair was made and why a small per-channel tolerance is correct: IDCT and colour-conversion
 * rounding legitimately differ between decoders.
 */
class JpegDecodeTest {

    /** How far one channel may drift from libjpeg's own decode of the same file. */
    private val tolerance = 2

    @Test
    fun `a greyscale jpeg decodes to libjpeg's pixels`() = assertMatchesDump("grey")

    @Test
    fun `an unsubsampled colour jpeg decodes to libjpeg's pixels`() = assertMatchesDump("yuv444")

    @Test
    fun `a 4-2-2 jpeg decodes to libjpeg's pixels`() = assertMatchesDump("yuv422")

    @Test
    fun `a 4-2-0 jpeg decodes to libjpeg's pixels`() = assertMatchesDump("yuv420")

    @Test
    fun `restart markers are honoured`() = assertMatchesDump("restart")

    @Test
    fun `a progressive jpeg is null, not wrong pixels`() {
        assertNull(JpegDecode.decode(fixture("progressive.jpg")))
    }

    @Test
    fun `anything malformed is null rather than an exception`() {
        val whole = fixture("yuv444.jpg")
        assertNull(JpegDecode.decode(ByteArray(0)))
        assertNull(JpegDecode.decode(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(JpegDecode.decode(whole.copyOfRange(0, whole.size / 2)))
        assertNull(JpegDecode.decode(ByteArray(64) { 0x55 }))
        // A PNG is not a JPEG.
        assertNull(JpegDecode.decode(RawImageCodec.encodePng(ByteArray(4) { -1 }, 1, 1)))
    }

    /** Decode `name.jpg` and compare every channel against libjpeg's `name.rgb` dump. */
    private fun assertMatchesDump(name: String) {
        val image = JpegDecode.decode(fixture("$name.jpg"))!!
        val expected = fixture("$name.rgb")
        assertEquals(20, image.width)
        assertEquals(13, image.height)
        assertEquals(expected.size, image.width * image.height * 3)
        for (pixel in 0 until image.width * image.height) {
            for (channel in 0 until 3) {
                val ours = image.rgba[pixel * 4 + channel].toInt() and 0xFF
                val libjpeg = expected[pixel * 3 + channel].toInt() and 0xFF
                assertTrue(
                    "$name pixel ${pixel % image.width},${pixel / image.width} channel $channel: $ours vs $libjpeg",
                    Math.abs(ours - libjpeg) <= tolerance,
                )
            }
            assertEquals(0xFF, image.rgba[pixel * 4 + 3].toInt() and 0xFF)
        }
    }

    /** A checked-in file from `app/src/test/resources/fixtures/jpeg/`. */
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/jpeg/$name").use {
            it?.readBytes() ?: error("missing fixture fixtures/jpeg/$name")
        }
}
