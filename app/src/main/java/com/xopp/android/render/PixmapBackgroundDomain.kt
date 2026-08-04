package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import java.io.File

/** The entry/filename a bundled pixmap background gets, numbered per page that carries one. */
fun attachPixmapFilename(index: Int, extension: String): String =
    if (extension.isEmpty()) "bg-$index" else "bg-$index.$extension"

/** A document re-pointed at its bundled pictures, plus the entry-name → file map to embed. */
data class PixmapAttachments(val document: Document, val entries: Map<String, File>)

/**
 * The pixmap counterpart of [documentWithPdfDomain]: rewrite every `pixmap` background to the
 * bundled form (`domain="attach"`, `filename="bg-<n>.<ext>"`) and return the files to embed under
 * those names, so a ZIP-package save carries its pictures with it instead of linking references
 * (`content://` URIs, absolute paths) that mean nothing on another machine.
 *
 * Unlike a PDF background there can be **many** — one per page — so each gets its own numbered
 * entry. [source] hands back the local copy of the picture a background's current reference names
 * (see `io.ImageStore`); a background whose picture we can't reach is left exactly as it was, since
 * an attach reference with no entry behind it would break where the original at least might not.
 * The extension is taken from the local copy's own bytes via [extensionFor], because desktop
 * Xournal++ picks its image loader by the attached file's suffix.
 *
 * Returns a copy; the working document is untouched.
 */
fun documentWithPixmapAttachments(doc: Document, source: (String) -> File?): PixmapAttachments {
    val entries = LinkedHashMap<String, File>()
    var index = 0
    val pages = doc.pages.map { page ->
        val bg = page.background
        val file = (bg as? Background.Pixmap)?.filename?.let(source)
        if (bg !is Background.Pixmap || file == null) return@map page
        val name = attachPixmapFilename(index++, extensionFor(file))
        entries[name] = file
        page.copy(background = bg.copy(domain = ATTACH_DOMAIN, filename = name))
    }
    return PixmapAttachments(doc.copy(pages = pages), entries)
}

/**
 * The file extension to give a bundled picture, sniffed from its leading bytes rather than from the
 * reference it came in under — a `content://` URI usually has no suffix at all, and the attached
 * name is what tells desktop Xournal++ how to decode it. Unknown bytes fall back to `png`, which is
 * the safest guess for something we already sniffed as an image.
 */
fun extensionFor(file: File): String {
    val head = ByteArray(12)
    val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
    if (read < 12) return "png"
    fun at(i: Int) = head[i].toInt() and 0xFF
    return when {
        at(0) == 0x89 && at(1) == 'P'.code -> "png"
        at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
        at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code -> "webp"
        at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "gif"
        else -> "png"
    }
}
