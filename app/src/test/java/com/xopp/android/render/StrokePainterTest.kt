package com.xopp.android.render

import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic tests for the highlighter/pen rendering split (no Canvas needed). */
class StrokePainterTest {

    @Test fun highlighterForcesTranslucentWhenStoredOpaque() {
        val out = StrokePainter.renderColor(Tool.HIGHLIGHTER, 0xFFFF0000.toInt())
        assertEquals(0x80, out ushr 24)          // ~50% alpha
        assertEquals(0xFF0000, out and 0x00FFFFFF) // colour preserved
    }

    @Test fun highlighterKeepsAnAlreadyTranslucentColour() {
        val stored = 0x40123456
        assertEquals(stored, StrokePainter.renderColor(Tool.HIGHLIGHTER, stored))
    }

    @Test fun penColourIsLeftUntouched() {
        val stored = 0xFF112233.toInt()
        assertEquals(stored, StrokePainter.renderColor(Tool.PEN, stored))
    }

    @Test fun bandWidthIsTheMeanVertexWidth() {
        val pts = listOf(
            StrokePoint(0.0, 0.0, 8.0),
            StrokePoint(1.0, 0.0, 10.0),
            StrokePoint(2.0, 0.0, 12.0),
        )
        assertEquals(10.0, StrokePainter.bandWidth(pts), 1e-9)
    }

    @Test fun bandWidthOfEmptyIsZero() {
        assertEquals(0.0, StrokePainter.bandWidth(emptyList()), 1e-9)
    }
}
