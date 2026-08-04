package com.xopp.android.render

import com.xopp.android.format.model.LineStyle
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry tests for the partial (segment-splitting) eraser. */
class StrokeEraserTest {

    private fun stroke(vararg xs: Double) = Stroke(
        Tool.PEN, 0xFF112233.toInt(), "round",
        xs.map { StrokePoint(it, 0.0, 1.0) }, uniformWidth = true,
        lineStyle = LineStyle.DASHED, fill = 128,
    )

    @Test fun missReturnsNull() {
        val s = stroke(0.0, 1.0, 2.0, 3.0)
        assertNull(StrokeEraser.erase(s, 100.0, 100.0, radius = 1.0))
    }

    @Test fun touchingTheMiddleSplitsIntoTwoPieces() {
        // Vertices at x = 0,1,2,3,4; erase around x=2. Reach is radius + half-width = 0.9, so the
        // cut lands on the disc boundary at x = 1.1 and x = 2.9.
        val s = stroke(0.0, 1.0, 2.0, 3.0, 4.0)
        val pieces = StrokeEraser.erase(s, 2.0, 0.0, radius = 0.4)!!
        assertEquals(2, pieces.size)
        assertEquals(listOf(0.0, 1.0, 1.1), pieces[0].points.map { round(it.x) })
        assertEquals(listOf(2.9, 3.0, 4.0), pieces[1].points.map { round(it.x) })
    }

    @Test fun touchingAnEndTrimsTheStroke() {
        val s = stroke(0.0, 1.0, 2.0, 3.0)
        val pieces = StrokeEraser.erase(s, 0.0, 0.0, radius = 0.4)!!
        assertEquals(1, pieces.size)
        assertEquals(listOf(0.9, 1.0, 2.0, 3.0), pieces[0].points.map { round(it.x) })
    }

    @Test fun rubbingTheShaftOfATwoPointLineSplitsIt() {
        // A shape-tool line has only its two endpoints — the old vertex-only test missed it entirely.
        val s = stroke(0.0, 300.0)
        val pieces = StrokeEraser.erase(s, 150.0, 0.0, radius = 9.5)!!
        assertEquals(2, pieces.size)
        assertEquals(listOf(0.0, 140.0), pieces[0].points.map { round(it.x) })
        assertEquals(listOf(160.0, 300.0), pieces[1].points.map { round(it.x) })
    }

    @Test fun rubbingBesideATwoPointLineLeavesItAlone() {
        val s = stroke(0.0, 300.0)
        assertNull(StrokeEraser.erase(s, 150.0, 50.0, radius = 9.5))
    }

    @Test fun rubbingPastAnEndpointLeavesTheLineAlone() {
        // The disc sits beyond the far end: the infinite line through the segment passes through it,
        // but the segment itself does not.
        val s = stroke(0.0, 300.0)
        assertNull(StrokeEraser.erase(s, 400.0, 0.0, radius = 9.5))
    }

    @Test fun rubbingAnEdgeOfASparseRectangleCutsOnlyThatEdge() {
        // A shape-tool rectangle: 4 corners plus the closing point, erased mid-edge on the top side.
        val corners = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0, 0.0 to 0.0)
        val rect = Stroke(
            Tool.PEN, 0xFF000000.toInt(), "round",
            corners.map { StrokePoint(it.first, it.second, 1.0) }, uniformWidth = true,
        )
        val pieces = StrokeEraser.erase(rect, 50.0, 0.0, radius = 9.5)!!
        assertEquals(2, pieces.size)
        assertEquals(listOf(0.0, 40.0), pieces[0].points.map { round(it.x) })
        // The rest of the outline survives as one run, starting just past the erased gap.
        assertEquals(60.0, round(pieces[1].points.first().x), 0.001)
        assertEquals(5, pieces[1].points.size)
    }

    private fun round(v: Double) = Math.round(v * 1000.0) / 1000.0

    @Test fun aLoneSurvivingVertexIsDropped() {
        // Erase around x=1 on a 3-vertex stroke: leaves single vertices at each end → nothing drawable.
        val s = stroke(0.0, 1.0, 2.0)
        val pieces = StrokeEraser.erase(s, 1.0, 0.0, radius = 1.2)!!
        assertTrue(pieces.isEmpty())
    }

    @Test fun erasingEverythingYieldsNoPieces() {
        val s = stroke(0.0, 1.0, 2.0, 3.0)
        val pieces = StrokeEraser.erase(s, 1.5, 0.0, radius = 10.0)!!
        assertTrue(pieces.isEmpty())
    }

    @Test fun piecesInheritTheOriginalStyleAndColour() {
        val s = stroke(0.0, 1.0, 2.0, 3.0, 4.0)
        val piece = StrokeEraser.erase(s, 2.0, 0.0, radius = 0.4)!!.first()
        assertEquals(0xFF112233.toInt(), piece.color)
        assertEquals(LineStyle.DASHED, piece.lineStyle)
        assertEquals(128, piece.fill)
        assertEquals(Tool.PEN, piece.tool)
    }
}
