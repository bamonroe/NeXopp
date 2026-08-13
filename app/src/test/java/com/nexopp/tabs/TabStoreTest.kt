package com.nexopp.tabs

import com.nexopp.format.SaveFormat
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
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
                OpenTab("t1", "first.xopp", doc(111.0), "content://docs/1", SaveFormat.XOPP_ZIP, "/cache/pdf-1.pdf", 2),
                OpenTab("t2", "second.xopp", doc(222.0), "content://docs/2", SaveFormat.XOPP_ZIP, "/cache/pdf-2.pdf", 5),
            ),
            activeIndex = 1,
        )

        assertEquals(true, store.save(session))
        val back = store.load()!!

        assertEquals(listOf("/cache/pdf-1.pdf", "/cache/pdf-2.pdf"), back.tabs.map { it.pdfPath })
        assertNotEquals(back.tabs[0].pdfPath, back.tabs[1].pdfPath)
        // Loading parses nothing: every tab comes back as a placeholder awaiting [hydrate].
        assertEquals(listOf(false, false), back.tabs.map { it.hydrated })
        // Each tab's snapshot is its own document, not the other tab's.
        assertEquals(111.0, store.hydrate(back.tabs[0]).document.pages[0].width, 0.0)
        assertEquals(222.0, store.hydrate(back.tabs[1]).document.pages[0].width, 0.0)
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

    /** The lazy-restore hazard: persisting a session must not write placeholders over real snapshots. */
    @Test fun savingAnUnhydratedTabLeavesItsSnapshotAlone() {
        val dir = tmp.newFolder()
        val store = TabStore(dir)
        store.save(TabSession(listOf(OpenTab("t1", "a", doc(111.0)), OpenTab("t2", "b", doc(222.0))), 1))

        // Restore (t1 comes back unhydrated) and persist again, as a tab switch would.
        val back = store.load()!!
        store.save(back)

        assertEquals(111.0, store.hydrate(store.load()!!.tabs[0]).document.pages[0].width, 0.0)
    }

    /** Handing a not-yet-parsed tab to the other pane copies its snapshot under the copy's new id. */
    @Test fun adoptingATabCarriesItsSnapshotToTheOtherStore() {
        val left = TabStore(tmp.newFolder())
        val right = TabStore(tmp.newFolder())
        left.save(TabSession(listOf(OpenTab("t1", "a", doc(111.0))), 0))

        assertEquals(true, right.adopt(left, "t1", "t9"))

        val copy = right.hydrate(OpenTab("t9", "a", doc(1.0), hydrated = false))
        assertEquals(111.0, copy.document.pages[0].width, 0.0)
    }

    @Test fun hydratingAnAlreadyLoadedTabIsANoOp() {
        val tab = OpenTab("t1", "a", doc(111.0))
        assertEquals(tab, TabStore(tmp.newFolder()).hydrate(tab))
    }

    @Test fun noSessionOnDiskLoadsAsNull() {
        assertNull(TabStore(tmp.newFolder()).load())
    }
}
