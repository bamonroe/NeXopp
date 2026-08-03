package com.xopp.android.render

/**
 * How an imported PDF joins the document that's already open.
 *
 * A `.xopp` can reference exactly **one** background PDF (the zipped package embeds a single
 * `bg.pdf`), so [APPEND] onto a document that already has one doesn't add a second reference: the two
 * PDFs are merged into a single joined PDF ([PdfMerger]) that becomes the document's one background
 * source, with the appended pages' `pageno` renumbered against it. That keeps the result
 * round-trippable to desktop Xournal++.
 */
enum class ImportPdfMode {
    /** Discard the current document; the PDF's pages become the whole document. */
    REPLACE,

    /** Keep the current pages and add the PDF's pages after them. */
    APPEND,
}
