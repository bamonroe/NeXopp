package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {

    private fun stroke(vararg pts: Pair<Double, Double>) =
        Stroke(Tool.PEN, 0xFF000000.toInt(), "round", pts.map { StrokePoint(it.first, it.second, 0.0) }, false)

    private fun page(vararg layers: Layer) =
        Page(200.0, 200.0, Background.Solid(0xFFFFFFFF.toInt(), "plain"), layers.toList())

    // A page with three strokes on one layer: near, far, and a corner-crosser.
    private val near = stroke(10.0 to 10.0, 30.0 to 30.0)      // inside a 0..50 rect
    private val far = stroke(100.0 to 100.0, 120.0 to 120.0)   // outside a 0..50 rect
    private val straddle = stroke(40.0 to 40.0, 80.0 to 80.0)  // crosses the rect edge
    private val p = page(Layer(listOf(near, far, straddle)))

    @Test fun inRectSelectsOnlyFullyEnclosedElements() {
        val refs = SelectionTester.inRect(p, Bounds(0.0, 0.0, 50.0, 50.0))
        assertEquals(setOf(ElementRef(0, 0)), refs) // only `near`
    }

    @Test fun inRectAcrossLayers() {
        val two = page(Layer(listOf(near)), Layer(listOf(far)))
        val all = SelectionTester.inRect(two, Bounds(0.0, 0.0, 200.0, 200.0))
        assertEquals(setOf(ElementRef(0, 0), ElementRef(1, 0)), all)
    }

    @Test fun pickTopmostReturnsLastDrawnAtPoint() {
        // Two overlapping images; the later one wins.
        val lo = ImageElement(0.0, 0.0, 50.0, 50.0, ByteArray(0))
        val hi = ImageElement(0.0, 0.0, 50.0, 50.0, byteArrayOf(1))
        val pg = page(Layer(listOf(lo, hi)))
        assertEquals(ElementRef(0, 1), SelectionTester.pickTopmost(pg, 25.0, 25.0))
    }

    @Test fun pickTopmostMissReturnsNull() {
        assertNull(SelectionTester.pickTopmost(p, 500.0, 500.0))
    }

    @Test fun boundsOfUnionsSelectedElements() {
        val refs = setOf(ElementRef(0, 0), ElementRef(0, 1))
        val b = SelectionTester.boundsOf(p, refs)!!
        assertEquals(10.0, b.left, 1e-9)
        assertEquals(10.0, b.top, 1e-9)
        assertEquals(120.0, b.right, 1e-9)
        assertEquals(120.0, b.bottom, 1e-9)
    }

    @Test fun translateMovesOnlySelectedElements() {
        val moved = SelectionOps.translate(listOf(p), 0, setOf(ElementRef(0, 0)), 5.0, 7.0)
        val layer = moved[0].layers[0]
        val movedStroke = layer.elements[0] as Stroke
        assertEquals(15.0, movedStroke.points[0].x, 1e-9)
        assertEquals(17.0, movedStroke.points[0].y, 1e-9)
        // `far` (index 1) is untouched.
        assertEquals(far, layer.elements[1])
    }

    @Test fun translateTextElement() {
        val t = TextElement("Sans", 10.0, 100.0, 50.0, 0xFF000000.toInt(), "hi")
        val moved = SelectionOps.translate(t, -10.0, 20.0) as TextElement
        assertEquals(90.0, moved.x, 1e-9)
        assertEquals(70.0, moved.y, 1e-9)
    }

    @Test fun deleteRemovesSelectedAndKeepsOrder() {
        val pages = SelectionOps.delete(listOf(p), 0, setOf(ElementRef(0, 1))) // remove `far`
        val kept = pages[0].layers[0].elements
        assertEquals(listOf<Any>(near, straddle), kept)
    }

    @Test fun deleteAcrossIndicesIsPositionStable() {
        // Removing indices 0 and 2 leaves only index 1.
        val pages = SelectionOps.delete(listOf(p), 0, setOf(ElementRef(0, 0), ElementRef(0, 2)))
        assertEquals(listOf<Any>(far), pages[0].layers[0].elements)
    }

    @Test fun emptySelectionOpsReturnTheSameList() {
        val pages = listOf(p)
        assertTrue(SelectionOps.translate(pages, 0, emptySet(), 5.0, 5.0) === pages)
        assertTrue(SelectionOps.delete(pages, 0, emptySet()) === pages)
    }
}
