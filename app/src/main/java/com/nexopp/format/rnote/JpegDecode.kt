package com.nexopp.format.rnote

/**
 * A minimal baseline-sequential JPEG **decoder**, the sibling of [PngDecode] and needed for the same
 * reason: a `.xopp` `<image>` may embed a JPEG, a `bitmapimage` needs its raw pixels, and
 * `android.graphics` is a throwing stub under the JVM unit tests. Only plain Kotlin is used.
 *
 * It covers what a real embedded photo is: SOF0 baseline, 8-bit precision, greyscale or YCbCr,
 * 4:4:4 / 4:2:2 / 4:2:0 (any 1–4 sampling factors, upsampled by replication), Huffman entropy
 * coding, and restart markers. Everything else — progressive (SOF2) and every other SOFn, 12-bit
 * precision, four-component CMYK, arithmetic coding — decodes to **null, never a throw**, so an
 * unreadable picture is skipped and reported rather than sinking a save.
 */
internal object JpegDecode {

    /** Zigzag scan position → natural (row-major) position inside an 8×8 block. */
    private val NATURAL_ORDER = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63,
    )

    /** The largest RGBA buffer a frame header may claim — the same 256 MiB gate [PngDecode] uses. */
    private const val MAX_IMAGE_BYTES = 256L * 1024 * 1024

    /** `cos((2x + 1) u π / 16)` scaled by the DCT normalisation, precomputed for [idct]. */
    private val COSINES = Array(8) { u ->
        DoubleArray(8) { x ->
            Math.cos((2 * x + 1) * u * Math.PI / 16) * (if (u == 0) Math.sqrt(0.5) else 1.0) / 2
        }
    }

    /** The internal "this file is not decodable" signal; it never escapes [decode]. */
    private class Malformed : Exception()

    /**
     * Decode a JPEG into a straight-alpha (fully opaque) RGBA8 buffer.
     *
     * @param jpeg The whole file's bytes.
     * @return The pixels and their size, or null when this is not a JPEG shape we decode.
     */
    fun decode(jpeg: ByteArray): RawImage? = try {
        Decoder(jpeg).run()
    } catch (_: Malformed) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** One component of the frame: its sampling factors, its tables, and its decoded plane. */
    private class Component(val h: Int, val v: Int, val quantId: Int) {
        var dcTableId = 0
        var acTableId = 0
        var dcPredictor = 0

        /** Full sampled-resolution plane, sized to whole MCUs; filled by the scan. */
        var plane = IntArray(0)
        var planeWidth = 0
    }

    /** One canonical Huffman table in the JPEG standard's MINCODE/MAXCODE/VALPTR form. */
    private class HuffmanTable(counts: IntArray, val symbols: ByteArray) {
        val minCode = IntArray(17)
        val maxCode = IntArray(17) { -1 }
        val valPtr = IntArray(17)

        init {
            var code = 0
            var k = 0
            for (length in 1..16) {
                if (counts[length] > 0) {
                    valPtr[length] = k
                    minCode[length] = code
                    code += counts[length]
                    k += counts[length]
                    maxCode[length] = code - 1
                }
                code = code shl 1
            }
        }
    }

    /** The whole per-file decode, kept as a class so the marker handlers share state naturally. */
    private class Decoder(val jpeg: ByteArray) {
        val quantTables = arrayOfNulls<IntArray>(4)
        val dcTables = arrayOfNulls<HuffmanTable>(4)
        val acTables = arrayOfNulls<HuffmanTable>(4)
        var components = emptyList<Component>()
        var width = 0
        var height = 0
        var restartInterval = 0
        var pos = 0

        fun run(): RawImage? {
            if (jpeg.size < 4 || u8() != 0xFF || u8() != 0xD8) return null
            while (true) {
                val marker = nextMarker()
                when {
                    marker == 0xC0 -> if (!readFrame()) return null
                    // Any other SOFn — progressive, extended, lossless, arithmetic — is out of
                    // scope. C4 is DHT and C8/CC are reserved, so they are not SOFs.
                    marker in 0xC1..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                        return null
                    marker == 0xC4 -> readHuffmanTables()
                    marker == 0xDB -> readQuantTables()
                    marker == 0xDD -> readRestartInterval()
                    marker == 0xDA -> {
                        if (!readScanHeader()) return null
                        decodeScan()
                        return toImage()
                    }
                    marker == 0xD9 -> return null // EOI before any scan
                    else -> skipSegment()
                }
            }
        }

        fun u8(): Int = jpeg[pos++].toInt() and 0xFF

        fun u16(): Int = (u8() shl 8) or u8()

        /** The next marker byte, skipping padding: any number of 0xFF fill bytes precede it. */
        fun nextMarker(): Int {
            if (u8() != 0xFF) throw Malformed()
            var marker = u8()
            while (marker == 0xFF) marker = u8()
            return marker
        }

        /** Skip a length-prefixed segment we don't act on (APPn, COM, and anything unknown). */
        fun skipSegment() {
            val length = u16()
            if (length < 2 || pos + length - 2 > jpeg.size) throw Malformed()
            pos += length - 2
        }

        /** DQT: one or more tables, 8- or 16-bit entries, stored in zigzag order as read. */
        fun readQuantTables() {
            var remaining = u16() - 2
            while (remaining > 0) {
                val pqTq = u8()
                val precision = pqTq shr 4
                val id = pqTq and 0x0F
                if (precision > 1 || id > 3) throw Malformed()
                val table = IntArray(64) { if (precision == 1) u16() else u8() }
                quantTables[id] = table
                remaining -= 1 + 64 * (precision + 1)
            }
            if (remaining != 0) throw Malformed()
        }

        /** DHT: one or more tables, each 16 code counts then that many symbols. */
        fun readHuffmanTables() {
            var remaining = u16() - 2
            while (remaining > 0) {
                val tcTh = u8()
                val clazz = tcTh shr 4
                val id = tcTh and 0x0F
                if (clazz > 1 || id > 3) throw Malformed()
                val counts = IntArray(17)
                var total = 0
                for (length in 1..16) {
                    counts[length] = u8()
                    total += counts[length]
                }
                if (total > 256) throw Malformed()
                val symbols = ByteArray(total) { u8().toByte() }
                val table = HuffmanTable(counts, symbols)
                if (clazz == 0) dcTables[id] = table else acTables[id] = table
                remaining -= 1 + 16 + total
            }
            if (remaining != 0) throw Malformed()
        }

        fun readRestartInterval() {
            if (u16() != 4) throw Malformed()
            restartInterval = u16()
        }

        /** SOF0: 8-bit precision, a plausible size, and 1 (grey) or 3 (YCbCr) components. */
        fun readFrame(): Boolean {
            u16() // segment length; the component count below fixes the real extent
            if (u8() != 8) return false
            height = u16()
            width = u16()
            if (width <= 0 || height <= 0) return false
            if (width.toLong() * height * 4 > MAX_IMAGE_BYTES) return false
            val count = u8()
            if (count != 1 && count != 3) return false
            components = (0 until count).map {
                u8() // component id; position in the list is what the scan header matches on
                val hv = u8()
                val h = hv shr 4
                val v = hv and 0x0F
                val quantId = u8()
                if (h !in 1..4 || v !in 1..4 || quantId > 3) return false
                Component(h, v, quantId)
            }
            return true
        }

        /** SOS: bind each component to its entropy tables; a partial-frame scan is out of scope. */
        fun readScanHeader(): Boolean {
            u16()
            if (u8() != components.size) return false
            for (component in components) {
                u8() // component selector, in frame order for every encoder we accept
                val tables = u8()
                component.dcTableId = tables shr 4
                component.acTableId = tables and 0x0F
            }
            pos += 3 // spectral start/end and approximation — fixed 0/63/0 in baseline
            return true
        }

        /** All MCUs of the single interleaved scan, honouring restart markers between intervals. */
        fun decodeScan() {
            val hMax = components.maxOf { it.h }
            val vMax = components.maxOf { it.v }
            val mcusAcross = (width + 8 * hMax - 1) / (8 * hMax)
            val mcusDown = (height + 8 * vMax - 1) / (8 * vMax)
            for (component in components) {
                component.planeWidth = mcusAcross * component.h * 8
                component.plane = IntArray(component.planeWidth * mcusDown * component.v * 8)
            }
            val bits = BitReader()
            var mcu = 0
            for (mcuRow in 0 until mcusDown) {
                for (mcuColumn in 0 until mcusAcross) {
                    if (restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0) restart(bits)
                    for (component in components) {
                        for (blockRow in 0 until component.v) {
                            for (blockColumn in 0 until component.h) {
                                decodeBlock(
                                    bits,
                                    component,
                                    (mcuRow * component.v + blockRow) * 8,
                                    (mcuColumn * component.h + blockColumn) * 8,
                                )
                            }
                        }
                    }
                    mcu++
                }
            }
        }

        /** Consume one RSTn at a byte boundary and reset every DC predictor, per the spec. */
        fun restart(bits: BitReader) {
            bits.alignToByte()
            if (u8() != 0xFF || u8() !in 0xD0..0xD7) throw Malformed()
            for (component in components) component.dcPredictor = 0
        }

        /** One 8×8 block: Huffman-decode, dequantise, inverse-DCT, and land it on the plane. */
        fun decodeBlock(bits: BitReader, component: Component, planeRow: Int, planeColumn: Int) {
            val quant = quantTables[component.quantId] ?: throw Malformed()
            val dc = dcTables[component.dcTableId] ?: throw Malformed()
            val ac = acTables[component.acTableId] ?: throw Malformed()
            val coefficients = IntArray(64)
            val dcSize = bits.decode(dc)
            component.dcPredictor += extend(bits.receive(dcSize), dcSize)
            coefficients[0] = component.dcPredictor * quant[0]
            var k = 1
            while (k < 64) {
                val runSize = bits.decode(ac)
                val run = runSize shr 4
                val size = runSize and 0x0F
                if (size == 0) {
                    if (run != 15) break // EOB; 0xF0 is ZRL, sixteen zeroes
                    k += 16
                } else {
                    k += run
                    if (k > 63) throw Malformed()
                    coefficients[NATURAL_ORDER[k]] = extend(bits.receive(size), size) * quant[k]
                    k++
                }
            }
            idct(coefficients, component.plane, planeRow * component.planeWidth + planeColumn, component.planeWidth)
        }

        /** Upsample each plane by replication and convert to RGBA — the tail end of [run]. */
        fun toImage(): RawImage {
            val hMax = components.maxOf { it.h }
            val vMax = components.maxOf { it.v }
            val rgba = ByteArray(width * height * 4)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val at = (y * width + x) * 4
                    fun sampleOf(component: Component): Int =
                        component.plane[(y * component.v / vMax) * component.planeWidth + x * component.h / hMax]
                    if (components.size == 1) {
                        val grey = sampleOf(components[0]).toByte()
                        rgba[at] = grey; rgba[at + 1] = grey; rgba[at + 2] = grey
                    } else {
                        yccToRgb(rgba, at, sampleOf(components[0]), sampleOf(components[1]), sampleOf(components[2]))
                    }
                    rgba[at + 3] = 0xFF.toByte()
                }
            }
            return RawImage(width, height, rgba)
        }

        /** The scan's entropy-coded bit stream, unstuffing 0xFF00 and refusing to run into a marker. */
        inner class BitReader {
            private var bits = 0
            private var count = 0

            fun nextBit(): Int {
                if (count == 0) {
                    val byte = u8()
                    if (byte == 0xFF && u8() != 0x00) throw Malformed()
                    bits = byte
                    count = 8
                }
                count--
                return (bits ushr count) and 1
            }

            /** Read [n] raw (non-Huffman) bits, most significant first. */
            fun receive(n: Int): Int {
                var value = 0
                for (i in 0 until n) value = (value shl 1) or nextBit()
                return value
            }

            /** Decode one symbol via the canonical-code walk the standard specifies (F.16). */
            fun decode(table: HuffmanTable): Int {
                var code = 0
                for (length in 1..16) {
                    code = (code shl 1) or nextBit()
                    if (code <= table.maxCode[length]) {
                        return table.symbols[table.valPtr[length] + code - table.minCode[length]].toInt() and 0xFF
                    }
                }
                throw Malformed()
            }

            /** Drop the partial byte before a restart marker; markers are always byte-aligned. */
            fun alignToByte() {
                count = 0
            }
        }
    }

    /** JPEG's sign extension (F.12): an s-bit value with a 0 high bit encodes a negative number. */
    private fun extend(value: Int, size: Int): Int =
        if (size == 0 || value >= (1 shl (size - 1))) value else value - (1 shl size) + 1

    /**
     * The 8×8 inverse DCT, separably: rows first into a temporary, then columns straight onto the
     * component plane at [offset], level-shifted by +128 and clamped to 0–255 on the way out.
     */
    private fun idct(coefficients: IntArray, plane: IntArray, offset: Int, planeWidth: Int) {
        val rows = DoubleArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                var sum = 0.0
                for (u in 0 until 8) sum += COSINES[u][x] * coefficients[y * 8 + u]
                rows[y * 8 + x] = sum
            }
        }
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                var sum = 0.0
                for (v in 0 until 8) sum += COSINES[v][y] * rows[v * 8 + x]
                plane[offset + y * planeWidth + x] =
                    (Math.round(sum) + 128).coerceIn(0, 255).toInt()
            }
        }
    }

    /** CCIR 601 YCbCr to RGB — the same constants libjpeg uses — rounded and clamped per channel. */
    private fun yccToRgb(out: ByteArray, at: Int, y: Int, cb: Int, cr: Int) {
        out[at] = clamp(y + 1.402 * (cr - 128))
        out[at + 1] = clamp(y - 0.344136 * (cb - 128) - 0.714136 * (cr - 128))
        out[at + 2] = clamp(y + 1.772 * (cb - 128))
    }

    private fun clamp(value: Double): Byte = Math.round(value).coerceIn(0, 255).toByte()
}
