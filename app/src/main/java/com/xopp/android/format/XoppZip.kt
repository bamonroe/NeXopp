package com.xopp.android.format

import com.xopp.android.format.model.Document
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The ZIP-package `.xopp`: a single self-contained file holding the document plus its attachments as
 * archive entries, versus the legacy gzip `.xopp` which links attachments as sibling files. Desktop
 * Xournal++'s `LoadHandler` opens such an archive by looking up entries by name — `mimetype`,
 * `META-INF/version`, `content.xml` (plain XML, *not* gzipped), and each attachment named by its
 * background `filename`. Only the JDK's `java.util.zip` is used (see `docs/architecture.md`).
 *
 * ## Intentional mimetype deviation — targeting release Xournal++ on Arch Linux
 * The spec-correct mimetype is the literal `application/xournal++`, but the currently *released*
 * Xournal++ (1.3.5, the build shipped by Arch Linux and the one the owner runs) has an **inverted**
 * mimetype check in `LoadHandler`: `if (!strcmp(mimetype, "application/xournal++")) → "Mimetype
 * wrong"`. A correctly-labelled archive is therefore *rejected*. To open on that release we
 * deliberately write a mimetype that is NOT the canonical string ([MIMETYPE]); Xournal++ only ever
 * compares against the one value, so any other string passes. This is a known, temporary hack: when
 * upstream fixes the check we switch [MIMETYPE] back to `application/xournal++`.
 */
object XoppZip {

    /**
     * The mimetype entry's content. Deliberately NOT `application/xournal++` — see the class note:
     * the released Xournal++ 1.3.5 rejects the canonical string, so we write a distinct one that its
     * inverted check accepts. Kept short (well under the reader's 25-byte cap) so its buffer stays
     * NUL-terminated. Flip this back to the canonical value once upstream fixes the check.
     */
    const val MIMETYPE = "application/x-xopp-zip"

    /** The in-archive entry name for the embedded PDF background (matches `domain="attach"`'s filename). */
    const val PDF_ENTRY = "bg.pdf"

    /** The document as it was loaded from a ZIP `.xopp`, plus the PDF extracted out of the archive. */
    data class Loaded(val doc: Document, val pdf: File?)

    /**
     * Write [doc] as a ZIP-package `.xopp` on [output] (not closed). When [pdf] is non-null it is
     * embedded as the [PDF_ENTRY] archive entry; the caller is responsible for having pointed the
     * document's PDF background at that entry (`domain="attach"`, `filename="bg.pdf"` — see
     * `documentWithPdfDomain`). Xournal++ enforces neither entry order nor per-entry compression, so
     * everything is DEFLATED for size.
     */
    fun save(doc: Document, pdf: File?, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            putText(zip, "mimetype", MIMETYPE)
            putText(zip, "META-INF/version", "current=${doc.fileVersion}\nmin=1")
            putText(zip, "content.xml", Xopp.toXml(doc))
            if (pdf != null) {
                zip.putNextEntry(ZipEntry(PDF_ENTRY))
                pdf.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Read a ZIP-package `.xopp` from [input] (not closed). Parses `content.xml` into a [Document] and
     * extracts an embedded [PDF_ENTRY], if present, into [cacheDir] so the background rasterises on
     * reopen — the whole point of the single-file package. Throws if `content.xml` is missing.
     */
    fun open(input: InputStream, cacheDir: File): Loaded {
        var xml: String? = null
        var pdf: File? = null
        ZipInputStream(input).let { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "content.xml" -> xml = zip.readBytes().toString(Charsets.UTF_8)
                    PDF_ENTRY -> pdf = File(cacheDir, "background.pdf").also { out ->
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
            }
        }
        val doc = XoppReader(requireNotNull(xml) { "content.xml missing from ZIP .xopp" }).read()
        return Loaded(doc, pdf)
    }

    /** True if [magic] (the file's first two bytes) is the local-file-header signature of a ZIP archive. */
    fun isZip(b0: Int, b1: Int): Boolean = b0 == 'P'.code && b1 == 'K'.code

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
