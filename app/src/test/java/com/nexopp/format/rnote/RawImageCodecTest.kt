package com.nexopp.format.rnote

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw-pixels → PNG encoder and the premultiplied-alpha fix-up a `bitmapimage` goes through.
 * The assertions read the encoder's own output back (chunk names, inflated scanline size) because
 * nothing on the JVM side can decode a PNG for us — `android.graphics` is a stub in unit tests.
 */
class RawImageCodecTest {

    /** A 2x2 opaque-red RGBA8 buffer, the shape `text-image.rnote` carries. */
    private fun redSquare(): ByteArray = ByteArray(2 * 2 * 4) { i ->
        if (i % 4 == 0 || i % 4 == 3) 0xFF.toByte() else 0
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished()) out.write(buffer, 0, inflater.inflate(buffer))
        inflater.end()
        return out.toByteArray()
    }

    /** The body of the first chunk of [type] in a PNG. */
    private fun chunkBody(png: ByteArray, type: String): ByteArray {
        var offset = RawImageCodec.PNG_SIGNATURE.size
        while (offset < png.size) {
            val length = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (png[offset + i].toInt() and 0xFF) }
            val name = String(png, offset + 4, 4, Charsets.US_ASCII)
            if (name == type) return png.copyOfRange(offset + 8, offset + 8 + length)
            offset += 12 + length
        }
        error("no $type chunk")
    }

    @Test
    fun `an encoded png carries the signature and the three chunks`() {
        val png = RawImageCodec.encodePng(redSquare(), 2, 2)
        assertArrayEquals(
            RawImageCodec.PNG_SIGNATURE,
            png.copyOfRange(0, RawImageCodec.PNG_SIGNATURE.size),
        )
        val ihdr = chunkBody(png, "IHDR")
        assertEquals(13, ihdr.size)
        assertEquals(2, ihdr[3].toInt())
        assertEquals(2, ihdr[7].toInt())
        assertEquals(8, ihdr[8].toInt())
        assertEquals(6, ihdr[9].toInt())
        assertEquals(0, ihdr[12].toInt())
        assertEquals(0, chunkBody(png, "IEND").size)
    }

    @Test
    fun `the idat inflates back to a filter byte per row plus the pixels`() {
        val png = RawImageCodec.encodePng(redSquare(), 2, 2)
        val scanlines = inflate(chunkBody(png, "IDAT"))
        assertEquals(2 * 2 * 4 + 2, scanlines.size)
        assertEquals(0, scanlines[0].toInt())
        assertEquals(0xFF.toByte(), scanlines[1])
        assertEquals(0, scanlines[9].toInt())
    }

    @Test
    fun `a wrongly sized buffer is rejected`() {
        val error = runCatching { RawImageCodec.encodePng(ByteArray(15), 2, 2) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `unpremultiplying scales a half-alpha pixel back up`() {
        // Half-alpha red stored premultiplied (0x80, 0, 0, 0x80) is full red at half alpha.
        val pixels = byteArrayOf(0x80.toByte(), 0, 0, 0x80.toByte())
        val straight = RawImageCodec.unpremultiply(pixels)
        assertEquals(0xFF, straight[0].toInt() and 0xFF)
        assertEquals(0, straight[1].toInt())
        assertEquals(0x80, straight[3].toInt() and 0xFF)
    }

    @Test
    fun `opaque and fully transparent pixels are left alone`() {
        val pixels = byteArrayOf(0x40, 0x50, 0x60, 0xFF.toByte(), 0x11, 0x22, 0x33, 0)
        assertArrayEquals(pixels, RawImageCodec.unpremultiply(pixels))
    }

    @Test
    fun `only a Premultiplied memory format needs the fix-up`() {
        assertTrue(RawImageCodec.isPremultiplied("R8g8b8a8Premultiplied"))
        assertFalse(RawImageCodec.isPremultiplied("R8g8b8a8"))
        assertFalse(RawImageCodec.isPremultiplied(null))
    }

    @Test
    fun `base64 with line breaks still decodes`() {
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0, 0, 0xFF.toByte()),
            RawImageCodec.decodeBase64("/wAA\n/w=="),
        )
    }
}
