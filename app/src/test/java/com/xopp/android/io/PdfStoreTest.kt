package com.xopp.android.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The store's one promise: a file handed out is never handed out again, so a PDF a canvas is still
 * rasterising can't be overwritten by the next document that opens.
 */
class PdfStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = PdfStore(tmp.newFolder())

    @Test fun allocationsAreUnique() {
        val store = store()
        val files = (1..50).map { store.newFile().apply { writeText("pdf $it") } }
        assertEquals(50, files.map { it.absolutePath }.toSet().size)
        files.forEachIndexed { i, f -> assertEquals("pdf ${i + 1}", f.readText()) }
    }

    @Test fun newFileCreatesTheFolderOnDemand() {
        val dir = java.io.File(tmp.newFolder(), "missing")
        val file = PdfStore(dir).newFile().apply { writeText("%PDF") }
        assertTrue(file.isFile)
    }

    @Test fun pruneKeepsOnlyReferencedFiles() {
        val store = store()
        val kept = store.newFile().apply { writeText("keep") }
        val dropped = store.newFile().apply { writeText("drop") }

        store.prune(listOf(kept.absolutePath, null, "/somewhere/else.pdf"))

        assertTrue(kept.isFile)
        assertFalse(dropped.exists())
    }

    @Test fun pruneWithNothingLiveEmptiesTheStore() {
        val store = store()
        val file = store.newFile().apply { writeText("drop") }
        store.prune(emptyList())
        assertFalse(file.exists())
    }
}
