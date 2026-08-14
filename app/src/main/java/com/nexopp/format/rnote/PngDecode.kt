package com.nexopp.format.rnote

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * A minimal PNG **decoder** — the inverse of [RawImageCodec.encodePng], needed because a
 * `bitmapimage` carries uncompressed pixels while an `<image>` carries an encoded file.
 *
 * Hand-rolled for the same reason the encoder is: `android.graphics.Bitmap` is a throwing stub under
 * JVM unit tests (`scripts/build.sh testDebugUnitTest`), so decoding through the platform would
 * leave the `.rnote` writer untestable in the loop we actually run. Only `java.util.zip` is used.
 *
 * It covers every **non-interlaced** PNG: bit depths 1/2/4/8/16 across grey, RGB, palette,
 * grey+alpha and RGBA, with `tRNS` transparency. That breadth is not gold-plating — the fixture
 * `<image>` this project has carried since day one is a *1-bit palette* PNG, so the narrow reading
 * would have dropped the format layer's own sample. Adam7 interlacing is the one shape left out; it
 * is rare, several times this much code, and takes the same **null, never a throw** path as a
 * truncated file, so an unreadable picture is skipped and reported rather than sinking a save.
 */
internal object PngDecode {

    /** Bytes of chunk framing that are not payload: the length, the type and the CRC. */
    private const val CHUNK_OVERHEAD = 12

    /** Samples per pixel by PNG colour type, indexed by the type byte; -1 marks one that cannot exist. */
    private val CHANNELS = intArrayOf(1, -1, 3, 1, 2, -1, 4)

    /** Colour type 3: each sample is an index into the `PLTE` chunk rather than a colour. */
    private const val PALETTE = 3

    /**
     * Decode a PNG into a straight-alpha RGBA8 buffer.
     *
     * @param png The whole file's bytes.
     * @return The pixels and their size, or null when this is not a PNG shape we decode.
     */
    fun decode(png: ByteArray): RawImage? {
        if (!hasSignature(png)) return null
        val header = readHeader(png) ?: return null
        val data = inflate(concatenateIdat(png)) ?: return null
        val samples = unfilter(data, header) ?: return null
        val rgba = expand(samples, header, firstChunk(png, "PLTE"), firstChunk(png, "tRNS"))
            ?: return null
        return RawImage(header.width, header.height, rgba)
    }

    /** The IHDR fields a decoder branches on, plus the row geometry they imply. */
    private class Header(
        val width: Int,
        val height: Int,
        val depth: Int,
        val colorType: Int,
        val channels: Int,
    ) {
        /** Bits per pixel — [channels] samples of [depth] bits each. */
        val bitsPerPixel = channels * depth

        /** One row's payload in bytes, rounded up: a sub-byte row is padded to a byte boundary. */
        val stride = (width * bitsPerPixel + 7) / 8

        /** The filter's "previous pixel" distance, in bytes, never less than one (spec §9.2). */
        val filterBpp = maxOf(1, bitsPerPixel / 8)
    }

    /** Whether the file opens with PNG's eight-byte signature. */
    private fun hasSignature(png: ByteArray): Boolean =
        png.size > RawImageCodec.PNG_SIGNATURE.size &&
            RawImageCodec.PNG_SIGNATURE.indices.all { png[it] == RawImageCodec.PNG_SIGNATURE[it] }

    /**
     * Read IHDR, which the spec requires to be the first chunk. Null for a bit depth or colour type
     * that cannot exist, and for any **interlaced** image, which is the one shape left undecoded.
     */
    private fun readHeader(png: ByteArray): Header? {
        val body = firstChunk(png, "IHDR") ?: return null
        if (body.size < 13) return null
        val depth = body[8].toInt() and 0xFF
        val colorType = body[9].toInt() and 0xFF
        if (body[12].toInt() != 0) return null
        if (depth !in intArrayOf(1, 2, 4, 8, 16)) return null
        val channels = CHANNELS.getOrElse(colorType) { -1 }
        if (channels < 0) return null
        // Only greyscale and palette images may be sub-byte; colour and alpha need 8 or 16.
        if (depth < 8 && colorType != 0 && colorType != PALETTE) return null
        val width = readInt(body, 0)
        val height = readInt(body, 4)
        if (width <= 0 || height <= 0) return null
        return Header(width, height, depth, colorType, channels)
    }

    /** The body of the first chunk of [type], or null when the file has none (or is truncated). */
    private fun firstChunk(png: ByteArray, type: String): ByteArray? =
        walkChunks(png) { chunkType, body -> if (chunkType == type) body else null }

    /** Every `IDAT` body joined end to end — the spec lets an encoder split the stream freely. */
    private fun concatenateIdat(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        walkChunks<Unit>(png) { type, body ->
            if (type == "IDAT") out.write(body)
            null
        }
        return out.toByteArray()
    }

    /**
     * Walk the chunk list after the signature, handing each type and body to [visit]; the first
     * non-null result stops the walk and is returned. A length that runs past the end of the buffer
     * ends the walk, so a truncated file decodes to null instead of throwing.
     */
    private fun <T> walkChunks(png: ByteArray, visit: (String, ByteArray) -> T?): T? {
        var pos = RawImageCodec.PNG_SIGNATURE.size
        while (pos + CHUNK_OVERHEAD <= png.size) {
            val length = readInt(png, pos)
            if (length < 0 || pos + CHUNK_OVERHEAD + length > png.size) return null
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val body = png.copyOfRange(pos + 8, pos + 8 + length)
            visit(type, body)?.let { return it }
            if (type == "IEND") return null
            pos += CHUNK_OVERHEAD + length
        }
        return null
    }

