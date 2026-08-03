package com.xopp.android.tabs

import com.xopp.android.format.SaveFormat
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two documents open at once must come back as two documents. The per-document background PDF fix
 * only holds if the tab it belongs to carries its own `pdfPath` all the way through a restart —
 * a session that collapsed both tabs onto one path is exactly the bug that blanked a pane.
 */
class TabStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun doc(width: Double) = Document(
        pages = listOf(Page(width, 100.0, Background.Solid(-1, "plain"), listOf(Layer(emptyList())))),
    )

    @Test fun aTwoTabSessionRoundTripsWithItsOwnPdfPathPerTab() {
        val store = TabStore(tmp.newFolder())
        val session = TabSession(
            tabs = listOf(
                OpenTab("t1", "first.xopp", doc(111.0), "content://docs/1", SaveFormat.ZIPPED, "/cache/pdf-1.pdf", 2),
                OpenTab("t2", "second.xopp", doc(222.0), "content://docs/2", SaveFormat.ZIPPED, "/cache/pdf-2.pdf", 5),
            ),
            activeIndex = 1,
        )

        assertEquals(true, store.save(session))
        val back = store.load()!!

        assertEquals(listOf("/cache/pdf-1.pdf", "/cache/pdf-2.pdf"), back.tabs.map { it.pdfPath })
        assertNotEquals(back.tabs[0].pdfPath, back.tabs[1].pdfPath)
        // Each tab's snapshot is its own document, not the other tab's.
        assertEquals(listOf(111.0, 222.0), back.tabs.map { it.document.pages[0].width })
        assertEquals(listOf("t1", "t2"), back.tabs.map { it.id })
        assertEquals(listOf(2, 5), back.tabs.map { it.page })
        assertEquals(1, back.activeIndex)
    }

    @Test fun aTabWithNoPdfBackgroundRestoresWithoutOne() {
        val store = TabStore(tmp.newFolder())
        store.save(TabSession(listOf(OpenTab("t1", "plain.xopp", doc(100.0))), 0))
        assertNull(store.load()!!.tabs[0].pdfPath)
    }

    @Test fun aTabWhoseSnapshotVanishedIsDroppedRatherThanRestoredEmpty() {
        val dir = tmp.newFolder()
        val store = TabStore(dir)
        store.save(
            TabSession(
                listOf(OpenTab("t1", "a", doc(111.0), pdfPath = "/cache/pdf-1.pdf"), OpenTab("t2", "b", doc(222.0))),
                1,
            ),
        )
        java.io.File(dir, "t2.xopp").delete()

        val back = store.load()!!
        assertEquals(listOf("t1"), back.tabs.map { it.id })
        assertEquals("/cache/pdf-1.pdf", back.tabs[0].pdfPath)
        assertEquals(0, back.activeIndex)
    }

    @Test fun closingATabDeletesItsSnapshot() {
        val dir = tmp.newFolder()
        val store = TabStore(dir)
        store.save(TabSession(listOf(OpenTab("t1", "a", doc(111.0)), OpenTab("t2", "b", doc(222.0))), 0))
        store.save(TabSession(listOf(OpenTab("t1", "a", doc(111.0))), 0))

        assertEquals(false, java.io.File(dir, "t2.xopp").exists())
        assertEquals(listOf("t1"), store.load()!!.tabs.map { it.id })
    }

    @Test fun noSessionOnDiskLoadsAsNull() {
        assertNull(TabStore(tmp.newFolder()).load())
    }
}
