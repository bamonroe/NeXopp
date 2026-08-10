package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.RawElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

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
        assertTrue(SelectionOps.scale(pages, 0, emptySet(), 2.0, 0.0, 0.0) === pages)
        assertTrue(SelectionOps.rotate(pages, 0, emptySet(), 1.0, 0.0, 0.0) === pages)
        assertTrue(SelectionOps.restyle(pages, 0, emptySet(), 0xFFFF0000.toInt(), 3.0) === pages)
        // A no-op factor / angle / all-null restyle is also identity.
        assertSame(pages, SelectionOps.scale(pages, 0, setOf(ElementRef(0, 0)), 1.0, 0.0, 0.0))
        assertSame(pages, SelectionOps.rotate(pages, 0, setOf(ElementRef(0, 0)), 0.0, 0.0, 0.0))
        assertSame(pages, SelectionOps.restyle(pages, 0, setOf(ElementRef(0, 0)), null, null))
    }

    // --- resize (affine scale about an anchor) ---------------------------------------------------

    private fun wide(w: Double, vararg pts: Pair<Double, Double>) =
        Stroke(Tool.PEN, 0xFF000000.toInt(), "round", pts.map { StrokePoint(it.first, it.second, w) }, true)

    @Test fun scaleGrowsStrokeAboutAnchorAndScalesWidth() {
        val s = wide(2.0, 10.0 to 10.0, 20.0 to 20.0)
        val pg = page(Layer(listOf(s)))
        // Double about the anchor (0,0): coords and width both double.
        val out = SelectionOps.scale(listOf(pg), 0, setOf(ElementRef(0, 0)), 2.0, 0.0, 0.0)
        val r = out[0].layers[0].elements[0] as Stroke
        assertEquals(20.0, r.points[0].x, 1e-9)
        assertEquals(40.0, r.points[1].y, 1e-9)
        assertEquals(4.0, r.points[0].width, 1e-9)
    }

    @Test fun scaleKeepsAnchorCornerFixed() {
        // Scaling about (100,100) leaves a vertex sitting on the anchor unmoved.
        val s = wide(1.0, 100.0 to 100.0, 60.0 to 60.0)
        val pg = page(Layer(listOf(s)))
        val out = SelectionOps.scale(listOf(pg), 0, setOf(ElementRef(0, 0)), 0.5, 100.0, 100.0)
        val r = out[0].layers[0].elements[0] as Stroke
        assertEquals(100.0, r.points[0].x, 1e-9)
        assertEquals(80.0, r.points[1].x, 1e-9) // 100 + (60-100)*0.5
    }

    @Test fun scaleTextScalesFontSize() {
        val t = TextElement("Sans", 10.0, 20.0, 20.0, 0xFF000000.toInt(), "hi")
        val out = SelectionOps.scale(listOf(page(Layer(listOf(t)))), 0, setOf(ElementRef(0, 0)), 3.0, 0.0, 0.0)
        val r = out[0].layers[0].elements[0] as TextElement
        assertEquals(30.0, r.size, 1e-9)
        assertEquals(60.0, r.x, 1e-9)
    }

    // --- rotate (strokes only) -------------------------------------------------------------------

    @Test fun rotateStroke90AboutOrigin() {
        val s = stroke(10.0 to 0.0, 0.0 to 10.0)
        val out = SelectionOps.rotate(listOf(page(Layer(listOf(s)))), 0, setOf(ElementRef(0, 0)), PI / 2, 0.0, 0.0)
        val r = out[0].layers[0].elements[0] as Stroke
        // (10,0) -> (0,10); (0,10) -> (-10,0) for a +90° (x→y, y→-x in y-down space)
        assertEquals(0.0, r.points[0].x, 1e-9)
        assertEquals(10.0, r.points[0].y, 1e-9)
        assertEquals(-10.0, r.points[1].x, 1e-9)
        assertEquals(0.0, r.points[1].y, 1e-9)
    }

    @Test fun rotateLeavesNonStrokeElementsUntouched() {
        val img = ImageElement(0.0, 0.0, 10.0, 10.0, ByteArray(0))
        val out = SelectionOps.rotate(img, PI / 3, 5.0, 5.0)
        assertSame(img, out) // no rotation representation in .xopp -> unchanged
    }

    // --- restyle (colour / width) ----------------------------------------------------------------

    @Test fun restyleRecoloursAndReWidthsStroke() {
        val s = wide(1.0, 0.0 to 0.0, 5.0 to 5.0)
        val out = SelectionOps.restyle(listOf(page(Layer(listOf(s)))), 0, setOf(ElementRef(0, 0)), 0xFFFF0000.toInt(), 4.0)
        val r = out[0].layers[0].elements[0] as Stroke
        assertEquals(0xFFFF0000.toInt(), r.color)
        assertTrue(r.uniformWidth)
        assertTrue(r.points.all { it.width == 4.0 })
    }

    @Test fun restyleColoursTextButNotImage() {
        val t = SelectionOps.restyle(TextElement("Sans", 10.0, 0.0, 0.0, 0xFF000000.toInt(), "x"), 0xFF00FF00.toInt(), null) as TextElement
        assertEquals(0xFF00FF00.toInt(), t.color)
        val img = ImageElement(0.0, 0.0, 1.0, 1.0, ByteArray(0))
        assertSame(img, SelectionOps.restyle(img, 0xFF00FF00.toInt(), 5.0))
    }

    // --- clipboard: elementsAt / addToTopLayer / moveToPage --------------------------------------

    @Test fun elementsAtResolvesInStableOrder() {
        val els = SelectionOps.elementsAt(p, setOf(ElementRef(0, 2), ElementRef(0, 0)))
        assertEquals(listOf<Any>(near, straddle), els) // sorted by index: 0 then 2
    }

    @Test fun addToTopLayerAppendsAndReportsRefs() {
        val extra = stroke(1.0 to 1.0)
        val (pages, refs) = SelectionOps.addToTopLayer(listOf(p), 0, listOf(extra))
        assertEquals(4, pages[0].layers[0].elements.size)
        assertEquals(setOf(ElementRef(0, 3)), refs)
        assertSame(extra, pages[0].layers[0].elements[3])
    }

    @Test fun moveToPageRemovesFromSourceAndAppendsToTarget() {
        val two = listOf(page(Layer(listOf(near, far))), page(Layer(emptyList())))
        // Shift by (100,0) with unit scale onto page 1.
        val (pages, refs) = SelectionOps.moveToPage(two, 0, 1, setOf(ElementRef(0, 0)), 1.0, 100.0, 0.0)
        assertEquals(1, pages[0].layers[0].elements.size)       // `near` left page 0
        assertEquals(setOf(ElementRef(0, 0)), refs)             // landed as page 1's first element
        val moved = pages[1].layers[0].elements[0] as Stroke
        assertEquals(110.0, moved.points[0].x, 1e-9)            // 10 + 100
    }

    // --- lasso (polygon) select ------------------------------------------------------------------

    @Test fun inPolygonSelectsWhollyEnclosed() {
        // A right triangle x+y<=70 in the top-left: near (max corner sum 60) is wholly inside;
        // straddle (corner sums >=80) and far are not.
        val triangle = listOf(Vec2(0.0, 0.0), Vec2(70.0, 0.0), Vec2(0.0, 70.0))
        val refs = SelectionTester.inPolygon(p, triangle)
        assertEquals(setOf(ElementRef(0, 0)), refs)
    }

    @Test fun inPolygonSelectsDiagonalStrokeWhoseBoxCornersFallOutside() {
        // A tight sleeve around the diagonal (10,10)-(30,30). The stroke's box corners (10,30) and
        // (30,10) sit outside this polygon, so the old corner rule missed it.
        val sleeve = listOf(Vec2(5.0, 12.0), Vec2(27.0, 34.0), Vec2(34.0, 27.0), Vec2(12.0, 5.0))
        assertEquals(setOf(ElementRef(0, 0)), SelectionTester.inPolygon(p, sleeve))
    }

    @Test fun inPolygonRejectsStrokeWithAPointOutside() {
        // `straddle` runs (40,40)-(80,80); this box holds only its first point.
        val box = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 60.0), Vec2(0.0, 60.0))
        assertEquals(setOf(ElementRef(0, 0)), SelectionTester.inPolygon(p, box))
    }

    @Test fun inPolygonSelectsImageByItsCorners() {
        val img = ImageElement(10.0, 10.0, 30.0, 30.0, ByteArray(0))
        val one = page(Layer(listOf(img)))
        val box = listOf(Vec2(0.0, 0.0), Vec2(50.0, 0.0), Vec2(50.0, 50.0), Vec2(0.0, 50.0))
        assertEquals(setOf(ElementRef(0, 0)), SelectionTester.inPolygon(one, box))
        val tight = listOf(Vec2(0.0, 0.0), Vec2(20.0, 0.0), Vec2(20.0, 20.0), Vec2(0.0, 20.0))
        assertTrue(SelectionTester.inPolygon(one, tight).isEmpty())
    }

    @Test fun inPolygonIgnoresEmptyStroke() {
        val one = page(Layer(listOf(stroke())))
        val box = listOf(Vec2(0.0, 0.0), Vec2(50.0, 0.0), Vec2(50.0, 50.0), Vec2(0.0, 50.0))
        assertTrue(SelectionTester.inPolygon(one, box).isEmpty())
    }

    // An unmodelled element gets an empty box at (0,0); no gesture over the page corner may pick it.
    @Test fun unmodelledElementIsNeverSelected() {
        val one = page(Layer(listOf(RawElement("vendor:thing"), stroke())))
        val box = listOf(Vec2(-5.0, -5.0), Vec2(50.0, -5.0), Vec2(50.0, 50.0), Vec2(-5.0, 50.0))
        assertTrue(SelectionTester.inPolygon(one, box).isEmpty())
        assertTrue(SelectionTester.inRect(one, Bounds(-5.0, -5.0, 50.0, 50.0)).isEmpty())
        assertNull(SelectionTester.pickTopmost(one, 0.0, 0.0))
    }

    @Test fun inPolygonDegenerateSelectsNothing() {
        assertTrue(SelectionTester.inPolygon(p, listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0))).isEmpty())
    }
}
