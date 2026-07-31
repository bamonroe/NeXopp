package com.xopp.android.render

import android.graphics.Canvas
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke

/**
 * Draws every layer's elements of a [Page] in z-order at a given scale and canvas offset — strokes
 * via [StrokePainter], everything else via [ElementRenderer]. The page background is drawn
 * separately by [BackgroundRenderer]. Shared by the on-screen [DrawingSurfaceView] and [PdfExporter]
 * so the flattened output matches the editor.
 */
object PageRenderer {

    fun drawElements(
        canvas: Canvas,
        page: Page,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        strokes: StrokePainter,
        elements: ElementRenderer,
    ) {
        for (layer in page.layers) {
            for (element in layer.elements) {
                if (element is Stroke) {
                    strokes.draw(canvas, element.points, element.tool, element.color, scale, offsetX, offsetY)
                } else {
                    elements.draw(canvas, element, scale, offsetX, offsetY)
                }
            }
        }
    }
}
