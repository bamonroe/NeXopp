package com.xopp.android.io

import java.io.File

/**
 * A folder of short-lived scratch files, each handed out under a **name no other allocation reuses**.
 *
 * Staging used to build a fixed `<name>.tmp` path, so every open wrote `open.tmp`. Opens run on a
 * worker thread and a remote share can take seconds, so two of them overlap easily: the second
 * download overwrote the first's bytes before or while they were parsed, and both tabs came up
 * showing the same document. A unique name per allocation removes the shared path entirely.
 *
 * Uniqueness means nothing is ever reclaimed by being overwritten, so callers delete their file once
 * they are done reading it (see `MainActivity`'s open / import / save paths).
 */
class ScratchDir(private val dir: File) {

    /**
     * A fresh, never-before-used file in this folder. [prefix] is only a human-readable label and
     * [suffix] the extension to end it with (`""` for none) — the stores that keep their own folder
     * of never-rewritten files ([PdfStore], [ImageStore]) delegate here for exactly this guarantee.
     */
    fun newFile(prefix: String, suffix: String = ".tmp"): File {
        dir.mkdirs()
        while (true) {
            val candidate = File(dir, "$prefix-${System.currentTimeMillis()}-${counter.getAndIncrement()}$suffix")
            if (!candidate.exists()) return candidate
        }
    }

    private companion object {
        /**
         * Salts the name so two allocations within the same millisecond still differ. Atomic
         * because opens are staged on worker threads, so allocations genuinely race.
         */
        val counter = java.util.concurrent.atomic.AtomicInteger()
    }
}
