package com.nexopp.format

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * What a file we've been asked to open actually is, decided by its leading bytes rather than by
 * its name. Extensions lie on Android: SAF hands us `content://` URIs with no usable suffix, and a
 * ZIP-package `.xopp`, a gzip `.xopp` and a raw PDF all arrive through the same picker.
 */
enum class FileKind {
    /** A ZIP-package `.xopp` file (`PK\x03\x04` signature) containing content.xml and bundled assets. */
    ZIP,

    /** A gzip-compressed `.xopp` file (`\x1f\x8b` signature) — the classic desktop Xournal++ format. */
    GZIP,

    /** A raw PDF file (`%PDF-` signature) — opened as a fresh annotatable document with one page per PDF page. */
    PDF,

    /** Uncompressed Xournal++ XML (`<?xml` or `<xournal` root) — a valid `.xopp` without gzip wrapping. */
    XML,

    /** Plain text content — typeset into a generated PDF-backed document for annotation. */
    TEXT,

    /**
     * A raster image (PNG, JPEG, or WebP) — opened as a single-page document with the image as the
     * page's pixmap background, the only representation the `.xopp` format supports for images.
     */
    IMAGE,

    /** An unrecognised file format — cannot be opened as a Xournal++ document. */
    UNKNOWN,
    ;

    companion object {

        /**
         * The number of leading bytes examined to classify a file; also the mark/reset budget on the
         * input stream. Well past the few magic bytes binary formats need, because text has no magic
         * signature at all: it is recognised by a whole sample decoding as printable UTF-8, and a
         * short sample would falsely classify far too much binary data as "text".
         */
        const val MAGIC_BYTES = 512

        /** A UTF-8 byte-order mark (BOM), which some editors prepend to otherwise plain text files. */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /**
         * Classify a file by its leading bytes. Anything without a known signature that still decodes
         * as printable UTF-8 is [TEXT]; empty or binary input is [UNKNOWN].
         *
         * @param magic The leading bytes of the file to examine.
         * @return The detected [FileKind] based on the byte signature.
         */
        fun of(magic: ByteArray): FileKind {
            fun at(i: Int): Int = if (i < magic.size) magic[i].toInt() and 0xff else -1
            fun startsWith(s: String): Boolean = s.indices.all { at(it) == s[it].code }
            return when {
                XoppZip.isZip(at(0), at(1)) -> ZIP
                at(0) == 0x1f && at(1) == 0x8b -> GZIP
                startsWith("%PDF-") -> PDF
                startsWith("<?xml") || startsWith("<xournal") -> XML
                isImage(::at) -> IMAGE
                isPrintableUtf8(magic) -> TEXT
                else -> UNKNOWN
            }
        }

        /**
         * Whether the leading bytes match a raster format Android can decode into a page background:
         * PNG (`\x89PNG\r\n\x1a\n`), JPEG (`\xff\xd8\xff`), or WebP — a RIFF container whose `WEBP`
         * tag sits four bytes past the `RIFF` one, after the little-endian length.
         *
         * Takes the byte accessor [at] already has rather than the array, so the "past the end reads
         * as -1" rule stays in one place.
         *
         * @param at A function that returns the byte at a given offset, or -1 if out of bounds.
         * @return True if the bytes match PNG, JPEG, or WebP signatures.
         */
        private fun isImage(at: (Int) -> Int): Boolean {
            fun matches(offset: Int, signature: List<Int>) =
                signature.indices.all { at(offset + it) == signature[it] }
            fun tag(offset: Int, s: String) = matches(offset, s.map { it.code })

            val png = matches(0, listOf(0x89)) && tag(1, "PNG") &&
                matches(4, listOf(0x0d, 0x0a, 0x1a, 0x0a))
            val jpeg = matches(0, listOf(0xff, 0xd8, 0xff))
            val webp = tag(0, "RIFF") && tag(8, "WEBP")
            return png || jpeg || webp
        }

        /**
         * Name suffixes that identify a text file as markdown rather than prose to typeset verbatim.
         */
        private val MARKDOWN_SUFFIXES = listOf(".md", ".markdown")

        /**
         * Whether the [name] (display name from the file picker) indicates this text file is markdown.
         * Deliberately the one place we look at a suffix: markdown has no magic signature — a `.md`
         * file is printable UTF-8 like any other, so the content verdict stays [TEXT] and only the
         * name distinguishes "render the syntax" from "typeset it literally". SAF gives us that name
         * at open time.
         *
         * @param name The file's display name to check for markdown suffixes.
         * @return True if the name ends with `.md` or `.markdown` (case-insensitive).
         */
        fun isMarkdownName(name: String): Boolean =
            MARKDOWN_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

        /**
         * Whether [sample] looks like human-readable UTF-8 text. Empty input is not text (there is
         * nothing to typeset). The sample is a *prefix* of the file, so a multi-byte character may
         * be cut in half at the end — trailing continuation bytes are tolerated rather than treated
         * as corruption.
         *
         * @param sample A byte array prefix of the file to test.
         * @return True if the sample decodes as valid printable UTF-8 text.
         */
        internal fun isPrintableUtf8(sample: ByteArray): Boolean {
            val body = if (sample.size >= 3 && sample.copyOf(3).contentEquals(UTF8_BOM)) {
                sample.copyOfRange(3, sample.size)
            } else {
                sample
            }
            if (body.isEmpty()) return false
            var i = 0
            while (i < body.size) {
                val b = body[i].toInt() and 0xff
                val extra = when {
                    b < 0x80 -> 0
                    b in 0xC2..0xDF -> 1
                    b in 0xE0..0xEF -> 2
                    b in 0xF0..0xF4 -> 3
                    else -> return false // a stray continuation byte or an invalid lead byte
                }
                if (extra == 0) {
                    // Control characters mean binary, bar the whitespace real text is made of.
                    if (b < 0x20 && b != 0x09 && b != 0x0a && b != 0x0d) return false
                    if (b == 0x7f) return false
                } else {
                    for (k in 1..extra) {
                        // A character straddling the end of the sample proves nothing either way.
                        if (i + k >= body.size) return true
                        if ((body[i + k].toInt() and 0xc0) != 0x80) return false
                    }
                }
                i += extra + 1
            }
            return true
        }

        /**
         * Sniff [input] without consuming it. The stream is buffered and rewound, so the caller can
         * hand the very same stream to the loader the verdict picks.
         *
         * @param input A buffered input stream positioned at the start of the file to classify.
         * @return The detected [FileKind] based on leading byte inspection.
         */
        fun sniff(input: BufferedInputStream): FileKind {
            input.mark(MAGIC_BYTES)
            val magic = ByteArray(MAGIC_BYTES)
            val read = readFully(input, magic)
            input.reset()
            return of(magic.copyOf(read))
        }

        private fun readFully(input: InputStream, into: ByteArray): Int {
            var n = 0
            while (n < into.size) {
                val r = input.read(into, n, into.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }
    }
}
