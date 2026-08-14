package com.nexopp.format.rnote

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * An uncompressed picture as both sides of the `bitmapimage` mapping hold one: straight-alpha RGBA8,
 * row-major, four bytes per pixel.
 *
 * @property width The width in pixels.
 * @property height The height in pixels.
 * @property rgba `width × height × 4` bytes.
 */
class RawImage(val width: Int, val height: Int, val rgba: ByteArray)

/**
 * The raw-pixels ↔ PNG step a `bitmapimage` needs: Rnote stores an image as base64 of an
 * **uncompressed** pixel buffer (`memory_format`, e.g. `R8g8b8a8Premultiplied`), while
 * [com.nexopp.format.model.ImageElement] carries the PNG bytes `.xopp` embeds. See
 * `docs/architecture.md`, "The stroke & pen mapping (measured against the fixture twins)".
 *
 * Everything here is plain JDK (`Base64`, `Deflater`, `CRC32`, `Inflater`) rather than
 * `android.graphics` so the conversion is unit-testable on the JVM, where `Bitmap` is a stub that
 * throws.
 */
object RawImageCodec {

    /** The eight bytes every PNG starts with. */
    val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Bytes per pixel in the one buffer layout we read and the one we write: RGBA8. */
    private const val BYTES_PER_PIXEL = 4

    /**
     * Decode a base64 payload as written into a `.rnote`.
     *
     * Uses the MIME decoder because the encoder upstream wraps long payloads across lines, and a
     * strict decoder would reject the line breaks.
     *
     * @param text The base64 text, with or without line breaks.
     * @return The decoded bytes.
     */
    fun decodeBase64(text: String): ByteArray = Base64.getMimeDecoder().decode(text)

    /**
     * Undo premultiplied alpha in an RGBA8 buffer, returning straight-alpha pixels.
     *
     * Only call this when `memory_format` ends in `Premultiplied` — [isPremultiplied] answers
     * that. A fully transparent pixel carries no recoverable colour, so it is left as it is.
     *
     * @param rgba An RGBA8 buffer, four bytes per pixel.
     * @return A new buffer with straight (un-premultiplied) alpha.
     */
    fun unpremultiply(rgba: ByteArray): ByteArray {
        val out = rgba.copyOf()
        var i = 0
        while (i + BYTES_PER_PIXEL <= out.size) {
            val alpha = out[i + 3].toInt() and 0xFF
            if (alpha != 0 && alpha != 255) {
                for (channel in 0 until 3) {
                    val value = (out[i + channel].toInt() and 0xFF) * 255.0 / alpha
                    out[i + channel] = value.roundToInt().coerceIn(0, 255).toByte()
                }
            }
            i += BYTES_PER_PIXEL
        }
        return out
    }

    /**
     * Redo premultiplied alpha in an RGBA8 buffer — the inverse of [unpremultiply], and what a
     * buffer needs before it is written under the `R8g8b8a8Premultiplied` memory format.
     *
     * @param rgba An RGBA8 buffer, four bytes per pixel, straight alpha.
     * @return A new buffer with its colour channels scaled by their alpha.
     */
    fun premultiply(rgba: ByteArray): ByteArray {
        val out = rgba.copyOf()
        var i = 0
        while (i + BYTES_PER_PIXEL <= out.size) {
            val alpha = out[i + 3].toInt() and 0xFF
            if (alpha != 255) {
                for (channel in 0 until 3) {
                    val value = (out[i + channel].toInt() and 0xFF) * alpha / 255.0
                    out[i + channel] = value.roundToInt().coerceIn(0, 255).toByte()
                }
            }
            i += BYTES_PER_PIXEL
        }
        return out
    }

    /**
     * Whether a Rnote `memory_format` name describes premultiplied alpha.
     *
     * @param memoryFormat The `image.memory_format` string, or null when the file omits it.
     * @return True when the buffer's colour channels are scaled by their alpha.
     */
    fun isPremultiplied(memoryFormat: String?): Boolean =
        memoryFormat?.endsWith("Premultiplied") == true

    /**
     * Encode an RGBA8 buffer as a PNG: signature, `IHDR`, one `IDAT`, `IEND`.
     *
     * The scanlines use filter type 0 (none) — deflate alone is enough for the small images a
     * `.rnote` carries, and it keeps the writer small enough to read in one sitting.
     *
     * @param rgba The pixels, row-major, four bytes per pixel; straight alpha.
     * @param width The image width in pixels.
     * @param height The image height in pixels.
     * @return The complete PNG file bytes.
     * @throws IllegalArgumentException If the buffer is not `width × height × 4` bytes.
     */
    fun encodePng(rgba: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "image is $width x $height" }
        require(rgba.size == width * height * BYTES_PER_PIXEL) {
            "image data is ${rgba.size} bytes, expected ${width * height * BYTES_PER_PIXEL}"
        }
        val png = ByteArrayOutputStream()
        png.write(PNG_SIGNATURE)
        writeChunk(png, "IHDR", ihdr(width, height))
        writeChunk(png, "IDAT", deflate(scanlines(rgba, width, height)))
        writeChunk(png, "IEND", ByteArray(0))
        return png.toByteArray()
    }

    /**
     * Decode a PNG back into straight-alpha RGBA8 pixels — the inverse of [encodePng], delegated to
     * [PngDecode].
     *
     * @param png The whole file's bytes.
     * @return The pixels, or null when this is not a PNG shape we decode (16-bit, interlaced,
     *   palette, truncated).
     */
    fun decodePng(png: ByteArray): RawImage? = PngDecode.decode(png)

    /**
     * Decode whatever an `<image>` element carries into raw pixels, ready to be written as a
     * `bitmapimage`.
     *
     * **PNG only.** A `.xopp` may embed a JPEG, and there is deliberately no JPEG decoder here: a
     * baseline decoder is far more code than the rest of this file, and `android.graphics` is off
     * limits for the reason in the class doc. A JPEG therefore returns null and the writer skips and
     * reports that image rather than shipping a wrong one — see the Images row of the feature-gap
     * matrix in `docs/architecture.md`.
     *
     * @param data The encoded bytes from the element.
     * @return The pixels, or null when nothing here can decode them.
     */
    fun decodeToRaw(data: ByteArray): RawImage? = decodePng(data)

    /** The 13-byte `IHDR` body: size, 8-bit depth, colour type 6 (RGBA), no interlace. */
    private fun ihdr(width: Int, height: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeInt(body, width)
        writeInt(body, height)
        body.write(8)
        body.write(6)
        body.write(0)
        body.write(0)
        body.write(0)
        return body.toByteArray()
    }

    /** The raw PNG image data: every row prefixed with its filter byte (0 = none). */
    private fun scanlines(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val stride = width * BYTES_PER_PIXEL
        val out = ByteArray(height * (stride + 1))
        for (row in 0 until height) {
            val target = row * (stride + 1)
            out[target] = 0
            rgba.copyInto(out, target + 1, row * stride, (row + 1) * stride)
        }
        return out
    }

    /** zlib-compress a buffer, which is exactly what a PNG `IDAT` payload is. */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** One PNG chunk: length, four-byte type, body, then a CRC32 over type and body. */
    private fun writeChunk(out: ByteArrayOutputStream, type: String, body: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(out, body.size)
        out.write(typeBytes)
        out.write(body)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(body)
        writeInt(out, crc.value.toInt())
    }

    /** PNG integers are four bytes, big-endian. */
    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