    /** zlib-inflate the joined `IDAT` stream, or null when it is corrupt. */
    private fun inflate(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } catch (_: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    /**
     * Undo the per-scanline filter each row carries, leaving raw packed samples.
     *
     * All five filter types are implemented because libpng picks per row and any of them can appear
     * in a file the user imported; our own encoder only ever writes type 0.
     *
     * @return `height × stride` bytes, or null when the inflated stream is short or names a filter
     *   type that doesn't exist.
     */
    private fun unfilter(data: ByteArray, header: Header): ByteArray? {
        val stride = header.stride
        val bpp = header.filterBpp
        if (data.size < header.height * (stride + 1)) return null
        val out = ByteArray(header.height * stride)
        for (row in 0 until header.height) {
            val filter = data[row * (stride + 1)].toInt() and 0xFF
            val from = row * (stride + 1) + 1
            val to = row * stride
            for (i in 0 until stride) {
                val raw = data[from + i].toInt() and 0xFF
                val left = if (i >= bpp) out[to + i - bpp].toInt() and 0xFF else 0
                val up = if (row > 0) out[to + i - stride].toInt() and 0xFF else 0
                val upLeft =
                    if (row > 0 && i >= bpp) out[to + i - stride - bpp].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> return null
                }
                out[to + i] = ((raw + predicted) and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * Turn unfiltered samples into the RGBA8 buffer everything above works in: unpack the bit depth,
     * look a palette index up, and fill in the alpha the colour type doesn't carry.
     *
     * @param palette The `PLTE` body (RGB triples), required for a palette image.
     * @param transparency The `tRNS` body: per-index alpha for a palette image, and ignored
     *   otherwise — the single transparent colour it means for grey/RGB is a rarity we treat as
     *   opaque rather than hunting for an exact sample match.
     */
    private fun expand(
        samples: ByteArray,
        header: Header,
        palette: ByteArray?,
        transparency: ByteArray?,
    ): ByteArray? {
        if (header.colorType == PALETTE && palette == null) return null
        val out = ByteArray(header.width * header.height * 4)
        for (row in 0 until header.height) {
            val offset = row * header.stride
            for (x in 0 until header.width) {
                val first = x * header.channels
                val at = (row * header.width + x) * 4
                val ok = when (header.colorType) {
                    PALETTE -> palettePixel(out, at, sample(samples, offset, first, header.depth), palette!!, transparency)
                    else -> directPixel(out, at, samples, offset, first, header)
                }
                if (!ok) return null
            }
        }
        return out
    }

    /** One palette pixel: the index's `PLTE` triple, with its `tRNS` alpha when the file has one. */
    private fun palettePixel(
        out: ByteArray,
        at: Int,
        index: Int,
        palette: ByteArray,
        transparency: ByteArray?,
    ): Boolean {
        if (index * 3 + 2 >= palette.size) return false
        out[at] = palette[index * 3]
        out[at + 1] = palette[index * 3 + 1]
        out[at + 2] = palette[index * 3 + 2]
        // tRNS is allowed to cover only a prefix of the palette; the rest is opaque.
        out[at + 3] = transparency?.getOrNull(index) ?: 0xFF.toByte()
        return true
    }

    /** One grey / grey+alpha / RGB / RGBA pixel, each sample scaled up to eight bits. */
    private fun directPixel(
        out: ByteArray,
        at: Int,
        samples: ByteArray,
        offset: Int,
        first: Int,
        header: Header,
    ): Boolean {
        fun channel(i: Int): Byte =
            scale(sample(samples, offset, first + i, header.depth), header.depth).toByte()
        when (header.channels) {
            1, 2 -> {
                val grey = channel(0)
                out[at] = grey; out[at + 1] = grey; out[at + 2] = grey
                out[at + 3] = if (header.channels == 2) channel(1) else 0xFF.toByte()
            }
            else -> {
                out[at] = channel(0); out[at + 1] = channel(1); out[at + 2] = channel(2)
                out[at + 3] = if (header.channels == 4) channel(3) else 0xFF.toByte()
            }
        }
        return true
    }

    /**
     * One sample out of a packed row. Sub-byte samples are stored **most significant first**; a
     * 16-bit sample keeps only its high byte, since everything above this is 8-bit RGBA.
     */
    private fun sample(row: ByteArray, offset: Int, index: Int, depth: Int): Int = when (depth) {
        16 -> row[offset + index * 2].toInt() and 0xFF
        8 -> row[offset + index].toInt() and 0xFF
        else -> {
            val perByte = 8 / depth
            val byte = row[offset + index / perByte].toInt() and 0xFF
            (byte ushr (8 - depth * (index % perByte + 1))) and ((1 shl depth) - 1)
        }
    }

    /** Spread a sample across the full 0–255 range: 1-bit `1` is white, not `0x01`. */
    private fun scale(value: Int, depth: Int): Int =
        if (depth >= 8) value else value * 255 / ((1 shl depth) - 1)

    /** PNG's Paeth predictor: whichever neighbour the linear estimate `a + b - c` is closest to. */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** A four-byte big-endian integer, the only integer encoding PNG uses. */
    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
