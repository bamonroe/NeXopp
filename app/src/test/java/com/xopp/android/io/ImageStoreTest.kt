package com.xopp.android.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pixmap-copy store: fresh names every time, unreferenced copies swept, and — like [PdfStore] —
 * a byte cap so a session that opens many large pictures can't sit on the whole folder forever.
 */
class ImageStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ImageStore(tmp.newFolder())

    @Test fun allocationsAreUnique() {
        val store = store()
        val files = (1..20).map { store.newFile().apply { writeText("png $it") } }
        assertEquals(20, files.map { it.absolutePath }.toSet().size)
    }

    @Test fun pruneKeepsOnlyReferencedFiles() {
        val store = store()
        val kept = store.newFile().apply { writeText("keep") }
        val dropped = store.newFile().apply { writeText("drop") }

        store.prune(listOf(kept.absolutePath, null))

        assertTrue(kept.isFile)
        assertFalse(dropped.exists())
    }

    @Test fun liveCopiesAreNeverEvictedByTheBudget() {
        val store = store()
        val old = store.newFile().apply { writeText("x".repeat(400)); setLastModified(1_000L) }
        val recent = store.newFile().apply { writeText("y".repeat(400)); setLastModified(9_000L) }

        store.prune(listOf(old.absolutePath, recent.absolutePath), maxBytes = 500)

        // 800 bytes against a 500-byte cap, but both are referenced by an open document: a copy the
        // renderer is still decoding is never deleted, so the store simply stays over.
        assertTrue(old.isFile)
        assertTrue(recent.isFile)
    }

    @Test fun theBudgetTrimsOldestFirstAmongWhatLivenessLeaves() {
        val store = store()
        val live = store.newFile().apply { writeText("l".repeat(400)) }
        // A stray file nothing allocated (an interrupted copy, a leftover from a previous run) is
        // what the cap is a backstop for; the liveness sweep drops it too, oldest-first below it.
        val strayOld = java.io.File(live.parentFile, "img-stray-old")
            .apply { writeText("x".repeat(400)); setLastModified(1_000L) }

        store.prune(listOf(live.absolutePath), maxBytes = 500)

        assertFalse(strayOld.exists())
        assertTrue(live.isFile)
    }

    @Test fun pruneWithoutABudgetLeavesLiveCopiesAlone() {
        val store = store()
        val live = store.newFile().apply { writeText("z".repeat(10_000)) }
        store.prune(listOf(live.absolutePath))
        assertTrue(live.isFile)
    }
}
