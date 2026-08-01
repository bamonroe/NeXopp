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
        // Vertices at x = 0,1,2,3,4; erase around x=2 removes the middle vertex.
        val s = stroke(0.0, 1.0, 2.0, 3.0, 4.0)
        val pieces = StrokeEraser.erase(s, 2.0, 0.0, radius = 0.4)!!
        assertEquals(2, pieces.size)
        assertEquals(listOf(0.0, 1.0), pieces[0].points.map { it.x })
        assertEquals(listOf(3.0, 4.0), pieces[1].points.map { it.x })
    }

    @Test fun touchingAnEndTrimsTheStroke() {
        val s = stroke(0.0, 1.0, 2.0, 3.0)
        val pieces = StrokeEraser.erase(s, 0.0, 0.0, radius = 0.4)!!
        assertEquals(1, pieces.size)
        assertEquals(listOf(1.0, 2.0, 3.0), pieces[0].points.map { it.x })
    }

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
