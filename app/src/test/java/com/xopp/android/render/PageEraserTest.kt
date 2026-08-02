package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
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

    @Test fun untouchedLayersKeepTheirIdentity() {
        val keep = Layer(listOf(stroke(80.0, 90.0)), "ink")
        val p = page(keep, Layer(listOf(stroke(0.0, 10.0, 20.0))))
        val out = PageEraser.erase(p, 10.0, 0.0, 2.0, EraserMode.WHOLE_STROKE)!!
        assertTrue(out.layers[0] === keep)
    }

    @Test fun eraserSizesAreOrderedFineToThick() {
        assertTrue(EraserSize.FINE.radiusPt < EraserSize.MEDIUM.radiusPt)
        assertTrue(EraserSize.MEDIUM.radiusPt < EraserSize.THICK.radiusPt)
    }
}
