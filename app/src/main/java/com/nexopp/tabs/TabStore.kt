package com.nexopp.tabs

import com.nexopp.format.Xopp
import com.nexopp.render.blankDocument
import java.io.File

/**
 * Persists the open-tab session so closing the app and reopening it lands you back on the same set
 * of tabs, each with its unsaved edits intact.
 *
 * Layout under [dir] (an app-private folder, normally `filesDir/tabs`):
 * - `session.index` — the tab records and the selection ([TabIndex]).
 * - `<id>.xopp` — one snapshot per tab, written in the app's own on-disk format (gzip XML), so a
 *   restored tab is byte-for-byte the document you were editing, unsaved strokes included.
 *
 * Snapshots are *not* a substitute for saving: they are a crash/restart cache keyed by tab id, and
 * the user's real file (the tab's `uri`) is still the only thing the desktop ever sees.
 */
class TabStore(private val dir: File) {

    /**
     * Write [session] out, replacing whatever was there. Snapshots for tabs that are no longer open
     * are deleted, so a long-lived install doesn't accumulate the documents of closed tabs.
     *
     * Every file is written **atomically** — to a `.tmp` sibling that only replaces the real one
     * once the bytes are all down. This session cache is the *only* copy of a never-saved document,
     * and the moment it is written (going to the background) is the moment the process is most
     * likely to be killed; an in-place write killed halfway leaves a truncated snapshot that
     * [hydrate] can't parse, which is a whole document's worth of unsaved work gone.
     */
    fun save(session: TabSession) = runCatching {
        dir.mkdirs()
        val live = session.tabs.map { snapshotFile(it.id) }.toSet()
        dir.listFiles()?.forEach { file ->
            // Leftover temp files (from a write that was killed) are swept too: the real file they
            // would have replaced is still intact, so they are pure debris.
            val stale = file.name.endsWith(SNAPSHOT_SUFFIX) && file !in live
            if (stale || file.name.endsWith(TEMP_SUFFIX)) file.delete()
        }
        for (tab in session.tabs) {
            // An unhydrated tab's `document` is a placeholder, never its content — its snapshot on
            // disk is already the truth, so leave it alone rather than blanking it.
            if (!tab.hydrated) continue
            atomically(snapshotFile(tab.id)) { out -> Xopp.save(tab.document, out) }
        }
        atomically(indexFile()) { out -> out.write(TabIndex.encode(session).toByteArray()) }
    }.isSuccess

    /**
     * Write [target] by filling a `.tmp` sibling and renaming it over the top, so a reader (or the
     * next launch) sees either the previous file or the complete new one, never a half-written one.
     *
     * A failed write leaves the temp file behind and the previous [target] untouched, then rethrows
     * — losing this save is recoverable, replacing a good snapshot with a broken one is not.
     */
    private fun atomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val temp = File(target.parentFile, "${target.name}$TEMP_SUFFIX")
        temp.outputStream().use(write)
        // renameTo is atomic within a directory, but won't clobber an existing file on every
        // filesystem, so the old one goes first. The window between the two is why the temp file is
        // the one holding the good bytes by this point.
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            error("could not replace ${target.name}")
        }
    }

    /**
     * Read the session back **lazily**: this only reads the small index, so every tab comes back as an
     * unhydrated placeholder ([OpenTab.hydrated]) whose document is filled in by [hydrate] when it is
     * about to be shown. No gzip XML is parsed here at all — parsing a whole session's worth of it up
     * front is what used to block the first frame long enough for Android to raise an ANR.
     *
     * Tabs whose snapshot file is missing are dropped — a lost snapshot costs that one tab, never the
     * whole restore. Returns null when there is no session on disk (first launch) or nothing survived,
     * and the caller should start with a fresh blank tab.
     */
    fun load(): TabSession? = runCatching {
        val index = indexFile().takeIf(File::isFile) ?: return@runCatching null
        val parsed = TabIndex.decode(index.readText()) { blankDocument() }
        val present = parsed.tabs.filter { snapshotFile(it.id).isFile }
        if (present.isEmpty()) null
        else TabSession(present, parsed.activeIndex.coerceIn(0, present.lastIndex))
    }.getOrNull()

    /**
     * Copy the snapshot of [sourceId] in [from] to a tab called [newId] here, so a tab handed to the
     * other pane keeps its content without anyone parsing the document. Returns false when there was
     * nothing to copy.
     */
    fun adopt(from: TabStore, sourceId: String, newId: String): Boolean = runCatching {
        val src = from.snapshotFile(sourceId).takeIf(File::isFile) ?: return@runCatching false
        dir.mkdirs()
        src.copyTo(snapshotFile(newId), overwrite = true)
        true
    }.getOrDefault(false)

    /**
     * Parse [tab]'s snapshot and return the tab holding its real document. An already-hydrated tab is
     * returned untouched, and an unreadable snapshot leaves the tab as it was (a blank placeholder)
     * but marks it hydrated, so the damage is one empty tab rather than a re-read on every switch.
     */
    fun hydrate(tab: OpenTab): OpenTab {
        if (tab.hydrated) return tab
        val doc = runCatching {
            snapshotFile(tab.id).takeIf(File::isFile)?.inputStream()?.use(Xopp::open)
        }.getOrNull()
        return if (doc == null) tab.copy(hydrated = true) else tab.copy(document = doc, hydrated = true)
    }

    /** Throw the whole cached session away (used when the user closes the last tab). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun indexFile() = File(dir, INDEX_NAME)

    private fun snapshotFile(id: String) = File(dir, "$id$SNAPSHOT_SUFFIX")

    companion object {
        /** The index file name — the small text record of which tabs were open and which was showing. */
        private const val INDEX_NAME = "session.index"
        /** Suffix for tab snapshot files — each tab's document is saved as `<id>.xopp`. */
        private const val SNAPSHOT_SUFFIX = ".xopp"
        /** Suffix for the half-written sibling every save fills before renaming it into place. */
        private const val TEMP_SUFFIX = ".tmp"

        /**
         * A fresh tab id. Time-based and counter-salted so ids stay unique within a run and across
         * runs, and never collide with a snapshot left behind by a previous session.
         */
        fun newId(): String = "tab-${System.currentTimeMillis()}-${counter++}"

        private var counter = 0
    }
}
