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
}
