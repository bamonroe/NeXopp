package com.nexopp.io

import com.nexopp.format.SaveFormat

/**
 * The file name to offer when a document that came in as a **PDF** or an **image** is saved for the
 * first time, in the [format] it is about to be written as.
 *
 * A PDF opened from the picker or handed over by another app has no document file on disk behind
 * it — the annotations live only in the tab until they're written somewhere. Saving must never
 * write document bytes back over the source PDF, so the first save asks for a destination, and this
 * is the name it suggests: the source's own name with the extension swapped for the format's, so
 * the annotations sit beside the original.
 */
fun saveNameFor(sourceName: String, format: SaveFormat): String {
    val suffix = ".${format.extension}"
    val trimmed = sourceName.trim()
    if (trimmed.isEmpty()) return "Untitled$suffix"
    if (trimmed.endsWith(suffix, ignoreCase = true)) return trimmed
    // Only a real extension is replaced — a dot in a leading position is part of the name, not a
    // suffix, and a name with no dot at all just gains one.
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    return stem + suffix
}
