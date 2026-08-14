package com.nexopp.format.rnote

import com.nexopp.format.json.JsonArray
import com.nexopp.format.json.JsonNull
import com.nexopp.format.json.JsonNumber
import com.nexopp.format.json.JsonString
import com.nexopp.format.json.JsonValue
import com.nexopp.format.json.jsonNumbers
import com.nexopp.format.json.jsonObject
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool

/**
 * The assembler that ties the `.rnote` export together — the mirror of [readDocument]. It walks the
 * page stack in painters order, lays each element back onto the single canvas with the geometry from
 * `RnoteDocumentWriterGeometry.kt`, encodes it with `RnoteStrokeEncode.kt`, and pairs each stroke
 * slot with its chrono entry. Every rule it applies is decided elsewhere; this file is the wiring.
 *
 * See `docs/architecture.md`, "Decision (2026-08-12): canvas ↔ pages, layers & backgrounds" (the
 * *Export* half) and the feature-gap matrix for what is written and what is left out.
 */

/** The only layout we write: it makes Rnote draw its page boundaries where our page stack puts them. */
private const val LAYOUT = "fixed_size"

/** The page background an empty document is written with: a plain white sheet, as Rnote's own is. */
private val EMPTY_BACKGROUND = Background.Solid(color = 0xFFFFFFFF.toInt(), style = "plain")

/**
 * Build the `engine_snapshot` object for [document].
 *
 * Elements that cannot cross are **skipped, never faked**: an eraser stroke (Rnote has none), a
 * `RawElement` (verbatim XML with no JSON home) and any picture whose bytes the PNG decoder refuses.
 * `exportWarnings` reports each of those, which is what makes the skip legitimate under the
 * lossy-mapping policy.
 *
 * @param document The document being written.
 * @return The `engine_snapshot` object, ready for [RnoteContainer.writeJson].
 */
fun writeSnapshot(document: Document): JsonValue {
    val strokes = encodeElements(document)
    val extent = canvasExtent(document)
    return jsonObject(
        "document" to jsonObject(
            "config" to jsonObject(
                "format" to formatJson(canvasFormat(document)),
                // Page 1's background wins; the rest have no canvas to live on and are warned about.
                "background" to backgroundJson(
                    toRnoteBackground(document.pages.firstOrNull()?.background ?: EMPTY_BACKGROUND),
                ),
                "layout" to JsonString(LAYOUT),
            ),
            "x" to JsonNumber(extent.minX),
            "y" to JsonNumber(extent.minY),
            "width" to JsonNumber(extent.maxX - extent.minX),
            "height" to JsonNumber(extent.maxY - extent.minY),
        ),
        "stroke_components" to slotArray(strokes) { jsonObject(it.kind to it.body) },
        "chrono_components" to slotArray(strokes) { chronoJson(it) },
        // The next `t` to hand out; slot 0 is null, so this is one past the last element.
        "chrono_counter" to JsonNumber(strokes.size + 1.0),
    )
}

/** One element that made it onto the canvas: its stroke tag, its body, and the slot it sits on. */
private class EncodedStroke(
    val kind: String,
    val body: JsonValue,
    val layer: String,
    val userLayer: Int?,
    /** The chrono `t`, counting from 1 in painters order across the whole document. */
    val z: Int,
)

/**
 * Walk every page, layer and element in painters order, translating each onto the canvas and
 * encoding it. The page offset is applied in **pt** — the encoders convert to px themselves, so
 * shifting first keeps one conversion in one place.
 */
private fun encodeElements(document: Document): List<EncodedStroke> {
    val origins = pageOriginsPx(document)
    val encoded = ArrayList<EncodedStroke>()
    for ((pageIndex, page) in document.pages.withIndex()) {
        val offsetPt = pxToPt(origins[pageIndex])
        for ((layerIndex, layer) in page.layers.withIndex()) {
            for (element in layer.elements) {
                val onCanvas = translated(element, offsetPt)
                val (kind, body) = encodeElement(onCanvas) ?: continue
                val (slot, userLayer) = slotFor(element, layer, layerIndex)
                encoded += EncodedStroke(kind, body, slot, userLayer, encoded.size + 1)
            }
        }
    }
    return encoded
}

/** Dispatch one element to its encoder, as the tag it will be written under. */
private fun encodeElement(element: Element): Pair<String, JsonValue>? = when (element) {
    // Rnote has no eraser stroke; these only ever arrive from an opened `.xopp`.
    is Stroke -> if (element.tool == Tool.ERASER) null else strokeToBrushStroke(element)?.let { "brushstroke" to it }
    is TextElement -> "textstroke" to textToTextStroke(element)
    is ImageElement -> imageToBitmapImage(element)?.let { "bitmapimage" to it }
    is TexImageElement -> texImageToBitmapImage(element)?.let { "bitmapimage" to it }
    else -> null
}

/** Shift a page-local element down the canvas by its page's offset, in pt. */
private fun translated(element: Element, offsetPt: Double): Element = when (element) {
    is Stroke -> element.copy(points = element.points.map { it.copy(y = it.y + offsetPt) })
    is TextElement -> element.copy(y = element.y + offsetPt)
    is ImageElement -> element.copy(top = element.top + offsetPt, bottom = element.bottom + offsetPt)
    is TexImageElement -> element.copy(top = element.top + offsetPt, bottom = element.bottom + offsetPt)
    else -> element
}

/**
 * A slotmap array: a leading `null` for index 0, which Rnote never fills, then one `{"value","version"}`
 * slot per element. `stroke_components` and `chrono_components` are parallel, so both go through here.
 */
private fun slotArray(strokes: List<EncodedStroke>, value: (EncodedStroke) -> JsonValue): JsonValue =
    JsonArray(
        listOf(jsonObject("value" to JsonNull, "version" to JsonNumber(0.0))) +
            strokes.map { jsonObject("value" to value(it), "version" to JsonNumber(1.0)) },
    )

/** One chrono entry: the painters-order counter and the layer slot the element landed on. */
private fun chronoJson(stroke: EncodedStroke): JsonValue = jsonObject(
    "t" to JsonNumber(stroke.z.toDouble()),
    "layer" to if (stroke.userLayer != null) {
        jsonObject("user_layer" to JsonNumber(stroke.userLayer.toDouble()))
    } else {
        JsonString(stroke.layer)
    },
)

/** The `document.config.format` block; the cosmetics Rnote also stores there are left at its defaults. */
private fun formatJson(format: RnoteFormat): JsonValue = jsonObject(
    "width" to JsonNumber(format.width),
    "height" to JsonNumber(format.height),
    "dpi" to JsonNumber(format.dpi),
    "orientation" to JsonString(format.orientation),
)

/** The `document.config.background` block, with the ruling pitch Xournal++ renders at. */
private fun backgroundJson(background: RnoteBackground): JsonValue = jsonObject(
    "color" to colorJson(background.color),
    "pattern" to JsonString(background.pattern),
    "pattern_size" to jsonNumbers(background.patternWidth, background.patternHeight),
    "pattern_color" to colorJson(background.patternColor),
)
