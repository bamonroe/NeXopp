package com.nexopp.io

import com.nexopp.format.FileKind
import com.nexopp.render.TextFlavor
import com.nexopp.render.TextPdfGenerator
import java.io.File
import java.security.MessageDigest

/**
 * Turns a plain-text file into the background PDF a text import is annotated over.
 *
 * A `.xopp` has no way to represent "a text file" — the only thing that round-trips is a PDF
 * background — so opening a `.txt` means typesetting it into one first (see [TextPdfGenerator]) and
 * then treating it exactly like any other PDF import. That is the whole trick: nothing downstream
 * (backgrounds, page building, text selection, saving) needs a text-specific path.
 *
 * Typesetting is not cheap, so the result is kept in a [PdfStore] under a **content key** — a digest
 * of the source text plus the file's name, prefixed by the [TextFlavor] it was typeset as so the
 * same bytes opened as `notes.txt` and as `notes.md` get their own PDFs. Reopening the same file while that entry survives reuses
 * the PDF; the store's liveness pruning drops it once no tab refers to it, and the next open simply
 * regenerates. Kept out of [DocumentIo] so it stays unit-testable without a `ContentResolver`.
 */
class TextImport(private val store: PdfStore, private val generator: TextPdfGenerator) {

    /**
     * The generated PDF for the text in [staged], named [name] for the cache key (the staged copy's
     * own file name is per-open scratch, so it would never hit).
     */
    fun pdfFor(staged: File, name: String, maxBytes: Long = Long.MAX_VALUE): File {
        // Checked on the file's own length, before a byte is read: typesetting holds the whole text
        // and its whole laid-out page list in memory, so a several-hundred-megabyte log has to be
        // refused rather than attempted (the user raises the cap in Settings → Storage if they mean it).
        val size = staged.length()
        if (size > maxBytes) {
            throw TextTooLargeException(
                "Text file is ${mb(size)} MB, over the ${mb(maxBytes)} MB import limit " +
                    "(raise it in Settings → Storage)"
            )
        }
        val flavor = flavorOf(name)
        return store.cached(key(staged, name, flavor)) { out ->
            out.outputStream().use { generator.generate(staged, it, flavor = flavor) }
        }
    }

    /**
     * Which flavour [name] asks for. Sniffing says only "this is text"; the display name is the
     * only thing that can say "and it is markdown", so the suffix decides (see
     * [FileKind.isMarkdownName]).
     */
    private fun flavorOf(name: String): TextFlavor =
        if (FileKind.isMarkdownName(name)) TextFlavor.MARKDOWN else TextFlavor.PLAIN

    /** A size in bytes as whole MB, for the message above (rounded up, so nothing reads as "0 MB"). */
    private fun mb(bytes: Long): Long = (bytes + BYTES_PER_MB - 1) / BYTES_PER_MB

    /**
     * The content key: the display name then the file's bytes, streamed through the digest a buffer
     * at a time so a large import never becomes a large `String`.
     */
    private fun key(staged: File, name: String, flavor: TextFlavor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$name\u0000".toByteArray(Charsets.UTF_8))
        staged.inputStream().buffered().use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return flavor.cachePrefix + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Bytes in a mebibyte, for the [mb] helper in error messages. */
        const val BYTES_PER_MB = 1024L * 1024L
        /** Streaming buffer for the SHA-256 digest — large enough to amortise reads, small enough to stay off the heap. */
        const val DIGEST_BUFFER_BYTES = 64 * 1024
    }
}

/** A text file too big to typeset under the current cap. Its message is shown to the user verbatim. */
class TextTooLargeException(message: String) : Exception(message)
