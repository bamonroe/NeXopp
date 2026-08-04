package com.xopp.android.io

import java.io.File

/**
 * A home for the background PDFs the open documents rasterise from — **one file per document**.
 *
 * Every `pdf` background has to live as a real local file, because [android.graphics.pdf.PdfRenderer]
 * rasterises from a file descriptor held open for as long as the document is on a canvas. The whole
 * point of this class is that such a file is **never rewritten**: each [newFile] hands out a fresh,
 * unique name.
 *
 * That immutability is load-bearing rather than tidy. Backgrounds used to be copied to one fixed
 * `background.pdf` (and imports to one `imported.pdf`), so opening a second PDF-backed document —
 * in the other split pane, or in another tab — overwrote the bytes underneath a renderer that was
 * still reading them. Pages already rasterised stayed correct while everything scrolled to
 * afterwards came back blank, and a reload "fixed" it. With a file per document there is nothing to
 * clobber, and two views of one document (a mirrored tab) can share a path safely because neither
 * of them writes it.
 *
 * Unique names mean the folder would grow forever, so [prune] sweeps it against the paths the live
 * tabs still reference — called whenever a session is persisted.
 */
class PdfStore(private val dir: File) {

    /** A fresh, never-before-used PDF file in this store. The caller writes the bytes. */
    fun newFile(): File {
        dir.mkdirs()
        while (true) {
            val candidate = File(dir, "pdf-${System.currentTimeMillis()}-${counter++}.pdf")
            if (!candidate.exists()) return candidate
        }
    }

    /**
     * The store's copy for [key], generating it with [write] the first time. [key] identifies the
     * *content* (for a text import: a hash of the source text and its name), so reopening the same
     * file while its entry survives reuses the PDF instead of paying to typeset it again.
     *
     * The immutability rule still holds — a hit returns the very same never-rewritten file, and a
     * miss allocates a fresh [newFile]. [prune] drops entries whose file it deleted, so a cache miss
     * after a tab closes simply regenerates.
     */
    fun cached(key: String, write: (File) -> Unit): File {
        index()[key]?.let { name -> File(dir, name).takeIf(File::isFile)?.let { return it } }
        val file = newFile()
        write(file)
        writeIndex(index() + (key to file.name))
        return file
    }

    /**
     * Delete every file in this store that is not one of [keep] (the `pdfPath`s of the tabs that are
     * still open, in either pane). Files outside [dir] are none of our business, so a `keep` entry
     * pointing elsewhere simply matches nothing.
     */
    fun prune(keep: Collection<String?>) {
        val live = keep.filterNotNull().toSet()
        dir.listFiles()?.forEach { file ->
            // The index is bookkeeping, not a background: never sweep it away with the PDFs.
            if (file.isFile && file.name != INDEX_FILE && file.absolutePath !in live) file.delete()
        }
        val surviving = index().filterValues { File(dir, it).isFile }
        writeIndex(surviving)
    }

    /** The content-key → file-name map, as last written. Missing or corrupt reads as empty. */
    private fun index(): Map<String, String> = runCatching {
        File(dir, INDEX_FILE).takeIf(File::isFile)?.readLines().orEmpty()
            .mapNotNull { line ->
                val at = line.lastIndexOf('\t')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeIndex(entries: Map<String, String>) {
        runCatching {
            dir.mkdirs()
            // A lost index only costs a regeneration, so failing to persist it is never fatal.
            File(dir, INDEX_FILE).writeText(entries.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }

    private companion object {
        /** Salts the name so two allocations within the same millisecond still differ. */
        var counter = 0

        /** Sidecar holding the [cached] key → file-name map. Tab-separated, one entry per line. */
        const val INDEX_FILE = "index.tsv"
    }
}
