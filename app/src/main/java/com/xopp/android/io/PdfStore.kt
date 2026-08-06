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
 * tabs still reference — called whenever a session is persisted. Liveness alone bounds nothing
 * between two of those sweeps (a long session can open document after document), so [prune] also
 * takes a byte budget and, once the folder exceeds it, drops the oldest files that nothing live
 * refers to.
 */
class PdfStore(private val dir: File) {

    /** Hands out this store's unique names; see [ScratchDir] for why allocation has to be atomic. */
    private val scratch = ScratchDir(dir)

    /** A fresh, never-before-used PDF file in this store. The caller writes the bytes. */
    fun newFile(): File = scratch.newFile("pdf", ".pdf")

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
     * Delete the files in this store that neither a live tab nor the [cached] index refers to.
     * [keep] is the `pdfPath`s of the tabs still open in either pane; files outside [dir] are none of
     * our business, so a `keep` entry pointing elsewhere simply matches nothing.
     *
     * A cached entry outliving its tab is the point of caching — reopening the same text file should
     * not pay to typeset it again — so the cache is bounded by [maxBytes] rather than by liveness:
     * once the folder exceeds the budget, cached entries are evicted oldest-first until it fits. Live
     * files are never evicted, whatever the budget says, so a store whose open documents alone exceed
     * it simply stays over. Anything the budget drops costs at most one regeneration.
     */
    fun prune(keep: Collection<String?>, maxBytes: Long = UNLIMITED) {
        val live = keep.filterNotNull().toSet()
        val cached = index().values.map { File(dir, it).absolutePath }.toSet()
        // The index is bookkeeping, not a background: never sweep it away with the PDFs.
        dir.pruneUnreferenced(live + cached, except = setOf(INDEX_FILE))
        dir.trimOldestTo(maxBytes, live, except = setOf(INDEX_FILE))
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

    companion object {
        /** The "no budget" [prune] cap — every file that survives the liveness sweep is kept. */
        const val UNLIMITED: Long = Long.MAX_VALUE

        /** Sidecar holding the [cached] key → file-name map. Tab-separated, one entry per line. */
        private const val INDEX_FILE = "index.tsv"
    }
}
