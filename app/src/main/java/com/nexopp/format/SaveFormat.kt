package com.nexopp.format

/**
 * How the current document is serialised to disk, and the choice that "Save As" makes sticky: once
 * you pick one, every later plain Save reuses it (see `MainActivity`).
 *
 * Each member names both halves of the choice at once — document format *and* container — and
 * carries the per-format facts every save path needs (mime, extension, label), the shape
 * [ExportFormat] already uses, so no call site spells out a mime string or an extension again.
 * Why one flat enum rather than a `format × container` pair: see `docs/architecture.md`,
 * "Decision (2026-08-12): one flat SaveFormat, extended to .rnote".
 *
 * - [XOPP_GZIP] — the legacy gzip `.xopp` desktop Xournal++ writes: gzip-compressed XML, a PDF
 *   background linked by path/URI. The interchange-safe default.
 * - [XOPP_ZIP] — the newer ZIP-package `.xopp`: a single self-contained file with the PDF embedded
 *   as an archive entry (see [XoppZip]). Portable, but see the mimetype caveat in [XoppZip].
 * - [RNOTE] — Rnote's own format: gzip-compressed JSON, one container only.
 */
enum class SaveFormat(
    /** MIME type handed to the SAF picker and set on the created document. */
    val mime: String,
    /** File extension, without the leading dot, used to build save file names. */
    val extension: String,
    /** Human-readable name shown in the "Save As" dialog's format selector. */
    val label: String,
) {
    /**
     * The original gzip-compressed `.xopp` format: XML wrapped in gzip, with PDF backgrounds
     * referenced by external path/URI. This is the default and most compatible format for
     * interchange with desktop Xournal++.
     */
    XOPP_GZIP("application/octet-stream", "xopp", "Xournal++ (gzip)"),

    /**
     * The ZIP-package `.xopp` format: a single self-contained ZIP archive containing `content.xml`,
     * the mimetype declaration, and embedded assets (PDF backgrounds, images). More portable but
     * requires Xournal++ with ZIP support.
     */
    XOPP_ZIP("application/octet-stream", "xopp", "Xournal++ (ZIP package)"),

    /**
     * Rnote's `.rnote` format: a gzip-compressed JSON engine snapshot. Unlike `.xopp` it has a
     * single container, so there is no gzip/ZIP choice to make.
     */
    RNOTE("application/octet-stream", "rnote", "Rnote"),
}
