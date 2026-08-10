package com.nexopp.io

import java.io.File

/**
 * The sweeping half of a store folder, shared by [PdfStore] and [ImageStore].
 *
 * Both stores hand out never-rewritten files (see [ScratchDir]), so neither folder reclaims anything
 * by overwriting it — each has to be swept instead, and both sweep it the same way: drop what no
 * live document refers to, then hold what's left to a byte budget oldest-first. These two helpers
 * are that shared sweep; the stores keep only what differs (which names are bookkeeping rather than
 * content, and what "live" means for them).
 */

/**
 * Delete every file directly in this folder that is not in [live] (a set of absolute paths) and
 * whose *name* is not in [except] — the sidecars a store keeps alongside its content.
 */
internal fun File.pruneUnreferenced(live: Set<String>, except: Set<String> = emptySet()) {
    listFiles()?.forEach { file ->
        if (file.isFile && file.name !in except && file.absolutePath !in live) file.delete()
    }
}

/**
 * Delete the **oldest** files in this folder that are not in [live] until it fits in [maxBytes].
 * Files named in [except] are sidecars rather than content, so they neither count toward the total
 * nor get deleted. [live] files are never evicted whatever the budget says — a folder whose live
 * files alone exceed it simply stays over.
 */
internal fun File.trimOldestTo(maxBytes: Long, live: Set<String>, except: Set<String> = emptySet()) {
    if (maxBytes >= Long.MAX_VALUE) return
    val files = listFiles().orEmpty().filter { it.isFile && it.name !in except }
    var total = files.sumOf(File::length)
    if (total <= maxBytes) return
    files.filter { it.absolutePath !in live }
        .sortedBy(File::lastModified)
        .forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) total -= size
        }
}
