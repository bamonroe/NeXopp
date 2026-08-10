package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VerticalSpaceOpsTest {

    private fun stroke(top: Double, bottom: Double = top + 10.0) = Stroke(
        Tool.PEN, 0, "round",
        listOf(StrokePoint(5.0, top, 1.0), StrokePoint(15.0, bottom, 1.0)),
        uniformWidth = true,
    )

    private fun page(vararg layers: Layer) =
        Page(200.0, 400.0, Background.Solid(0xFFFFFFFF.toInt(), "plain"), layers.toList())

    private fun topsOf(page: Page): List<Double> =
        page.layers.flatMap { it.elements }.map { ElementBounds.of(it).top }

    @Test fun insertsSpaceOnlyBelowTheGrabLine() {
        val pages = listOf(page(Layer(listOf(stroke(20.0), stroke(120.0)))))
        val out = VerticalSpaceOps.shiftBelow(pages, 0, yPt = 100.0, dy = 50.0)
        assertEquals("stroke above the line stays put", 19.5, topsOf(out[0])[0], 1e-6)
        assertEquals("stroke below the line moves down", 169.5, topsOf(out[0])[1], 1e-6)
    }

    @Test fun straddlingElementStaysPutRatherThanBeingTorn() {
        // The line at y=25 passes through a stroke spanning 20..30; its top is above, so it holds.
        val pages = listOf(page(Layer(listOf(stroke(20.0, 30.0)))))
        val out = VerticalSpaceOps.shiftBelow(pages, 0, yPt = 25.0, dy = 40.0)
        assertSame("no element moved, so the page list is untouched", pages, out)
    }

    @Test fun everyLayerMovesTogether() {
        val pages = listOf(page(Layer(listOf(stroke(150.0))), Layer(listOf(TextElement("Sans", 12.0, 8.0, 200.0, 0, "hi")))))
        val out = VerticalSpaceOps.shiftBelow(pages, 0, yPt = 100.0, dy = 25.0)
        assertEquals(listOf(174.5, 225.0), topsOf(out[0]))
    }

    @Test fun removingSpaceStopsAtTheGrabLine() {
        val pages = listOf(page(Layer(listOf(stroke(140.0)))))
        // Asking for -80 would drag the stroke's top (139.5) above the line at 100.
        assertEquals(-39.5, VerticalSpaceOps.clampShift(pages, 0, yPt = 100.0, dy = -80.0), 1e-6)
        val out = VerticalSpaceOps.shiftBelow(pages, 0, yPt = 100.0, dy = -80.0)
        assertEquals("pulled up exactly to the line", 100.0, topsOf(out[0])[0], 1e-6)
    }

    @Test fun pullingUpWithNothingBelowIsANoOp() {
        val pages = listOf(page(Layer(listOf(stroke(20.0)))))
        assertSame(pages, VerticalSpaceOps.shiftBelow(pages, 0, yPt = 300.0, dy = -50.0))
    }

    @Test fun zeroDragAndBadPageIndexAreNoOps() {
        val pages = listOf(page(Layer(listOf(stroke(120.0)))))
        assertSame(pages, VerticalSpaceOps.shiftBelow(pages, 0, yPt = 50.0, dy = 0.0))
        assertSame(pages, VerticalSpaceOps.shiftBelow(pages, 7, yPt = 50.0, dy = 30.0))
    }
}
