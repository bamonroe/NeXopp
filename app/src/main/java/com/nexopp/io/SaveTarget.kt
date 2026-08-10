package com.nexopp.io

/** The suffix every document this app writes carries, whichever `SaveFormat` produced the bytes. */
const val XOPP_SUFFIX = ".xopp"

/**
 * The file name to offer when a document that came in as a **PDF** is saved for the first time.
 *
 * A PDF opened from the picker or handed over by another app has no `.xopp` on disk behind it — the
 * annotations live only in the tab until they're written somewhere. Saving must never write `.xopp`
 * bytes back over the source PDF, so the first save asks for a destination, and this is the name it
 * suggests: the PDF's own name with the extension swapped, so annotations sit beside the original.
 */
fun xoppNameFor(sourceName: String): String {
    val trimmed = sourceName.trim()
    if (trimmed.isEmpty()) return "Untitled$XOPP_SUFFIX"
    if (trimmed.endsWith(XOPP_SUFFIX, ignoreCase = true)) return trimmed
    // Only a real extension is replaced — a dot in a leading position is part of the name, not a
    // suffix, and a name with no dot at all just gains one.
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    return stem + XOPP_SUFFIX
}
