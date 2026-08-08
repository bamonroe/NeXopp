package com.xopp.android.panes

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.tabs.OpenTab
import com.xopp.android.tabs.TabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [MirrorSync] fans an edit out to the other views of a document — once per pass, not per edit. */
class MirrorSyncTest {

    @get:Rule val temp = TemporaryFolder()

    private fun pane() = EditorPane(TabStore(temp.newFolder()))

    /** A one-page document whose page width doubles as an identity marker in the assertions. */
    private fun doc(width: Double) =
        Document(pages = listOf(Page(width, 100.0, Background.Solid(-1, "plain"), listOf(Layer(emptyList())))))

    /** Two panes, each showing a tab that is a view of the same document key. */
    private fun mirroredPanes(): Triple<EditorPane, EditorPane, MutableList<Runnable>> {
        val left = pane()
        val right = pane()
        val key = "doc-1"
        left.tabs.open(OpenTab("a", "t", doc(1.0), docKey = key))
        right.tabs.open(OpenTab("b", "t", doc(1.0), docKey = key))
        return Triple(left, right, mutableListOf())
    }

    @Test
    fun `edits are coalesced into a single scheduled pass`() {
        val (left, right, posted) = mirroredPanes()
        val sync = MirrorSync(listOf(left, right)) { posted += it }

        sync.propagate(left, doc(2.0))
        sync.propagate(left, doc(3.0))
        sync.propagate(left, doc(4.0))

        assertEquals("three edits, one scheduled pass", 1, posted.size)
        assertEquals("nothing applied before the pass runs", 1.0, right.tabs.active!!.document.pages[0].width, 0.0)

        posted.single().run()
        assertEquals("the last edit wins", 4.0, right.tabs.active!!.document.pages[0].width, 0.0)
    }

    @Test
    fun `every view of the document gets the same instance, hydrated`() {
        val (left, right, posted) = mirroredPanes()
        val sync = MirrorSync(listOf(left, right)) { posted += it }
        val edited = doc(9.0)

        sync.propagate(left, edited)
        posted.single().run()

        for (p in listOf(left, right)) {
            val tab = p.tabs.active!!
            assertSame("one shared graph, not a copy per tab", edited, tab.document)
            assertEquals(true, tab.hydrated)
        }
    }

    @Test
    fun `flush applies a pending edit immediately`() {
        val (left, right, posted) = mirroredPanes()
        val sync = MirrorSync(listOf(left, right)) { posted += it }

        sync.propagate(left, doc(5.0))
        sync.flush()

        assertEquals(5.0, right.tabs.active!!.document.pages[0].width, 0.0)
        posted.single().run() // the scheduled pass then finds nothing left to do
        assertEquals(5.0, right.tabs.active!!.document.pages[0].width, 0.0)
    }

    @Test
    fun `a document open in one pane only schedules nothing`() {
        val left = pane()
        left.tabs.open(OpenTab("a", "t", doc(1.0), docKey = "solo"))
        val posted = mutableListOf<Runnable>()
        val sync = MirrorSync(listOf(left)) { posted += it }

        sync.propagate(left, doc(2.0))

        assertEquals(0, posted.size)
    }
}
