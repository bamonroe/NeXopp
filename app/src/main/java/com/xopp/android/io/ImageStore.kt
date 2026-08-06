package com.xopp.android.io

import java.io.File

/**
 * A home for the pictures behind `pixmap` page backgrounds — the raster counterpart of [PdfStore].
 *
 * A `.xopp` names its pixmap background by reference (a `content://` URI, an absolute or relative
 * path, or a ZIP entry), and none of those survive on their own: a grant expires, a desktop-authored
 * path doesn't exist on Android, an archive entry has no filesystem at all. So every reference we
 * can reach once is copied in here, and the renderer decodes from **this** copy for as long as the
 * document is open (see `ImageBackgroundCache`).
 *
 * As in [PdfStore], a file here is **never rewritten**: [newFile] hands out a fresh name every time,
 * so two documents that reference the same picture can't clobber each other's bytes mid-decode. The
 * folder is instead bounded by [prune], swept against the copies the open documents still use.
 *
 * The document's *own* reference is deliberately left untouched by all of this — the local copy is a
 * side table, not a rewrite — so a plain Save writes back the reference the file came in with and a
 * desktop-authored document round-trips unchanged.
 */
class ImageStore(private val dir: File) {

    /** Hands out this store's unique names; see [ScratchDir] for why allocation has to be atomic. */
    private val scratch = ScratchDir(dir)

    /** A fresh, never-before-used image file in this store. The caller writes the bytes. */
    fun newFile(): File = scratch.newFile("img", suffix = "")

    /** Copy [stream] (closed here) into a fresh file in this store and return it. */
    fun copyIn(stream: java.io.InputStream): File {
        val out = newFile()
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /**
     * Delete the copies in this store that no live document refers to any more, then hold the folder
     * to [maxBytes] by deleting the **oldest** surviving non-live files until it fits — the same
     * oldest-first trim [PdfStore.prune] does. Live copies are never evicted, whatever the budget
     * says, so a session whose open documents alone exceed it simply stays over.
     */
    fun prune(keep: Collection<String?>, maxBytes: Long = UNLIMITED) {
        val live = keep.filterNotNull().toSet()
        dir.pruneUnreferenced(live)
        dir.trimOldestTo(maxBytes, live)
    }

    companion object {
        /** The "no cap" sentinel, matching [PdfStore.UNLIMITED]. */
        const val UNLIMITED: Long = Long.MAX_VALUE
    }
}
