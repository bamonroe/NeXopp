package com.nexopp.format.rnote

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw-pixels ↔ PNG codec and the premultiplied-alpha fix-ups a `bitmapimage` goes through, in
 * both directions: the encoder an import needs and the decoder an export needs.
 *
 * Some assertions still read the encoder's *bytes* back (chunk names, inflated scanline size) rather
 * than going through the decoder, so a matching pair of bugs in the two halves cannot cancel out.
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

    @Test
    fun `encode then decode returns the pixels unchanged`() {
        val pixels = ByteArray(3 * 2 * 4) { (it * 11 % 256).toByte() }
        val image = RawImageCodec.decodePng(RawImageCodec.encodePng(pixels, 3, 2))!!
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertArrayEquals(pixels, image.rgba)
    }

    @Test
    fun `every scanline filter type unfilters back to the same pixels`() {
        // Our own encoder only writes filter 0, but libpng picks per row, so a user's imported
        // picture can carry any of the five.
        val pixels = ByteArray(4 * 3 * 4) { (it * 23 % 256).toByte() }
        for (filter in 0..4) {
            val image = RawImageCodec.decodePng(pngWithFilter(pixels, 4, 3, filter))
            assertArrayEquals("filter $filter", pixels, image?.rgba)
        }
    }

    @Test
    fun `grey and rgb images expand to rgba`() {
        val grey = RawImageCodec.decodePng(pngWithSamples(byteArrayOf(0x40, 0x80.toByte()), 2, 1, 0))!!
        assertArrayEquals(
            byteArrayOf(0x40, 0x40, 0x40, 0xFF.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0xFF.toByte()),
            grey.rgba,
        )
        val rgb = RawImageCodec.decodePng(pngWithSamples(byteArrayOf(1, 2, 3), 1, 1, 2))!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 0xFF.toByte()), rgb.rgba)
    }

    @Test
    fun `a palette image resolves its indices and tRNS alpha`() {
        // The project's own text-image fixture embeds a 1-bit palette PNG, so this is the shape a
        // real `<image>` actually arrives in — not an exotic one.
        val palette = byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte())
        // One row of two 1-bit indices, MSB first: index 0 then index 1, padded to a byte.
        val png = pngWithSamples(byteArrayOf(0b0100_0000), 2, 1, colorType = 3, depth = 1) {
            mapOf("PLTE" to palette, "tRNS" to byteArrayOf(0x80.toByte()))
        }
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0, 0, 0x80.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte()),
            RawImageCodec.decodePng(png)!!.rgba,
        )
    }

    @Test
    fun `sub-byte and 16-bit greyscale scale onto the full range`() {
        // 1-bit: 0 is black and 1 is white, not 0x01.
        val bilevel = RawImageCodec.decodePng(
            pngWithSamples(byteArrayOf(0b0100_0000), 2, 1, colorType = 0, depth = 1),
        )!!
        assertEquals(0, bilevel.rgba[0].toInt() and 0xFF)
        assertEquals(0xFF, bilevel.rgba[4].toInt() and 0xFF)

        // 16-bit keeps the high byte; everything above this layer is 8-bit RGBA.
        val deep = RawImageCodec.decodePng(
            pngWithSamples(byteArrayOf(0x40, 0x99.toByte()), 1, 1, colorType = 0, depth = 16),
        )!!
        assertEquals(0x40, deep.rgba[0].toInt() and 0xFF)
        assertEquals(0xFF, deep.rgba[3].toInt() and 0xFF)
    }

    @Test
    fun `an interlaced png decodes to the same pixels as its plain twin`() {
        // Both fixtures were written by ImageMagick, not by our encoder, so the seven-pass geometry
        // is checked against a real PNG writer. See the fixtures' README for how they were made.
        val plain = RawImageCodec.decodePng(fixture("adam7-plain.png"))!!
        val interlaced = RawImageCodec.decodePng(fixture("adam7-interlaced.png"))!!
        assertEquals(0, interlaceMethod(fixture("adam7-plain.png")))
        assertEquals(1, interlaceMethod(fixture("adam7-interlaced.png")))
        assertEquals(13, interlaced.width)
        assertEquals(9, interlaced.height)
        assertArrayEquals(plain.rgba, interlaced.rgba)
        // And they are the picture the README says, not merely equal to each other: every pixel is a
        // function of its position, so a scatter that shifted a pass would show up here too.
        for (y in 0 until 9) {
            for (x in 0 until 13) {
                val at = (y * 13 + x) * 4
                val expected = intArrayOf(x * 17, y * 23, x * 7 + y * 13, 255 - (x * 3 + y * 5) % 128)
                for (channel in 0 until 4) {
                    assertEquals(
                        "pixel $x,$y channel $channel",
                        expected[channel] % 256,
                        interlaced.rgba[at + channel].toInt() and 0xFF,
                    )
                }
            }
        }
    }

    @Test
    fun `a sub-byte interlaced image lands each pass in the right pixels`() {
        // ImageMagick will not write a 1-bit Adam7 PNG, so this one is built here — the bit-packed
        // scatter is the half of interlacing the byte-aligned fixtures cannot reach.
        val lit = { x: Int, y: Int -> (x * 3 + y * 5) % 2 }
        val image = RawImageCodec.decodePng(interlacedBilevel(11, 7, lit))!!
        for (y in 0 until 7) {
            for (x in 0 until 11) {
                val grey = image.rgba[(y * 11 + x) * 4].toInt() and 0xFF
                assertEquals("pixel $x,$y", if (lit(x, y) == 1) 0xFF else 0, grey)
            }
        }
    }

    @Test
    fun `anything we cannot decode is null rather than an exception`() {
        val png = RawImageCodec.encodePng(redSquare(), 2, 2)
        val interlaced = fixture("adam7-interlaced.png")
        // A JPEG, a truncated file, an empty buffer, a truncated interlaced file, an interlaced
        // header over a stream that runs out mid-pass, an undefined interlace method, and a palette
        // with no PLTE. The mid-pass case flips the plain twin's IHDR byte: seven passes need 487
        // bytes of scanline where the plain layout wrote 477, so the last pass has nothing to read.
        assertNull(RawImageCodec.decodeToRaw(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(RawImageCodec.decodePng(png.copyOfRange(0, png.size / 2)))
        assertNull(RawImageCodec.decodePng(ByteArray(0)))
        assertNull(RawImageCodec.decodePng(interlaced.copyOfRange(0, interlaced.size - 40)))
        assertNull(
            RawImageCodec.decodePng(
                fixture("adam7-plain.png").also { it[RawImageCodec.PNG_SIGNATURE.size + 8 + 12] = 1 },
            ),
        )
        assertNull(RawImageCodec.decodePng(png.also { it[RawImageCodec.PNG_SIGNATURE.size + 8 + 12] = 2 }))
        assertNull(RawImageCodec.decodePng(pngWithSamples(byteArrayOf(0), 1, 1, 3)))
    }

    @Test
    fun `a header claiming an absurd size is null before anything is allocated`() {
        // 0x40000000 squared overflows height * stride; the gate must catch it in readHeader
        // rather than let a NegativeArraySizeException or OutOfMemoryError escape decode().
        val huge = assemble(ByteArray(0), 0x40000000, 0x40000000, colorType = 6)
        assertNull(RawImageCodec.decodePng(huge))
    }

    /** A checked-in PNG from `app/src/test/resources/fixtures/png/`. */
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/png/$name").use {
            it?.readBytes() ?: error("missing fixture fixtures/png/$name")
        }

    /** The `IHDR` interlace method byte: 0 for none, 1 for Adam7. */
    private fun interlaceMethod(png: ByteArray): Int = chunkBody(png, "IHDR")[12].toInt()

    /**
     * A 1-bit greyscale Adam7 PNG of [lit], written pass by pass the way the spec lays them out:
     * seven independently filtered sub-images, concatenated with no separator, each row padded to a
     * byte. A pass with no rows or columns contributes nothing at all.
     */
    private fun interlacedBilevel(width: Int, height: Int, lit: (Int, Int) -> Int): ByteArray {
        val columnStart = intArrayOf(0, 4, 0, 2, 0, 1, 0)
        val rowStart = intArrayOf(0, 0, 4, 0, 2, 0, 1)
        val columnStep = intArrayOf(8, 8, 4, 4, 2, 2, 1)
        val rowStep = intArrayOf(8, 8, 8, 4, 4, 2, 2)
        val raw = ByteArrayOutputStream()
        for (pass in 0 until 7) {
            val columns = extent(width, columnStart[pass], columnStep[pass])
            val rows = extent(height, rowStart[pass], rowStep[pass])
            if (columns == 0 || rows == 0) continue
            for (y in 0 until rows) {
                val line = ByteArray((columns + 7) / 8)
                for (x in 0 until columns) {
                    val on = lit(columnStart[pass] + x * columnStep[pass], rowStart[pass] + y * rowStep[pass])
                    if (on == 1) line[x / 8] = (line[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                }
                raw.write(0) // filter type 0
                raw.write(line)
            }
        }
        return assemble(raw.toByteArray(), width, height, colorType = 0, depth = 1, interlace = 1)
    }

    /** How many pixels one Adam7 pass covers along a [total]-long side. */
    private fun extent(total: Int, start: Int, step: Int): Int =
        if (start >= total) 0 else (total - start + step - 1) / step

    @Test
    fun `premultiplying is the inverse of unpremultiplying within a rounding step`() {
        val straight = byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x40, 0x80.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte())
        val back = RawImageCodec.unpremultiply(RawImageCodec.premultiply(straight))
        for (i in straight.indices) {
            val delta = Math.abs((straight[i].toInt() and 0xFF) - (back[i].toInt() and 0xFF))
            assertTrue("byte $i drifted by $delta", delta <= 1)
        }
        // An opaque buffer is untouched in both directions.
        val opaque = byteArrayOf(0x40, 0x50, 0x60, 0xFF.toByte())
        assertArrayEquals(opaque, RawImageCodec.premultiply(opaque))
    }

    /** A PNG whose every row uses [filter], so the decoder's unfiltering is what is under test. */
    private fun pngWithFilter(rgba: ByteArray, width: Int, height: Int, filter: Int): ByteArray {
        val stride = width * 4
        val raw = ByteArray(height * (stride + 1))
        for (row in 0 until height) {
            raw[row * (stride + 1)] = filter.toByte()
            for (i in 0 until stride) {
                val here = rgba[row * stride + i].toInt() and 0xFF
                val left = if (i >= 4) rgba[row * stride + i - 4].toInt() and 0xFF else 0
                val up = if (row > 0) rgba[(row - 1) * stride + i].toInt() and 0xFF else 0
                val upLeft = if (row > 0 && i >= 4) rgba[(row - 1) * stride + i - 4].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> 0
                }
                raw[row * (stride + 1) + 1 + i] = ((here - predicted) and 0xFF).toByte()
            }
        }
        return assemble(raw, width, height, colorType = 6)
    }

    /**
     * A PNG of already-unfiltered [samples] under [colorType] and [depth], for the
     * channel-expansion, bit-depth and palette cases. [extra] supplies any chunks the colour type
     * needs (`PLTE`, `tRNS`), written between IHDR and IDAT as the spec requires.
     */
    private fun pngWithSamples(
        samples: ByteArray,
        width: Int,
        height: Int,
        colorType: Int,
        depth: Int = 8,
        extra: () -> Map<String, ByteArray> = { emptyMap() },
    ): ByteArray {
        val channels = intArrayOf(1, 0, 3, 1, 2, 0, 4)[colorType]
        val stride = (width * channels * depth + 7) / 8
        val raw = ByteArray(height * (stride + 1))
        for (row in 0 until height) {
            samples.copyInto(raw, row * (stride + 1) + 1, row * stride, (row + 1) * stride)
        }
        return assemble(raw, width, height, colorType, depth, extra())
    }

    /** Wrap already-filtered scanlines as a PNG file: signature, IHDR, any extras, one IDAT, IEND. */
    private fun assemble(
        raw: ByteArray,
        width: Int,
        height: Int,
        colorType: Int,
        depth: Int = 8,
        extra: Map<String, ByteArray> = emptyMap(),
        interlace: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(RawImageCodec.PNG_SIGNATURE)
        val ihdr = ByteArrayOutputStream()
        for (value in listOf(width, height)) {
            for (shift in listOf(24, 16, 8, 0)) ihdr.write((value ushr shift) and 0xFF)
        }
        ihdr.write(depth)
        ihdr.write(colorType)
        ihdr.write(0) // compression method
        ihdr.write(0) // filter method
        ihdr.write(interlace)
        writeChunk(out, "IHDR", ihdr.toByteArray())
        for ((type, body) in extra) writeChunk(out, type, body)
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val idat = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) idat.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        writeChunk(out, "IDAT", idat.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** One PNG chunk: length, type, body, CRC32 over type and body. */
    private fun writeChunk(out: ByteArrayOutputStream, type: String, body: ByteArray) {
        for (shift in listOf(24, 16, 8, 0)) out.write((body.size ushr shift) and 0xFF)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(body)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(body)
        for (shift in listOf(24, 16, 8, 0)) out.write((crc.value.toInt() ushr shift) and 0xFF)
    }

    /** PNG's Paeth predictor, duplicated here so the test does not lean on the code under test. */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
