package com.xopp.android.format

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
    ORIGINAL,
    ZIPPED,
}
