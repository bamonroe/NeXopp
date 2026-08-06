/**
 * [DrawingSurfaceView]'s selection surface: starting a rubber-band/tap selection, the PDF-text
 * selection built on the extracted text layer, and every edit that acts on a selection — delete,
 * restyle, copy/cut/paste and duplicate. Extensions on the view, so they read its state directly;
 * the selection itself is owned by [SelectionGestureController] and the element clipboard lives on
 * the view in `DrawingSurfaceView.kt`.
 */
package com.xopp.android.render

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import com.xopp.android.format.model.Element

// --- selection: rubber-band / tap to select, drag to move, delete ------------------------------

/** Down in Select mode: clear the other modes and let [SelectionGestureController] take it. */
internal fun DrawingSurfaceView.beginSelect(event: MotionEvent) {
    scrolling = false
    erasing = false
    placing = false
    current = null
    gestures.beginSelect(event)
}

/** Delete every selected element as one undoable edit. */
fun DrawingSurfaceView.deleteSelection() {
    val sel = selection ?: return
    val before = doc
    doc = doc.copy(pages = SelectionOps.delete(doc.pages, sel.pageIndex, sel.refs))
    selection = null
    onSelectionChanged?.invoke(false)
    history.record(before)
    notifyHistory()
    relayout()
    render()
}

/** Drop the current selection (a view-only change; not recorded in history). */
fun DrawingSurfaceView.clearSelection() {
    if (selection == null) return
    gestures.clearSelection()
    render()
}

/** Recolour and/or re-width the selected elements as one undoable edit (selection stays). */
fun DrawingSurfaceView.restyleSelection(color: Int?, widthPt: Double?) {
    val sel = selection ?: return
    val before = doc
    val pages = SelectionOps.restyle(doc.pages, sel.pageIndex, sel.refs, color, widthPt)
    if (pages === doc.pages) return
    doc = doc.copy(pages = pages)
    history.record(before)
    notifyHistory()
    relayout()
    render()
}

// --- the element clipboard: copy, cut, paste, duplicate ----------------------------------------

/** Copy the selected elements to the clipboard (leaves the document and selection unchanged). */
fun DrawingSurfaceView.copySelection() {
    val sel = selection ?: return
    val page = doc.pages.getOrNull(sel.pageIndex) ?: return
    clipboard = SelectionOps.elementsAt(page, sel.refs)
    onClipboardChanged?.invoke(clipboard.isNotEmpty())
}

/** Copy then delete the selection (one undoable edit via [deleteSelection]). */
fun DrawingSurfaceView.cutSelection() {
    if (selection == null) return
    copySelection()
    deleteSelection()
}

/** Whether the clipboard currently holds anything to paste. */
fun DrawingSurfaceView.hasClipboard(): Boolean = clipboard.isNotEmpty()

/** Paste the clipboard onto the visible page (offset a little), selecting the pasted copies. */
fun DrawingSurfaceView.pasteClipboard() {
    if (clipboard.isEmpty()) return
    val target = visiblePageIndex()
    pasteOnto(
        target,
        clipboard.map {
            SelectionOps.translate(it, DrawingSurfaceDefaults.PASTE_OFFSET_PT, DrawingSurfaceDefaults.PASTE_OFFSET_PT)
        },
    )
}

/** Duplicate the selection in place (offset a little), selecting the duplicates. */
fun DrawingSurfaceView.duplicateSelection() {
    val sel = selection ?: return
    val page = doc.pages.getOrNull(sel.pageIndex) ?: return
    val copies = SelectionOps.elementsAt(page, sel.refs).map {
        SelectionOps.translate(it, DrawingSurfaceDefaults.PASTE_OFFSET_PT, DrawingSurfaceDefaults.PASTE_OFFSET_PT)
    }
    pasteOnto(sel.pageIndex, copies)
}

/** Append [elements] to [pageIndex]'s top layer as one undoable edit and select them. */
private fun DrawingSurfaceView.pasteOnto(pageIndex: Int, elements: List<Element>) {
    if (elements.isEmpty()) return
    val before = doc
    val (pages, refs) = SelectionOps.addToTopLayer(doc.pages, pageIndex, elements)
    if (refs.isEmpty()) return
    doc = doc.copy(pages = pages)
    selection = ActiveSelection(pageIndex, refs)
    onSelectionChanged?.invoke(true)
    history.record(before)
    notifyHistory()
    relayout()
    render()
}

// --- PDF text selection -------------------------------------------------------------------------

/** Down with the text-select tool: anchor the selection at the word nearest the touch. */
internal fun DrawingSurfaceView.beginTextSelect(event: MotionEvent) {
    scrolling = false; erasing = false; placing = false; current = null
    val index = pdfTextIndex ?: return
    val pageIndex = layout.pageAt(event.x + scrollX, event.y + scrollY)?.index ?: return
    val box = layout.boxes.getOrNull(pageIndex) ?: return
    val anchor = index.anchorWord(pageIndex, box.toPtX(event.x, scrollX), box.toPtY(event.y, scrollY)) ?: return
    textSelecting = true
    textSelPage = pageIndex
    textSelAnchor = anchor
    textSelFocus = anchor
    onTextSelectionChanged?.invoke(true)
    render()
}

/** Drag: extend the selection to the word nearest the touch (kept on the anchor's page). */
internal fun DrawingSurfaceView.textSelectMove(event: MotionEvent) {
    val index = pdfTextIndex ?: return
    val box = layout.boxes.getOrNull(textSelPage) ?: return
    val focus = index.anchorWord(textSelPage, box.toPtX(event.x, scrollX), box.toPtY(event.y, scrollY)) ?: return
    if (focus != textSelFocus) { textSelFocus = focus; render() }
}

/** True while a PDF-text selection is active (drives the Copy affordance). */
fun DrawingSurfaceView.hasTextSelection(): Boolean = textSelPage >= 0 && textSelAnchor >= 0

/** Copy the selected PDF text to the Android system clipboard. */
fun DrawingSurfaceView.copyTextSelection() {
    val index = pdfTextIndex ?: return
    if (!hasTextSelection()) return
    val text = index.rangeText(textSelPage, textSelAnchor, textSelFocus)
    if (text.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("PDF text", text))
}

/** Drop the current PDF-text selection (view-only). */
fun DrawingSurfaceView.clearTextSelection() {
    val had = textSelPage >= 0
    textSelecting = false
    textSelPage = -1
    textSelAnchor = -1
    textSelFocus = -1
    if (had) {
        onTextSelectionChanged?.invoke(false)
        render()
    }
}
