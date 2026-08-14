package com.nexopp.format.rnote

import com.nexopp.format.model.Document
import java.io.OutputStream

/**
 * The one entry point the save path calls to write a `.rnote` — the mirror of [readRnote]'s
 * `readDocument`. Deliberately thin: the snapshot is assembled by `RnoteSnapshotWriter.kt` and
 * wrapped by [RnoteContainer], so this file is the seam, not the work.
 *
 * @param document The document to write.
 * @param output The stream to write the file to. Not closed.
 */
fun writeRnote(document: Document, output: OutputStream) {
    RnoteContainer.write(writeSnapshot(document), output)
}
