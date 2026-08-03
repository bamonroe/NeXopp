package com.xopp.android.tabs

import com.xopp.android.format.Xopp
import com.xopp.android.render.DrawingSurfaceView
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
     */
    fun save(session: TabSession) = runCatching {
        dir.mkdirs()
        val live = session.tabs.map { snapshotFile(it.id) }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(SNAPSHOT_SUFFIX) && file !in live) file.delete()
        }
        for (tab in session.tabs) {
            snapshotFile(tab.id).outputStream().use { Xopp.save(tab.document, it) }
        }
        indexFile().writeText(TabIndex.encode(session))
    }.isSuccess

    /**
     * Read the session back. Tabs whose snapshot is missing or unreadable are dropped — a damaged
     * snapshot costs that one tab, never the whole restore. Returns null when there is no session on
     * disk (first launch) or nothing survived, and the caller should start with a fresh blank tab.
     */
    fun load(): TabSession? = runCatching {
        val index = indexFile().takeIf(File::isFile) ?: return@runCatching null
        val parsed = TabIndex.decode(index.readText()) { DrawingSurfaceView.blankDocument() }
        val restored = parsed.tabs.mapNotNull { tab ->
            val doc = runCatching {
                snapshotFile(tab.id).takeIf(File::isFile)?.inputStream()?.use(Xopp::open)
            }.getOrNull() ?: return@mapNotNull null
            tab.copy(document = doc)
        }
        if (restored.isEmpty()) null
        else TabSession(restored, parsed.activeIndex.coerceIn(0, restored.lastIndex))
    }.getOrNull()

    /** Throw the whole cached session away (used when the user closes the last tab). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun indexFile() = File(dir, INDEX_NAME)

    private fun snapshotFile(id: String) = File(dir, "$id$SNAPSHOT_SUFFIX")

    companion object {
        private const val INDEX_NAME = "session.index"
        private const val SNAPSHOT_SUFFIX = ".xopp"

        /**
         * A fresh tab id. Time-based and counter-salted so ids stay unique within a run and across
         * runs, and never collide with a snapshot left behind by a previous session.
         */
        fun newId(): String = "tab-${System.currentTimeMillis()}-${counter++}"

        private var counter = 0
    }
}
