package com.nexopp.format.rnote

import com.nexopp.format.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The layer stack against the ground-truth fixtures written by `rnote-cli` 0.14.2
 * (`app/src/test/resources/fixtures/rnote/`), plus the slot ordering on its own.
 */
class RnoteLayerStackTest {

    private fun slotsOf(name: String): List<String> {
        val wrapper = javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
            ?.use { RnoteContainer.open(it) }
            ?: error("missing fixture fixtures/rnote/$name.rnote")
        return layerSlots(RnoteSnapshot.parse(wrapper.snapshot).strokes)
    }

    private fun stroke(layer: String, userLayer: Int?) =
        RnoteStroke(index = 0, kind = "brushstroke", body = JsonNull, z = 1L, layer = layer, userLayer = userLayer)

    @Test
    fun `a pen stroke names its numbered user layer`() {
        assertEquals("user_layer 0", layerSlotName(stroke("user_layer", 0)))
        assertEquals("user_layer 3", layerSlotName(stroke("user_layer", 3)))
    }

    @Test
    fun `a user layer with no number falls back to layer 0`() {
        assertEquals("user_layer 0", layerSlotName(stroke("user_layer", null)))
    }

    @Test
    fun `every other slot keeps its own name`() {
        assertEquals("highlighter", layerSlotName(stroke("highlighter", null)))
        assertEquals("image", layerSlotName(stroke("image", null)))
        assertEquals("document", layerSlotName(stroke("document", null)))
        assertEquals("scribble", layerSlotName(stroke("scribble", null)))
    }

    @Test
    fun `the highlighter sorts below the pen layers`() {
        val order = listOf("document", "image", "highlighter", "user_layer 0", "user_layer 1").map(::layerOrder)
        assertEquals(order.sorted(), order)
        assertEquals(listOf(0, 1, 2, 3, 4), order)
    }

    @Test
    fun `an unknown slot sorts last`() {
        assertEquals(listOf("highlighter", "user_layer 9", "scribble"), layerSlots(
            listOf(stroke("scribble", null), stroke("user_layer", 9), stroke("highlighter", null)),
        ))
    }

    @Test
    fun `plain and layers both stack the highlighter under one pen layer`() {
        assertEquals(listOf("highlighter", "user_layer 0"), slotsOf("plain"))
        assertEquals(listOf("highlighter", "user_layer 0"), slotsOf("layers"))
    }

    @Test
    fun `text-image stacks the image layer under the pen layer`() {
        assertEquals(listOf("image", "user_layer 0"), slotsOf("text-image"))
    }

    @Test
    fun `backgrounds only ever uses the default pen layer`() {
        assertEquals(listOf("user_layer 0"), slotsOf("backgrounds"))
    }

    @Test
    fun `an empty canvas has no layers`() {
        assertEquals(emptyList<String>(), slotsOf("empty"))
    }
}
