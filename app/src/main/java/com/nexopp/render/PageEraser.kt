package com.nexopp.render

import com.nexopp.format.model.Element
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke

/** How the eraser removes ink: rub out touched segments, or delete whole strokes. */
enum class EraserMode { STANDARD, WHOLE_STROKE }

/**
 * How much bigger the eraser tip is than the pen tip it follows. The eraser has no size scheme of
 * its own — it rides the same three configurable pen-width slots as the pen ([AppSettings.penWidths]),
 * so picking a tip size in the rail's Size popup sizes both. A pen width is a **diameter** and this
 * yields a **radius**, so a factor of 1 makes the eraser's radius the pen's full width — a rubber
 * twice as wide as the ink it rubs out, which is enough to aim without swallowing the page.
 */
const val ERASER_RADIUS_FACTOR: Double = 1.0

/** Smallest eraser tip, in pt — a hair-thin pen still needs a tip you can aim. */
const val ERASER_RADIUS_MIN_PT: Double = 1.0

/**
 * The eraser tip radius for a pen width of [widthPt], in document **pt**, so the tip covers the same
 * ink at every zoom level — the same as on the desktop.
 */
fun eraserRadiusPt(widthPt: Float): Double =
    (widthPt * ERASER_RADIUS_FACTOR).coerceAtLeast(ERASER_RADIUS_MIN_PT)

/**
 * The eraser applied to a whole page: pure, Android-free, and unit-testable ([PageEraserTest]).
 *
 * Hidden layers are left untouched — you can only rub out ink you can actually see, matching the
 * desktop. Only [Stroke]s erase; text and images are deleted through selection instead.
 */
object PageEraser {

    /**
     * Erase at ([px], [py]) with tip [radius] on [page], skipping any layer index in [hiddenLayers].
     * When [onlyLayer] is non-null the rubber bites on that layer alone — ink on the other layers is
     * left alone, matching the desktop, where the eraser works on the selected layer. Returns the
     * rewritten page, or `null` when nothing was touched (so the caller can skip the document
     * rebuild and the undo snapshot).
     */
    fun erase(
        page: Page,
        px: Double,
        py: Double,
        radius: Double,
        mode: EraserMode,
        hiddenLayers: Set<Int> = emptySet(),
        onlyLayer: Int? = null,
    ): Page? {
        var changed = false
        val layers = page.layers.mapIndexed { li, layer ->
            if (li in hiddenLayers) return@mapIndexed layer
            if (onlyLayer != null && li != onlyLayer) return@mapIndexed layer
            val rebuilt = eraseLayer(layer, px, py, radius, mode) ?: return@mapIndexed layer
            changed = true
            rebuilt
        }
        return if (changed) page.copy(layers = layers) else null
    }

    /** One layer's rewrite, or `null` when the tip missed everything in it. */
    private fun eraseLayer(
        layer: Layer,
        px: Double,
        py: Double,
        radius: Double,
        mode: EraserMode,
    ): Layer? {
        var touched = false
        val rebuilt = ArrayList<Element>(layer.elements.size)
        for (el in layer.elements) {
            if (el !is Stroke) { rebuilt += el; continue }
            when (mode) {
                EraserMode.WHOLE_STROKE ->
                    if (StrokeHitTester.hits(el, px, py, radius)) touched = true else rebuilt += el
                EraserMode.STANDARD -> {
                    val pieces = StrokeEraser.erase(el, px, py, radius)
                    if (pieces == null) rebuilt += el else { touched = true; rebuilt += pieces }
                }
            }
        }
        return if (touched) Layer(rebuilt, layer.name) else null
    }
}
