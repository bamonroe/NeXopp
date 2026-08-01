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

/** Pure tests for the layer-management operations. */
class LayerOpsTest {

    private fun dot(x: Double) = Stroke(Tool.PEN, 0, "round", listOf(StrokePoint(x, 0.0, 1.0)), true)
    private fun page(vararg layers: Layer) =
        Page(100.0, 100.0, Background.Solid(0, "plain"), layers.toList())

    @Test fun addAppendsAnEmptyTopLayer() {
        val p = page(Layer(listOf(dot(1.0))))
        val (out, idx) = LayerOps.add(p)
        assertEquals(2, out.layers.size)
        assertEquals(1, idx)
        assertTrue(out.layers[1].elements.isEmpty())
        assertEquals(1, out.layers[0].elements.size) // existing content untouched
    }

    @Test fun removeKeepsAtLeastOneLayer() {
        val single = page(Layer(listOf(dot(1.0))))
        assertEquals(single, LayerOps.remove(single, 0)) // refuses to drop the last layer

        val two = page(Layer(listOf(dot(1.0))), Layer(listOf(dot(2.0))))
        val out = LayerOps.remove(two, 0)
        assertEquals(1, out.layers.size)
        assertEquals(2.0, (out.layers[0].elements[0] as Stroke).points[0].x, 1e-9)
    }

    @Test fun renameSetsAndClears() {
        val p = page(Layer(emptyList()))
        assertEquals("Sketch", LayerOps.rename(p, 0, "Sketch").layers[0].name)
        assertNull(LayerOps.rename(p, 0, "   ").layers[0].name)
    }

    @Test fun moveReordersLayers() {
        val p = page(Layer(listOf(dot(0.0)), "a"), Layer(listOf(dot(1.0)), "b"), Layer(listOf(dot(2.0)), "c"))
        val out = LayerOps.move(p, 0, 2)
        assertEquals(listOf("b", "c", "a"), out.layers.map { it.name })
    }

    @Test fun moveElementsRelocatesAndReports() {
        val p = page(Layer(listOf(dot(1.0), dot(2.0))), Layer(emptyList()))
        val (out, refs) = LayerOps.moveElementsToLayer(p, setOf(ElementRef(0, 0)), target = 1)
        assertEquals(1, out.layers[0].elements.size)  // one left behind
        assertEquals(1, out.layers[1].elements.size)  // one moved up
        assertEquals(setOf(ElementRef(1, 0)), refs)
    }

    @Test fun labelFallsBackToLayerNumber() {
        val p = page(Layer(emptyList()), Layer(emptyList(), "Ink"))
        assertEquals("Layer 1", LayerOps.label(p, 0))
        assertEquals("Ink", LayerOps.label(p, 1))
    }
}
