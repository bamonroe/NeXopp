package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportStateTest {

    /** A viewport of 100×100 over content of 200×400 at zoom 1. */
    private fun viewport() = ViewportState().apply { setBounds(100f, 100f, 200f, 400f) }

    @Test fun `max scroll is the content beyond the viewport`() {
        val v = viewport()
        assertEquals(100f, v.maxScrollX(), 0f)
        assertEquals(300f, v.maxScrollY(), 0f)
        assertTrue(v.canScroll())
    }

    @Test fun `content smaller than the viewport cannot scroll`() {
        val v = ViewportState().apply { setBounds(100f, 100f, 50f, 50f) }
        assertEquals(0f, v.maxScrollX(), 0f)
        assertEquals(0f, v.maxScrollY(), 0f)
        assertFalse(v.canScroll())
    }

    @Test fun `scrollBy clamps and reports whether it moved`() {
        val v = viewport()
        assertTrue(v.scrollBy(10f, 20f))
        assertEquals(10f, v.scrollX, 0f)
        assertEquals(20f, v.scrollY, 0f)
        assertTrue(v.scrollBy(0f, 1000f))
        assertEquals(300f, v.scrollY, 0f)
        assertFalse(v.scrollBy(0f, 50f)) // already pinned at the bottom
    }

    @Test fun `scrollToY clamps to the range`() {
        val v = viewport()
        v.scrollToY(-5f)
        assertEquals(0f, v.scrollY, 0f)
        v.scrollToY(9999f)
        assertEquals(300f, v.scrollY, 0f)
    }

    @Test fun `setBounds pulls a stale offset back into range`() {
        val v = viewport()
        v.scrollToY(300f)
        v.setBounds(100f, 100f, 200f, 150f) // pages shrank
        assertEquals(50f, v.scrollY, 0f)
    }

    @Test fun `shrinking the view height keeps the document centre`() {
        val v = viewport() // view 100x100, content 200x400
        v.scrollToY(100f) // centre at y=150 -> frac 0.375
        v.setBounds(100f, 60f, 200f, 400f)
        assertEquals(0.375f, (v.scrollY + 30f) / 400f, 1e-4f)
    }

    @Test fun `growing the view width keeps the document centre`() {
        val v = ViewportState().apply { setBounds(100f, 100f, 400f, 400f) }
        v.scrollBy(100f, 0f) // centre at x=150 -> frac 0.375
        v.setBounds(160f, 100f, 400f, 400f)
        assertEquals(0.375f, (v.scrollX + 80f) / 400f, 1e-4f)
    }

    @Test fun `a view-size change never scrolls negative when content is shorter than the view`() {
        val v = viewport()
        v.setBounds(100f, 200f, 200f, 150f)
        assertEquals(0f, v.scrollY, 0f)
        assertEquals(0f, v.scrollX, 0f)
    }

    /** A viewport of 100×100 over content of 200×180 — 80px of vertical scroll to lose. */
    private fun shortViewport() = ViewportState().apply { setBounds(100f, 100f, 200f, 180f) }

    @Test fun `a round trip through a viewport too small to hold the position restores it`() {
        val v = shortViewport()
        v.scrollToY(80f)
        // Split view halves the pane: the fit-to-width layout halves with it, leaving content that
        // is shorter than the viewport, so there is nowhere to scroll and the offset is clamped away.
        v.setBounds(50f, 100f, 100f, 90f)
        assertEquals(0f, v.scrollY, 0f)
        v.setBounds(100f, 100f, 200f, 180f)
        assertEquals(80f, v.scrollY, 1e-3f)
    }

    @Test fun `a relayout while the position is pending keeps it pending`() {
        val v = shortViewport()
        v.scrollToY(80f)
        v.setBounds(50f, 100f, 100f, 90f) // clamped away
        v.setBounds(50f, 100f, 100f, 95f) // a stroke grew the page; same view size
        assertEquals(0f, v.scrollY, 0f)
        v.setBounds(100f, 100f, 200f, 180f)
        assertEquals(80f, v.scrollY, 1e-3f)
    }

    @Test fun `scrolling inside the small viewport gives up the pending position`() {
        val v = viewport() // view 100x100, content 200x400
        v.scrollToY(300f) // pinned at the bottom, so the halved extent can't hold the centre either
        v.setBounds(50f, 100f, 100f, 200f)
        assertEquals(100f, v.scrollY, 0f) // clamped to the new bottom, position still owed
        v.scrollToY(10f) // the reader picks a new place; the old one is no longer owed
        v.setBounds(100f, 100f, 200f, 400f)
        // Centre of the small viewport (10 + 50 of 200) re-anchored into the full extent.
        assertEquals(0.3f, (v.scrollY + 50f) / 400f, 1e-4f)
    }

    /** Relayout the way the stacked layout does: extents scale with the zoom. */
    private fun ViewportState.relayoutTo(w: Float, h: Float): () -> Unit =
        { setBoundsKeepingOffsets(w * zoom, h * zoom) }

    private fun ViewportState.setBoundsKeepingOffsets(cw: Float, ch: Float) =
        setBounds(100f, 100f, cw, ch)

    @Test fun `zoomTo clamps to the zoom range`() {
        val v = viewport()
        assertTrue(v.zoomTo(100f, v.relayoutTo(200f, 400f)))
        assertEquals(ViewportState.MAX_ZOOM, v.zoom, 0f)
        assertFalse(v.zoomTo(100f, v.relayoutTo(200f, 400f)))
        assertTrue(v.zoomTo(0f, v.relayoutTo(200f, 400f)))
        assertEquals(ViewportState.MIN_ZOOM, v.zoom, 0f)
    }

    @Test fun `zoomAbout keeps the focus point fixed`() {
        val v = viewport()
        v.scrollToY(100f)
        // The content point under viewport y=50 is 150 of 400 — 0.375 of the way down.
        v.zoomAbout(0f, 50f, 2f, v.relayoutTo(200f, 400f))
        assertEquals(2f, v.zoom, 0f)
        assertEquals(0.375f * 800f - 50f, v.scrollY, 0.001f)
    }

    @Test fun `zoomTo keeps the viewport centre fixed`() {
        val v = viewport()
        v.scrollToY(100f)
        // Centre (y=50) sits at content 150/400; after 2× it should stay centred on that fraction.
        v.zoomTo(2f, v.relayoutTo(200f, 400f))
        assertEquals(0.375f * 800f - 50f, v.scrollY, 0.001f)
    }

    @Test fun `reset returns to the top-left at 100 percent`() {
        val v = viewport()
        v.scrollBy(50f, 50f)
        v.zoomTo(2f, v.relayoutTo(200f, 400f))
        v.reset()
        assertEquals(0f, v.scrollX, 0f)
        assertEquals(0f, v.scrollY, 0f)
        assertEquals(1f, v.zoom, 0f)
    }
}
