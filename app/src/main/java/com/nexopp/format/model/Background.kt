package com.nexopp.format.model

/**
 * A page background. Mirrors `<background type=…>`; the three concrete types map to the
 * `solid` / `pixmap` / `pdf` values. Styles and domains are kept as raw strings so unknown
 * desktop values round-trip unchanged.
 *
 * [extraAttrs] carries the attributes we don't interpret (newer desktop builds put custom ruling
 * configuration on a solid background, for instance) so they survive a load/save round trip.
 */
sealed interface Background {

    /** Attributes on the `<background>` element we don't interpret, preserved for round-trip. */
    val extraAttrs: Map<String, String>

    /**
     * `type="solid"`: a ruled/graph/plain sheet in a single colour.
     *
     * @param color The background colour as an ARGB int.
     * @param style The ruling style (e.g., "plain", "ruled", "graph").
     * @param extraAttrs Extra attributes preserved for round-trip.
     */
    data class Solid(
        val color: Int,
        val style: String,
        override val extraAttrs: Map<String, String> = emptyMap(),
    ) : Background

    /**
     * `type="pixmap"`: an image behind the page.
     *
     * @param domain The image domain (e.g., "absolute" for filesystem paths).
     * @param filename The path or URI to the image file.
     * @param extraAttrs Extra attributes preserved for round-trip.
     */
    data class Pixmap(
        val domain: String,
        val filename: String,
        override val extraAttrs: Map<String, String> = emptyMap(),
    ) : Background

    /**
     * `type="pdf"`: an annotated PDF page.
     *
     * @param filename The PDF file path or URI, or null if embedded in a ZIP package.
     * @param pageNo The 0-based page index within the PDF.
     * @param domain The domain for the filename (e.g., "attach" for ZIP-embedded PDFs).
     * @param extraAttrs Extra attributes preserved for round-trip.
     */
    data class Pdf(
        val filename: String?,
        val pageNo: Int,
        val domain: String?,
        override val extraAttrs: Map<String, String> = emptyMap(),
    ) : Background
}
