package com.nexopp.render

import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Page
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement

/**
 * Owns placing and editing the non-stroke elements — text boxes, images and LaTeX images — that a
 * placement tap creates. This is the whole authoring round trip: a tap either hits an existing text
 * box (remembered as the edit target, so the editor opens prefilled) or lands on empty page, and the
 * editor's answer comes back through [insertText] / [insertTex] / [insertImage] as one undoable edit.
 *
 * The controller holds only the pending edit target; the document edits themselves are the pure
 * functions in [ElementEdits]. [commit] hands the new document back to the view, which is what
 * records history and repaints.
 */
internal class TextEditController(
    private val document: () -> Document,
    private val activeLayerOf: (Page) -> Int,
    private val commit: (Document) -> Unit,
) {

    /** The text box a placement tap hit, awaiting an edit-or-delete from the editor. */
    private var editingTarget: TextElement? = null

    /**
     * The text box at ([xPt], [yPt]) on [pageIndex], remembered as the pending edit target so the
     * next [insertText] edits it instead of creating a new box. Null when the tap hit empty page.
     * @param pageIndex Page index to search (0-based).
     * @param xPt X coordinate in page points.
     * @param yPt Y coordinate in page points.
     * @return TextElement if found, null otherwise.
     */
    fun pickForEditing(pageIndex: Int, xPt: Double, yPt: Double): TextElement? =
        ElementEdits.pickText(document(), pageIndex, xPt, yPt)?.also { editingTarget = it }

    /**
     * Create a text box (or edit the one a tap hit) at the placement; blank content deletes it.
     * @param p Placement info (page, position).
     * @param content Text content.
     * @param font Font family name.
     * @param sizePt Font size in points.
     * @param colorArgb ARGB color.
     */
    fun insertText(p: Placement, content: String, font: String, sizePt: Double, colorArgb: Int) {
        val target = editingTarget
        editingTarget = null
        if (content.isBlank()) {
            if (target != null) replace(target, null)
            return
        }
        val text = TextElement(font, sizePt, p.xPt, p.yPt, colorArgb, content)
        if (target != null) replace(target, text) else add(p.pageIndex, text)
    }

    /**
     * Place a LaTeX image at the placement, sized to a default box (resizable later).
     * @param p Placement info (page, position).
     * @param latex LaTeX source string.
     * @param colorArgb ARGB color.
     */
    fun insertTex(p: Placement, latex: String, colorArgb: Int) {
        if (latex.isBlank()) return
        add(
            p.pageIndex,
            TexImageElement(
                p.xPt, p.yPt,
                p.xPt + ElementEdits.TEX_W_PT, p.yPt + ElementEdits.TEX_H_PT,
                latex, colorArgb,
            ),
        )
    }

    /**
     * Place an encoded image (PNG/JPEG bytes) at the placement, scaled to fit a default extent.
     * @param p Placement info (page, position).
     * @param data Image bytes (PNG or JPEG).
     */
    fun insertImage(p: Placement, data: ByteArray) {
        val (wPt, hPt) = ElementEdits.imageBoxPt(data)
        add(p.pageIndex, ImageElement(p.xPt, p.yPt, p.xPt + wPt, p.yPt + hPt, data))
    }

    /** Discard a pending text-edit target (the editor's dialog was dismissed without saving). */
    fun cancel() { editingTarget = null }

    private fun add(pageIndex: Int, element: Element) {
        ElementEdits.addElement(document(), pageIndex, element, activeLayerOf)?.let(commit)
    }

    private fun replace(old: Element, new: Element?) {
        ElementEdits.replaceElement(document(), old, new)?.let(commit)
    }
}
