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
 * It covers the 8-bit non-interlaced images the format layer produces and that real files carry, and
 * **returns null rather than throwing** for anything else (16-bit, interlaced, palette, truncated) so
 * one unreadable picture is skipped and reported instead of sinking a save.
 */
internal object PngDecode {

    /** Bytes of chunk framing that are not payload: the length, the type and the CRC. */
    private const val CHUNK_OVERHEAD = 12

    /** The one bit depth we decode; 16-bit images are refused rather than truncated to 8. */
    private const val SUPPORTED_BIT_DEPTH = 8

    /** Channels per pixel by PNG colour type, indexed by the type byte; -1 marks one we refuse. */
    private val CHANNELS = intArrayOf(1, -1, 3, -1, 2, -1, 4)

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
        val channels = CHANNELS.getOrElse(header.colorType) { -1 }
        val samples = unfilter(data, header.width, header.height, channels) ?: return null
        return RawImage(header.width, header.height, toRgba(samples, channels))
    }

    /** The IHDR fields a decoder actually branches on. */
    private class Header(val width: Int, val height: Int, val colorType: Int)

    /** Whether the file opens with PNG's eight-byte signature. */
    private fun hasSignature(png: ByteArray): Boolean =
        png.size > RawImageCodec.PNG_SIGNATURE.size &&
            RawImageCodec.PNG_SIGNATURE.indices.all { png[it] == RawImageCodec.PNG_SIGNATURE[it] }

    /**
     * Read IHDR, which the spec requires to be the first chunk. Null for the shapes we refuse:
     * a bit depth other than 8, a palette (colour type 3) and any interlaced image.
     */
    private fun readHeader(png: ByteArray): Header? {
        val body = firstChunk(png, "IHDR") ?: return null
        if (body.size < 13) return null
        val bitDepth = body[8].toInt() and 0xFF
        val colorType = body[9].toInt() and 0xFF
        val interlace = body[12].toInt() and 0xFF
        if (bitDepth != SUPPORTED_BIT_DEPTH || interlace != 0) return null
        if (CHANNELS.getOrElse(colorType) { -1 } < 0) return null
        val width = readInt(body, 0)
        val height = readInt(body, 4)
        if (width <= 0 || height <= 0) return null
        return Header(width, height, colorType)
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
     * Undo the per-scanline filter each row carries, leaving raw samples.
     *
     * All five filter types are implemented because libpng picks per row and any of them can appear
     * in a file the user imported; our own encoder only ever writes type 0.
     *
     * @return `width × height × channels` samples, or null when the inflated stream is the wrong size
     *   or names a filter type that doesn't exist.
     */
    private fun unfilter(data: ByteArray, width: Int, height: Int, channels: Int): ByteArray? {
        val stride = width * channels
        if (data.size < height * (stride + 1)) return null
        val out = ByteArray(height * stride)
        for (row in 0 until height) {
            val filter = data[row * (stride + 1)].toInt() and 0xFF
            val from = row * (stride + 1) + 1
            val to = row * stride
            for (i in 0 until stride) {
                val raw = data[from + i].toInt() and 0xFF
                val left = if (i >= channels) out[to + i - channels].toInt() and 0xFF else 0
                val up = if (row > 0) out[to + i - stride].toInt() and 0xFF else 0
                val upLeft =
                    if (row > 0 && i >= channels) out[to + i - stride - channels].toInt() and 0xFF else 0
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

    /** PNG's Paeth predictor: whichever neighbour the linear estimate `a + b - c` is closest to. */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Expand grey, grey+alpha or RGB samples into the RGBA8 buffer everything above works in. */
    private fun toRgba(samples: ByteArray, channels: Int): ByteArray {
        if (channels == 4) return samples
        val pixels = samples.size / channels
        val out = ByteArray(pixels * 4)
        for (i in 0 until pixels) {
            val src = i * channels
            val dst = i * 4
            when (channels) {
                1 -> {
                    out[dst] = samples[src]; out[dst + 1] = samples[src]; out[dst + 2] = samples[src]
                    out[dst + 3] = 0xFF.toByte()
                }
                2 -> {
                    out[dst] = samples[src]; out[dst + 1] = samples[src]; out[dst + 2] = samples[src]
                    out[dst + 3] = samples[src + 1]
                }
                else -> {
                    out[dst] = samples[src]; out[dst + 1] = samples[src + 1]; out[dst + 2] = samples[src + 2]
                    out[dst + 3] = 0xFF.toByte()
                }
            }
        }
        return out
    }

    /** A four-byte big-endian integer, the only integer encoding PNG uses. */
    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
