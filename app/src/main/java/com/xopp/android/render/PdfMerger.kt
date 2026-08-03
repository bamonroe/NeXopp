package com.xopp.android.render

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import java.io.File

/**
 * Joins PDFs end-to-end so a document that already has a PDF background can still take another
 * import. A `.xopp` can reference exactly **one** background PDF (see `docs/architecture.md`), so
 * "append a second PDF" is only representable on disk if the two PDFs become one: [join] writes a
 * concatenation of the two and the caller re-points the document's single background reference at
 * it, renumbering the appended pages' `pageno` against the joined document.
 *
 * Merging goes through PDFBox (already a dependency for export/text), which imports each source
 * page's resources verbatim, so the appended pages rasterise and re-export exactly as before.
 */
object PdfMerger {

    /**
     * Write [base] followed by [added] into [out] and return it. [out] must differ from both inputs
     * — the merge reads them while it writes — which is why callers alternate destination names.
     */
    fun join(base: File, added: File, out: File): File {
        require(out != base && out != added) { "merge destination must differ from its sources" }
        PDFMergerUtility().apply {
            destinationFileName = out.absolutePath
            addSource(base)
            addSource(added)
            mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
        return out
    }

    /**
     * A destination for the next [join] in [dir] that isn't the currently-open [current] PDF.
     * Repeated appends ping-pong between two names, so each merge reads one file and writes the
     * other instead of clobbering the source it is still reading.
     */
    fun nextJoinedFile(dir: File, current: File?): File {
        val a = File(dir, "joined-a.pdf")
        return if (current == a) File(dir, "joined-b.pdf") else a
    }
}
