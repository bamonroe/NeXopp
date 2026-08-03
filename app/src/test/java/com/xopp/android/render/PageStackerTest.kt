package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStackerTest {

    private fun page(w: Double, h: Double) =
        Page(w, h, Background.Solid(0xFFFFFFFF.toInt(), "plain"), listOf(Layer(emptyList())))

    // Two 100x200pt pages in a 100px-wide view (scale 1.0) with a 10px gap.
    private val layout = PageStacker.stack(listOf(page(100.0, 200.0), page(100.0, 200.0)), 100, 10f)

    @Test fun stacksPagesTopToBottomWithGaps() {
        assertEquals(2, layout.boxes.size)
        assertEquals(10f, layout.boxes[0].topPx, 1e-4f) // first gap
        assertEquals(200f, layout.boxes[0].heightPx, 1e-4f)
        assertEquals(220f, layout.boxes[1].topPx, 1e-4f) // gap + page + gap
        assertEquals(430f, layout.totalHeightPx, 1e-4f) // 3 gaps + 2 pages
        assertEquals(1f, layout.boxes[0].scale, 1e-4f)
    }

    @Test fun pageAtMapsContentYToItsBand() {
        assertNull("gap above the first page has no page", layout.pageAt(5f))
        assertEquals(0, layout.pageAt(50f)!!.index)
        assertNull("inter-page gap has no page", layout.pageAt(215f))
        assertEquals(1, layout.pageAt(300f)!!.index)
    }

    @Test fun visibleClipsToScrollWindow() {
        val onlyFirst = layout.visible(scrollY = 0f, viewHeightPx = 100f)
        assertEquals(listOf(0), onlyFirst.map { it.index })
        val both = layout.visible(scrollY = 150f, viewHeightPx = 200f)
        assertEquals(listOf(0, 1), both.map { it.index })
    }

    @Test fun emptyOrZeroWidthIsSafe() {
        assertTrue(PageStacker.stack(emptyList(), 100, 10f).boxes.isEmpty())
        assertTrue(PageStacker.stack(listOf(page(100.0, 200.0)), 0, 10f).boxes.isEmpty())
    }

    @Test fun defaultZoomFitsWidthAndPageStartsAtLeftZero() {
        val box = layout.boxes[0]
        assertEquals(1f, box.scale, 1e-4f)
        assertEquals(100f, box.widthPx, 1e-4f)
        assertEquals(0f, box.leftPx, 1e-4f) // fits exactly, so no centring offset
        assertEquals(100f, layout.contentWidthPx, 1e-4f)
    }

    @Test fun zoomScalesPagesAndWidensContent() {
        val zoomed = PageStacker.stack(listOf(page(100.0, 200.0)), 100, 10f, zoom = 2f)
        val box = zoomed.boxes[0]
        assertEquals(2f, box.scale, 1e-4f)
        assertEquals(200f, box.widthPx, 1e-4f) // twice as wide as the 100px view
        assertEquals(200f, zoomed.contentWidthPx, 1e-4f)
        assertEquals(0f, box.leftPx, 1e-4f) // widest page anchors the content band
    }

    @Test fun twoColumnsLayPagesSideBySide() {
        // Two 100x200pt pages in a 210px view with a 10px gap: each column is 100px, so scale 1.
        val grid = PageStacker.stack(listOf(page(100.0, 200.0), page(100.0, 200.0)), 210, 10f, columns = 2)
        assertEquals(2, grid.columns)
        assertEquals(1f, grid.boxes[0].scale, 1e-4f)
        assertEquals(10f, grid.boxes[0].topPx, 1e-4f)
        assertEquals(10f, grid.boxes[1].topPx, 1e-4f) // same row
        assertEquals(0f, grid.boxes[0].leftPx, 1e-4f)
        assertEquals(110f, grid.boxes[1].leftPx, 1e-4f) // page + gap
        assertEquals(220f, grid.totalHeightPx, 1e-4f) // one row: gap + page + gap
    }

    @Test fun twoColumnsWrapToASecondRow() {
        val pages = List(3) { page(100.0, 200.0) }
        val grid = PageStacker.stack(pages, 210, 10f, columns = 2)
        assertEquals(220f, grid.boxes[2].topPx, 1e-4f)
        assertEquals(430f, grid.totalHeightPx, 1e-4f)
        // The lone page on the last row is centred in the content band.
        assertEquals(55f, grid.boxes[2].leftPx, 1e-4f)
    }

    @Test fun pageAtDistinguishesColumns() {
        val grid = PageStacker.stack(listOf(page(100.0, 200.0), page(100.0, 200.0)), 210, 10f, columns = 2)
        assertEquals(0, grid.pageAt(50f, 100f)!!.index)
        assertEquals(1, grid.pageAt(150f, 100f)!!.index)
        assertNull("the gap between columns has no page", grid.pageAt(105f, 100f))
        // nearestPage resolves that gap to a neighbour so a viewport-centre probe still works.
        assertEquals(1, grid.nearestPage(106f, 100f)!!.index)
    }

    @Test fun zoomedOutPageIsCentredInTheViewWidth() {
        val out = PageStacker.stack(listOf(page(100.0, 200.0)), 100, 10f, zoom = 0.5f)
        val box = out.boxes[0]
        assertEquals(50f, box.widthPx, 1e-4f)
        assertEquals(100f, out.contentWidthPx, 1e-4f) // view width, since the page is narrower
        assertEquals(25f, box.leftPx, 1e-4f) // (100 - 50) / 2, centred
    }
}
