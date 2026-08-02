package com.xopp.android.render

import com.xopp.android.format.model.ImageElement
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.TexImageElement
import com.xopp.android.format.model.TextElement
import com.xopp.android.format.model.Tool
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

    @Test fun translateAndUnion() {
        val a = Bounds(0.0, 0.0, 10.0, 10.0).translate(5.0, -2.0)
        assertEquals(Bounds(5.0, -2.0, 15.0, 8.0), a)
        val u = Bounds(0.0, 0.0, 10.0, 10.0).union(Bounds(20.0, 5.0, 30.0, 40.0))
        assertEquals(Bounds(0.0, 0.0, 30.0, 40.0), u)
    }
}
