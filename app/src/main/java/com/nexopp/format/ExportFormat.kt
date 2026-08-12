package com.nexopp.format

/**
 * The targets "Export" can write to, and the per-format facts every export path needs: the MIME type
 * to hand the Storage Access Framework, the file extension, and the label the Export dialog shows.
 *
 * Keeping them here rather than inline at each call site means `MainActivity` never spells out a
 * mime string or an extension — it asks the format.
 *
 * The load-bearing distinction is [isMultiFile]. SAF's `CreateDocument` contract can only ever
 * produce **one** destination file, which is fine for PDF (a whole document in a single file) but
 * useless for SVG and the raster formats, where each page is its own file. Multi-file formats must
 * therefore be routed through `OpenDocumentTree` instead: the user picks a *folder*, and the
 * exporter creates one document per page inside it via [fileName]. Choosing the wrong picker for a
 * format is the mistake this flag exists to prevent.
 */
enum class ExportFormat(
    /** MIME type handed to the SAF picker and set on the created document. */
    val mime: String,
    /** File extension, without the leading dot, used to build export file names. */
    val extension: String,
    /** Human-readable name shown in the Export dialog's format selector. */
    val label: String,
    /** True for pixel formats, which need a rasterised page rather than vector drawing commands. */
    val isRaster: Boolean,
) {
    /** One PDF holding every exported page — the only single-file target. */
    PDF("application/pdf", "pdf", "PDF", isRaster = false),

    /** Vector SVG, one file per page. */
    SVG("image/svg+xml", "svg", "SVG", isRaster = false),

    /** Lossless raster, one file per page. */
    PNG("image/png", "png", "PNG", isRaster = true),

    /** Lossy raster, one file per page. */
    JPEG("image/jpeg", "jpg", "JPEG", isRaster = true),

    /** WebP raster, one file per page. */
    WEBP("image/webp", "webp", "WebP", isRaster = true);

    /**
     * Whether exporting writes one file per page instead of a single document.
     *
     * Every format except [PDF] is per-page, so this drives which SAF picker the export flow
     * launches — see the class KDoc.
     */
    val isMultiFile: Boolean get() = this != PDF

    /**
     * The file name for an exported page: `"base.ext"` when [pageIndex] is null (the single-file
     * case), otherwise `"base-001.ext"`.
     *
     * [pageIndex] is zero-based like the document model, but the name shows the 1-based page number
     * the user sees, zero-padded to three digits so a directory listing sorts in page order.
     */
    fun fileName(base: String, pageIndex: Int?): String =
        if (pageIndex == null) "$base.$extension"
        else "$base-${(pageIndex + 1).toString().padStart(3, '0')}.$extension"
}
