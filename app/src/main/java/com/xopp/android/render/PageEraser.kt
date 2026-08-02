package com.xopp.android.render

import com.xopp.android.format.model.Element
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke

/** How the eraser removes ink: rub out touched segments, or delete whole strokes. */
enum class EraserMode { STANDARD, WHOLE_STROKE }

/**
 * Eraser tip sizes, mirroring desktop Xournal++'s fine / medium / thick. The radius is in document
 * **pt**, so the tip covers the same ink at every zoom level — the same as on the desktop.
 */
enum class EraserSize(val radiusPt: Double, val label: String) {
    FINE(4.0, "Fine"),
    MEDIUM(10.0, "Medium"),
    THICK(20.0, "Thick"),
}

/**
 * The eraser applied to a whole page: pure, Android-free, and unit-testable ([PageEraserTest]).
 *
 * Hidden layers are left untouched — you can only rub out ink you can actually see, matching the
 * desktop. Only [Stroke]s erase; text and images are deleted through selection instead.
 */
object PageEraser {

    /**
     * Erase at ([px], [py]) with tip [radius] on [page], skipping any layer index in [hiddenLayers].
     * Returns the rewritten page, or `null` when nothing was touched (so the caller can skip the
     * document rebuild and the undo snapshot).
     */
    fun erase(
        page: Page,
        px: Double,
        py: Double,
        radius: Double,
        mode: EraserMode,
        hiddenLayers: Set<Int> = emptySet(),
    ): Page? {
        var changed = false
        val layers = page.layers.mapIndexed { li, layer ->
            if (li in hiddenLayers) return@mapIndexed layer
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
