package com.xopp.android.render

/**
 * How an imported PDF joins the document that's already open.
 *
 * A `.xopp` can reference exactly **one** background PDF (the zipped package embeds a single
 * `bg.pdf`), so [APPEND] is only offered while the open document has no PDF-backed pages of its own —
 * otherwise the result couldn't round-trip to desktop Xournal++.
 */
enum class ImportPdfMode {
    /** Discard the current document; the PDF's pages become the whole document. */
    REPLACE,

    /** Keep the current pages and add the PDF's pages after them. */
    APPEND,
}
