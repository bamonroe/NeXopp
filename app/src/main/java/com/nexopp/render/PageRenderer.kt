package com.nexopp.render

import android.graphics.Canvas
import com.nexopp.format.model.Element
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke

/**
 * Draws every layer's elements of a [Page] in z-order at a given scale and canvas offset — strokes
 * via [StrokePainter], everything else via [ElementRenderer]. The page background is drawn
 * separately by [BackgroundRenderer]. Shared by the on-screen [DrawingSurfaceView] and [PdfExporter]
 * so the flattened output matches the editor.
 */
object PageRenderer {

    /**
     * Bounding boxes keyed by element *identity*, so culling doesn't rescan every stroke's points on
     * every frame. The model is immutable — an edited element is a new instance — so a stale entry
     * can't outlive the geometry it describes. Bounded so a long session can't grow it without end.
     */
    private const val BOUNDS_CACHE_MAX = 20_000
    private val boundsCache = java.util.IdentityHashMap<Element, Bounds>()

    private fun boundsOf(element: Element): Bounds =
        boundsCache[element] ?: ElementBounds.of(element).also {
            if (boundsCache.size >= BOUNDS_CACHE_MAX) boundsCache.clear()
            boundsCache[element] = it
        }

    fun drawElements(
        canvas: Canvas,
        page: Page,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        strokes: StrokePainter,
        elements: ElementRenderer,
        hiddenLayers: Set<Int> = emptySet(),
        visible: Bounds? = null,
    ) {
        page.layers.forEachIndexed { li, layer ->
            if (li in hiddenLayers) return@forEachIndexed
            for (element in layer.elements) {
                // Only the drawing thread passes a viewport, so the identity cache stays single-threaded.
                if (visible != null && !boundsOf(element).intersects(visible)) continue
                if (element is Stroke) {
                    strokes.draw(
                        canvas, element.points, element.tool, element.color, scale, offsetX, offsetY,
                        element.lineStyle, element.fill,
                    )
                } else {
                    elements.draw(canvas, element, scale, offsetX, offsetY)
                }
            }
        }
    }
}
