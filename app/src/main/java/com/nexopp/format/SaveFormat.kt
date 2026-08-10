package com.nexopp.format

/**
 * How the current document is serialised to disk, and the choice that "Save As" makes sticky: once
 * you pick one, every later plain Save reuses it (see `MainActivity`).
 *
 * - [ORIGINAL] — the legacy gzip `.xopp` desktop Xournal++ writes: gzip-compressed XML, a PDF
 *   background linked by path/URI. The interchange-safe default.
 * - [ZIPPED] — the newer ZIP-package `.xopp`: a single self-contained file with the PDF embedded as
 *   an archive entry (see [XoppZip]). Portable, but see the mimetype caveat in [XoppZip].
 */
enum class SaveFormat {
    /**
     * The original gzip-compressed `.xopp` format: XML wrapped in gzip, with PDF backgrounds
     * referenced by external path/URI. This is the default and most compatible format for
     * interchange with desktop Xournal++.
     */
    ORIGINAL,

    /**
     * The ZIP-package `.xopp` format: a single self-contained ZIP archive containing `content.xml`,
     * the mimetype declaration, and embedded assets (PDF backgrounds, images). More portable but
     * requires Xournal++ with ZIP support.
     */
    ZIPPED,
}
