package com.nexopp.render

import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementBoundsTest {

    private fun stroke(vararg pts: Triple<Double, Double, Double>) =
        Stroke(Tool.PEN, 0xFF000000.toInt(), "round", pts.map { StrokePoint(it.first, it.second, it.third) }, false)

    @Test fun strokeBoundsGrowByHalfWidth() {
        // Segment from (10,10) to (20,20), width 2 -> half-width 1 on every side.
        val b = ElementBounds.of(stroke(Triple(10.0, 10.0, 2.0), Triple(20.0, 20.0, 2.0)))
        assertEquals(9.0, b.left, 1e-9)
        assertEquals(9.0, b.top, 1e-9)
        assertEquals(21.0, b.right, 1e-9)
        assertEquals(21.0, b.bottom, 1e-9)
    }

    @Test fun imageAndTexBoundsAreTheBox() {
        val img = ImageElement(5.0, 6.0, 25.0, 40.0, ByteArray(0))
        assertEquals(Bounds(5.0, 6.0, 25.0, 40.0), ElementBounds.of(img))
        val tex = TexImageElement(1.0, 2.0, 3.0, 4.0, "x^2", 0xFF000000.toInt())
        assertEquals(Bounds(1.0, 2.0, 3.0, 4.0), ElementBounds.of(tex))
    }

    @Test fun textBoundsAnchorAtTopLeftAndGrowWithContent() {
        val t = TextElement("Sans", 10.0, 100.0, 50.0, 0xFF000000.toInt(), "ab\ncd")
        val b = ElementBounds.of(t)
        assertEquals(100.0, b.left, 1e-9)
        assertEquals(50.0, b.top, 1e-9)
        assertTrue(b.right > b.left)
        assertTrue(b.bottom > b.top)
    }

    @Test fun containedByAndContains() {
        val outer = Bounds(0.0, 0.0, 100.0, 100.0)
        assertTrue(Bounds(10.0, 10.0, 20.0, 20.0).containedBy(outer))
        assertFalse(Bounds(90.0, 90.0, 110.0, 110.0).containedBy(outer))
        assertTrue(outer.contains(50.0, 50.0))
        assertFalse(outer.contains(150.0, 50.0))
    }

    /** The viewport cull in [PageRenderer] keeps an element only when its box intersects the view. */
    @Test fun intersectsIsOverlapNotContainment() {
        val view = Bounds(0.0, 0.0, 100.0, 100.0)
        assertTrue(view.intersects(Bounds(90.0, 90.0, 200.0, 200.0))) // straddles a corner
        assertTrue(view.intersects(Bounds(-50.0, -50.0, 500.0, 500.0))) // swallows the view
        assertTrue(view.intersects(Bounds(100.0, 50.0, 120.0, 60.0))) // touching edge
        assertFalse(view.intersects(Bounds(100.5, 50.0, 120.0, 60.0))) // just off the right
        assertFalse(view.intersects(Bounds(10.0, -40.0, 20.0, -0.5))) // just above
    }

    /**
     * A rotated stroke's box is recomputed from the baked-in points, not carried over: rotating a
     * wide horizontal segment 90 degrees about its own centre swaps its extents, and the half-width
     * inflation still applies on all four sides.
     */
    @Test fun rotatedStrokeBoundsFollowThePoints() {
        val s = stroke(Triple(0.0, 50.0, 4.0), Triple(100.0, 50.0, 4.0))
        val before = ElementBounds.of(s)
        assertEquals(Bounds(-2.0, 48.0, 102.0, 52.0), before)
        val r = SelectionOps.rotate(s, Math.PI / 2, 50.0, 50.0)
        val after = ElementBounds.of(r)
        assertEquals(48.0, after.left, 1e-6)
        assertEquals(-2.0, after.top, 1e-6)
        assertEquals(52.0, after.right, 1e-6)
        assertEquals(102.0, after.bottom, 1e-6)
        // Area is preserved by a rigid rotation of an axis-aligned segment.
        assertEquals(before.width * before.height, after.width * after.height, 1e-6)
    }

    /**
     * The selection outline and the vertical-space grab line read the same top edge — both go
     * through [ElementBounds], so a fat stroke's ink-inflated top wins over its raw vertex y.
     */
    @Test fun strokeWidthInflationAgreesAcrossSelectionAndVerticalSpace() {
        val fat = stroke(Triple(10.0, 100.0, 20.0), Triple(30.0, 100.0, 20.0))
        val page = com.nexopp.format.model.Page(
            200.0, 400.0,
            com.nexopp.format.model.Background.Solid(0xFFFFFFFF.toInt(), "plain"),
            listOf(com.nexopp.format.model.Layer(listOf(fat))),
        )
        val selBounds = SelectionTester.boundsOf(page, setOf(ElementRef(0, 0)))!!
        assertEquals(90.0, selBounds.top, 1e-9) // 100 - half of 20
        // A grab line at 85pt sits above the inflated top (90) but below the raw vertex y (100), so
        // the stroke counts as below the line only because both sides inflate by the half-width.
        val shifted = VerticalSpaceOps.shiftBelow(listOf(page), 0, 85.0, 30.0)
        val moved = shifted[0].layers[0].elements[0]
        assertEquals(120.0, ElementBounds.of(moved).top, 1e-9)
        // A pull-up can only close the 5pt gap between the inflated top and the grab line.
        assertEquals(-5.0, VerticalSpaceOps.clampShift(listOf(page), 0, 85.0, -50.0), 1e-9)
        // A line at 95pt is already inside the ink, so the stroke stays put rather than tearing.
        assertEquals(listOf(page), VerticalSpaceOps.shiftBelow(listOf(page), 0, 95.0, 30.0))
    }

    /** Every hit test shares one pad, so a tap picks the same element whichever path handles it. */
    @Test fun textTapPadIsSharedWithElementEdits() {
        val t = TextElement("Sans", 10.0, 100.0, 50.0, 0xFF000000.toInt(), "ab")
        val b = ElementBounds.of(t).expand(ElementBounds.TAP_PAD)
        assertTrue(ElementEdits.hitsText(t, b.left + 0.1, b.top + 0.1))
        assertFalse(ElementEdits.hitsText(t, b.left - 0.1, b.top + 0.1))
    }

    @Test fun translateAndUnion() {
        val a = Bounds(0.0, 0.0, 10.0, 10.0).translate(5.0, -2.0)
        assertEquals(Bounds(5.0, -2.0, 15.0, 8.0), a)
        val u = Bounds(0.0, 0.0, 10.0, 10.0).union(Bounds(20.0, 5.0, 30.0, 40.0))
        assertEquals(Bounds(0.0, 0.0, 30.0, 40.0), u)
    }
}
