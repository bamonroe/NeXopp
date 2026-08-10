package com.nexopp.render

import java.io.File
import java.lang.ref.SoftReference

/**
 * The extracted text layers of the PDFs currently open, keyed by file.
 *
 * Extraction walks the whole PDF and the resulting index is proportional to its text, so doing it
 * twice for one file — which is exactly what mirroring a PDF-backed document across the split panes
 * used to do — costs a second pass *and* a second copy of the words. Both panes take the same index
 * from here instead.
 *
 * Entries are held **softly**: an index nobody is using is a pure cache, and the collector may drop
 * it under memory pressure rather than have it compete with the bitmap budget. A dropped entry just
 * means the next open re-extracts.
 */
object PdfTextIndexCache {

    private val entries = HashMap<String, SoftReference<PdfTextIndex>>()

    /** The index already extracted for [file], or null if it has not been (or has been reclaimed). */
    fun get(file: File): PdfTextIndex? = synchronized(entries) {
        val key = file.absolutePath
        val hit = entries[key]?.get()
        if (hit == null) entries.remove(key)
        hit
    }

    /** Record [index] as [file]'s text layer, for the other views of the same PDF. */
    fun put(file: File, index: PdfTextIndex) {
        synchronized(entries) { entries[file.absolutePath] = SoftReference(index) }
    }

    /** Drop everything — the PDF bytes behind a path may have been replaced (merge, re-import). */
    fun forget(file: File) {
        synchronized(entries) { entries.remove(file.absolutePath) }
    }
}
