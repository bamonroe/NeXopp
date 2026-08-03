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

    /** Nothing we recognise. */
    UNKNOWN,
    ;

    companion object {

        /** Bytes we need to see to decide; also the mark/reset budget on the open stream. */
        const val MAGIC_BYTES = 8

        /** Classify by leading bytes. Short or empty input is [UNKNOWN]. */
        fun of(magic: ByteArray): FileKind {
            fun at(i: Int): Int = if (i < magic.size) magic[i].toInt() and 0xff else -1
            fun startsWith(s: String): Boolean = s.indices.all { at(it) == s[it].code }
            return when {
                XoppZip.isZip(at(0), at(1)) -> ZIP
                at(0) == 0x1f && at(1) == 0x8b -> GZIP
                startsWith("%PDF-") -> PDF
                startsWith("<?xml") || startsWith("<xournal") -> XML
                else -> UNKNOWN
            }
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
