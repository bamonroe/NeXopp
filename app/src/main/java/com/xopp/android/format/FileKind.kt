package com.xopp.android.format

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * What a file we've been asked to open actually is, decided by its leading bytes rather than by
 * its name. Extensions lie on Android: SAF hands us `content://` URIs with no usable suffix, and a
 * ZIP-package `.xopp`, a gzip `.xopp` and a raw PDF all arrive through the same picker.
 */
enum class FileKind {
    /** ZIP-package `.xopp` (`PK\x03\x04`) — content.xml plus bundled assets. */
    ZIP,

    /** The classic gzip-compressed `.xopp` (`\x1f\x8b`). */
    GZIP,

    /** A raw PDF (`%PDF-`) — opened as a fresh annotatable document, one page per PDF page. */
    PDF,

    /** Uncompressed Xournal++ XML, which desktop can also write (`<?xml` / `<xournal`). */
    XML,

    /** Plain text (`.txt`, `.md`, source…) — typeset into a generated PDF-backed document. */
    TEXT,

    /**
     * A raster image (PNG, JPEG, WebP) — opened as a one-page document with the picture as the
     * page's pixmap background, which is the only shape the `.xopp` format has for it.
     */
    IMAGE,

    /** Nothing we recognise. */
    UNKNOWN,
    ;

    companion object {

        /**
         * Bytes we need to see to decide; also the mark/reset budget on the open stream. Well past
         * the few magic bytes the binary formats need, because text has no magic at all: it is
         * recognised by a whole sample decoding as printable UTF-8, and a short sample would call
         * far too much binary "text".
         */
        const val MAGIC_BYTES = 512

        /** A UTF-8 byte-order mark, which editors like to put in front of otherwise plain text. */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /**
         * Classify by leading bytes. Anything without a known signature that still decodes as
         * printable UTF-8 is [TEXT]; empty or binary input is [UNKNOWN].
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
         * Whether the leading bytes are one of the raster formats Android can decode into a page
         * background: PNG (`\x89PNG\r\n\x1a\n`), JPEG (`\xff\xd8\xff`), or WebP — a RIFF container
         * whose `WEBP` tag sits four bytes past the `RIFF` one, after the little-endian length.
         *
         * Takes the byte accessor [of] already has rather than the array, so the "past the end reads
         * as -1" rule stays in one place.
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

        /** Name suffixes that mark a text file as markdown rather than prose to typeset verbatim. */
        private val MARKDOWN_SUFFIXES = listOf(".md", ".markdown")

        /**
         * Whether the *display name* [name] says this text file is markdown. Deliberately the one
         * place we look at a suffix: markdown has no signature to sniff — a `.md` file is printable
         * UTF-8 like any other, so the content verdict stays [TEXT] and only the name distinguishes
         * "render the syntax" from "typeset it literally". SAF gives us that name at open time.
         */
        fun isMarkdownName(name: String): Boolean =
            MARKDOWN_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

        /**
         * Whether [sample] looks like human-readable UTF-8 text. Empty input is not text (there is
         * nothing to typeset). The sample is a *prefix* of the file, so a multi-byte character may
         * be cut in half at the end — trailing continuation bytes are tolerated rather than
         * treated as corruption.
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
