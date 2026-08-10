package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEraserTest {

    private fun stroke(vararg xs: Double): Stroke =
        Stroke(Tool.PEN, 0xFF000000.toInt(), "round", xs.map { StrokePoint(it, 0.0, 1.0) }, false)

    private fun page(vararg layers: Layer): Page =
        Page(100.0, 100.0, Background.Solid(0xFFFFFFFF.toInt(), "plain"), layers.toList())

    @Test fun aMissLeavesThePageAlone() {
        val p = page(Layer(listOf(stroke(0.0, 10.0))))
        assertNull(PageEraser.erase(p, 90.0, 90.0, 5.0, EraserMode.STANDARD))
    }

    @Test fun standardModeSplitsTheTouchedStroke() {
        val p = page(Layer(listOf(stroke(0.0, 5.0, 10.0, 15.0, 20.0))))
        val out = PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.STANDARD)!!
        assertEquals(2, out.layers[0].elements.size)
    }

    @Test fun wholeStrokeModeDeletesTheTouchedStroke() {
        val p = page(Layer(listOf(stroke(0.0, 10.0, 20.0), stroke(80.0, 90.0))))
        val out = PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.WHOLE_STROKE)!!
        assertEquals(1, out.layers[0].elements.size)
        assertEquals(80.0, (out.layers[0].elements[0] as Stroke).points[0].x, 1e-9)
    }

    @Test fun hiddenLayersAreNeverErased() {
        val p = page(Layer(listOf(stroke(0.0, 10.0, 20.0))), Layer(listOf(stroke(0.0, 10.0, 20.0))))
        val out = PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.WHOLE_STROKE, hiddenLayers = setOf(0))!!
        assertEquals(1, out.layers[0].elements.size)
        assertEquals(0, out.layers[1].elements.size)
    }

    @Test fun anAllHiddenPageReportsNoChange() {
        val p = page(Layer(listOf(stroke(0.0, 10.0, 20.0))))
        assertNull(PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.STANDARD, hiddenLayers = setOf(0)))
    }

    @Test fun onlyTheSelectedLayerIsErased() {
        val p = page(Layer(listOf(stroke(0.0, 10.0, 20.0))), Layer(listOf(stroke(0.0, 10.0, 20.0))))
        val out = PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.WHOLE_STROKE, onlyLayer = 1)!!
        assertEquals(1, out.layers[0].elements.size)
        assertEquals(0, out.layers[1].elements.size)
    }

    @Test fun aHitOnAnUnselectedLayerReportsNoChange() {
        val p = page(Layer(listOf(stroke(0.0, 10.0, 20.0))), Layer(emptyList()))
        assertNull(PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.STANDARD, onlyLayer = 1))
    }

    @Test fun untouchedLayersKeepTheirIdentity() {
        val keep = Layer(listOf(stroke(80.0, 90.0)), "ink")
        val p = page(keep, Layer(listOf(stroke(0.0, 10.0, 20.0))))
        val out = PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.WHOLE_STROKE)!!
        assertTrue(out.layers[0] === keep)
    }

    /** The eraser has no size scheme of its own: a thicker pen slot must mean a wider rubber. */
    @Test fun eraserTipGrowsWithThePenTip() {
        assertTrue(eraserRadiusPt(0.85f) < eraserRadiusPt(1.5f))
        assertTrue(eraserRadiusPt(1.5f) < eraserRadiusPt(2.6f))
    }

    /** …and it is always wider than the ink it rubs out, however hair-thin the pen slot is. */
    @Test fun eraserTipStaysAimableForAThinPen() {
        assertTrue(eraserRadiusPt(0.1f) >= ERASER_RADIUS_MIN_PT)
        // The radius is the pen's full width, i.e. twice the ink's own radius.
        assertEquals(1.5, eraserRadiusPt(1.5f), 1e-9)
    }
}
