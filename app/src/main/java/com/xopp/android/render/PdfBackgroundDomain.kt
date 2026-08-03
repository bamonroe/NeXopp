package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document

/** The `attach` domain: the PDF travels bundled beside the .xopp so the file is self-contained. */
const val ATTACH_DOMAIN = "attach"

/** The `absolute` domain: the .xopp links to the PDF by path/URI, left where it lives on disk. */
const val ABSOLUTE_DOMAIN = "absolute"

/**
 * The `filename` desktop Xournal++ records for an attached PDF background. The actual bundled file
 * sits next to the document as `<xoppname>.bg.pdf`; on load desktop resolves it as
 * `<xoppPath> + "." + filename` (see `docs/architecture.md`), so a `document.xopp` and its
 * `document.xopp.bg.pdf` sibling pair up.
 */
const val ATTACH_PDF_FILENAME = "bg.pdf"

/**
 * Rewrite how the document's PDF background is referenced on disk, per desktop Xournal++'s
 * `domain` convention. Only the first PDF-backed page carries the reference (the import
 * convention); later PDF pages keep their bare `pageno` and are untouched.
 *
 * - [ABSOLUTE_DOMAIN] — leave the reference (a path/URI) in place: the .xopp links to the PDF.
 * - [ATTACH_DOMAIN] — point at the bundled sibling: `domain="attach"`, `filename="bg.pdf"`.
 *
 * Returns a copy; the working document passed in is left unchanged.
 */
fun documentWithPdfDomain(doc: Document, domain: String): Document {
    if (domain != ATTACH_DOMAIN) return doc
    return documentWithPdfReference(doc, ATTACH_PDF_FILENAME, ATTACH_DOMAIN)
}

/**
 * Re-point the document's single PDF background reference at [filename] under [domain], leaving
 * every page's `pageno` alone. Used when appending a second PDF: the two PDFs are merged into one
 * joined file ([PdfMerger]) and the document's one reference has to name the joined file instead of
 * the original. Only the *first* PDF-backed page that carries a reference is rewritten — the rest
 * reference the same PDF by bare `pageno`. Returns a copy; the input is left unchanged.
 */
fun documentWithPdfReference(doc: Document, filename: String, domain: String): Document {
    var rewritten = false
    val pages = doc.pages.map { page ->
        val bg = page.background
        if (!rewritten && bg is Background.Pdf && bg.filename != null) {
            rewritten = true
            page.copy(background = bg.copy(domain = domain, filename = filename))
        } else {
            page
        }
    }
    return doc.copy(pages = pages)
}
