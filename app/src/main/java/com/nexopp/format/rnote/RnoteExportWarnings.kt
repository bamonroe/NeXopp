package com.nexopp.format.rnote

import com.nexopp.audio.ATTR_AUDIO_FILENAME
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.RawElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool

/**
 * What a document loses by being written as `.rnote`, in plain sentences. Every row the
 * `.xopp` ↔ `.rnote` feature-gap matrix in `docs/architecture.md` marks **report** is checked
 * here, so a loss is never silent.
 *
 * This is pure: it computes the lines and nothing else. Showing them — modal on Save As, snackbar
 * on a plain Save — belongs to the save flow (see the "no per-tab document mode" decision).
 */

/** Page 1's size becomes the canvas format, so a re-import re-pages the document. */
private const val MIXED_PAGE_SIZES =
    "Pages have different sizes; Rnote uses one page size, so reopening may split pages differently."

/** One canvas, one background: every page after the first loses its own. */
private const val MIXED_BACKGROUNDS =
    "Pages have different backgrounds; Rnote keeps only one, so page 1's background will be used " +
        "for all."

/** Rnote has no PDF-backed page; the ink survives, the reference doesn't. */
private const val PDF_BACKGROUND =
    "PDF page backgrounds are not saved in .rnote; the annotations are kept but the PDF is not."

/** Rnote has no image-backed page either. */
private const val PIXMAP_BACKGROUND = "Image page backgrounds are not saved in .rnote."

/** No eraser stroke exists in Rnote; these only ever arrive from an opened file. */
private const val ERASER_STROKES = "Eraser strokes are not saved in .rnote."

/** A `RawElement` is verbatim XML from the opened file; JSON has nowhere to put it. */
private const val RAW_ELEMENT =
    "1 unrecognised element from the original file is not saved in .rnote."

/** The many-of form of [RAW_ELEMENT]. */
private fun rawElements(count: Int) =
    "$count unrecognised elements from the original file are not saved in .rnote."

/** A `<teximage>` exports as the PNG the desktop already rendered, so only the source is lost. */
private const val TEX_BOX = "1 LaTeX box is saved as a plain image; the LaTeX source is lost."

/** The many-of form of [TEX_BOX]. */
private fun texBoxes(count: Int) =
    "$count LaTeX boxes are saved as plain images; the LaTeX source is lost."

/** The `fn`/`ts` pen-replay anchor has no JSON home. */
private const val AUDIO_LINKS = "Audio recording links are not saved in .rnote."

/** A layer that doesn't name a slot becomes `user_layer <n>`. */
private const val LAYER_NAMES = "Layer names are not saved in .rnote."

/** Rnote's slot order is fixed: highlighter always sits below the pen layers. */
private const val HIGHLIGHTER_Z_ORDER =
    "Rnote always draws highlighter below ink, so highlighting that currently covers ink will " +
        "move behind it."

/**
 * Everything [document] would lose on its way into a `.rnote` file, one line per condition that
 * actually applies.
 *
 * @param document The document about to be written.
 * @return The warnings in a stable order, structural first; empty when nothing is lost — an empty
 *   list means "say nothing", never "show a reassurance".
 */
fun exportWarnings(document: Document): List<String> = buildList {
    if (hasMixedPageSizes(document)) add(MIXED_PAGE_SIZES)
    if (document.pages.distinctBy { it.background }.size > 1) add(MIXED_BACKGROUNDS)
    if (document.pages.any { it.background is Background.Pdf }) add(PDF_BACKGROUND)
    if (document.pages.any { it.background is Background.Pixmap }) add(PIXMAP_BACKGROUND)
    if (document.elements().any { it is Stroke && it.tool == Tool.ERASER }) add(ERASER_STROKES)

    val raw = document.elements().count { it is RawElement }
    if (raw == 1) add(RAW_ELEMENT) else if (raw > 1) add(rawElements(raw))
    val tex = document.elements().count { it is TexImageElement }
    if (tex == 1) add(TEX_BOX) else if (tex > 1) add(texBoxes(tex))

    if (document.elements().any { it.hasAudioLink() }) add(AUDIO_LINKS)
    if (document.layers().any { it.name != null && !isSlotName(it.name) }) add(LAYER_NAMES)
    if (document.pages.any { highlighterSinksBelowInk(it) }) add(HIGHLIGHTER_Z_ORDER)
}

/** Every layer in the document, page order then stack order. */
private fun Document.layers(): Sequence<Layer> =
    pages.asSequence().flatMap { it.layers.asSequence() }

/** Every drawable in the document, in document order. */
private fun Document.elements(): Sequence<Element> =
    layers().flatMap { it.elements.asSequence() }

/**
 * Whether this element points at an audio sidecar. Desktop Xournal++ writes `fn=""` on strokes with
 * no recording, so a blank name is "unlinked" rather than a link to be warned about — the same rule
 * [com.nexopp.audio.audioRef] applies, extended to the `<text>` boxes that carry the pair too.
 */
private fun Element.hasAudioLink(): Boolean {
    val fn = when (this) {
        is Stroke -> extraAttrs[ATTR_AUDIO_FILENAME]
        is TextElement -> extraAttrs[ATTR_AUDIO_FILENAME]
        else -> null
    }
    return fn != null && fn.isNotBlank()
}

/**
 * Whether any highlighter on [page] currently covers ink that Rnote would draw on top of it: a
 * highlighter layer with any non-highlighter content **below** it in the stack. Rnote's slot order
 * is fixed, so on export that highlighting sinks underneath.
 *
 * @param page The page being checked.
 * @return True when at least one highlighter layer sits above a layer holding anything else.
 */
private fun highlighterSinksBelowInk(page: Page): Boolean {
    var inkBelow = false
    for (layer in page.layers) {
        if (inkBelow && layer.elements.any { it.isHighlighter() }) return true
        if (layer.elements.any { !it.isHighlighter() }) inkBelow = true
    }
    return false
}

/** Whether this element is a highlighter stroke — the one thing Rnote's highlighter slot holds. */
private fun Element.isHighlighter(): Boolean = this is Stroke && tool == Tool.HIGHLIGHTER
