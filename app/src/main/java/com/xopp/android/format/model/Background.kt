package com.xopp.android.format.model

/**
 * A page background. Mirrors `<background type=…>`; the three concrete types map to the
 * `solid` / `pixmap` / `pdf` values. Styles and domains are kept as raw strings so unknown
 * desktop values round-trip unchanged.
 */
sealed interface Background {
    /** `type="solid"`: a ruled/graph/plain sheet in a single colour. */
    data class Solid(val color: Int, val style: String) : Background

    /** `type="pixmap"`: an image behind the page. */
    data class Pixmap(val domain: String, val filename: String) : Background

    /** `type="pdf"`: an annotated PDF page. */
    data class Pdf(val filename: String?, val pageNo: Int, val domain: String?) : Background
}
