package com.xopp.android.render

import android.graphics.BitmapFactory
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Element
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.TextElement

/**
 * Pure document edits behind placing and editing non-stroke elements (text boxes, images, LaTeX).
 * Every function takes a [Document] and returns a new one, or null when nothing changed — the
 * caller owns history, layout and repaint. Keeping them free of view state is what makes them
 * unit-testable; [TextEditController] is the stateful half that drives them.
 */
internal object ElementEdits {

    /** Append [element] to page [pageIndex]'s active layer (resolved by [activeLayerOf]), or null. */
    fun addElement(
        doc: Document,
        pageIndex: Int,
        element: Element,
        activeLayerOf: (Page) -> Int,
    ): Document? {
        val pages = doc.pages.toMutableList()
        val page = pages.getOrNull(pageIndex) ?: return null
        val layers = page.layers.ifEmpty { listOf(Layer(emptyList())) }.toMutableList()
        val target = activeLayerOf(page).coerceIn(0, layers.lastIndex)
        layers[target] = Layer(layers[target].elements + element, layers[target].name)
        pages[pageIndex] = page.copy(layers = layers)
        return doc.copy(pages = pages)
    }

    /**
     * Replace [old] (matched by identity, so two equal text boxes never swap) with [new], or remove
     * it when [new] is null. Null when [old] isn't in the document.
     */
    fun replaceElement(doc: Document, old: Element, new: Element?): Document? {
        var changed = false
        val pages = doc.pages.map { page ->
            page.copy(layers = page.layers.map { layer ->
                val idx = layer.elements.indexOfFirst { it === old }
                if (idx < 0) return@map layer
                changed = true
                val els = layer.elements.toMutableList()
                if (new == null) els.removeAt(idx) else els[idx] = new
                Layer(els, layer.name)
            })
        }
        return if (changed) doc.copy(pages = pages) else null
    }

    /** The top-most text box on [pageIndex] whose (approximate) bounds contain the point, or null. */
    fun pickText(doc: Document, pageIndex: Int, xPt: Double, yPt: Double): TextElement? {
        val page = doc.pages.getOrNull(pageIndex) ?: return null
        for (layer in page.layers.asReversed()) {
            for (el in layer.elements.asReversed()) {
                if (el is TextElement && hitsText(el, xPt, yPt)) return el
            }
        }
        return null
    }

    /** Rough hit test for a text box from its content extent (glyph widths aren't measured here). */
    fun hitsText(t: TextElement, xPt: Double, yPt: Double): Boolean {
        val lines = t.content.split("\n")
        val h = lines.size * t.size * 1.3
        val w = (lines.maxOfOrNull { it.length } ?: 1) * t.size * 0.62
        return xPt >= t.x - 4 && xPt <= t.x + w + 4 && yPt >= t.y - 4 && yPt <= t.y + h + 4
    }

    /** The natural pt size for an encoded image, scaled so its longest side is [IMG_MAX_PT]. */
    fun imageBoxPt(data: ByteArray): Pair<Double, Double> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val w = opts.outWidth.coerceAtLeast(1)
        val h = opts.outHeight.coerceAtLeast(1)
        val s = IMG_MAX_PT / maxOf(w, h)
        return w * s to h * s
    }

    /** Default extent (pt) of a placed LaTeX image; it is resizable once on the page. */
    const val TEX_W_PT = 120.0
    const val TEX_H_PT = 40.0
    /** Longest side (pt) a placed image is scaled to fit. */
    const val IMG_MAX_PT = 240.0
}
