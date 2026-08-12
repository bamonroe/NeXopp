package com.nexopp.format.rnote

import com.nexopp.format.json.JsonObject
import com.nexopp.format.json.JsonValue
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The `brushstroke` → [Stroke] half of the `.rnote` stroke mapping. The JSON shapes and every
 * constant here are documented in `docs/architecture.md`, section "The stroke & pen mapping
 * (measured against the fixture twins)"; the units and colour packing come from `RnoteUnits.kt`.
 */

/** The default pen width Rnote writes when `style.*.stroke_width` is absent, in px. */
private const val DEFAULT_STROKE_WIDTH_PX = 2.0

/** Opaque black — the colour a brush stroke with no `stroke_color` falls back to. */
private const val DEFAULT_COLOR = 0xFF000000.toInt()

/** Pressures within this of 1.0 count as "no pressure", i.e. a uniform-width stroke. */
private const val PRESSURE_EPSILON = 1e-6

/**
 * Convert one `brushstroke` into a [Stroke].
 *
 * Rnote's pressure polyline is 1:1 with our vertex list: `path.start` followed by the `end` of
 * each segment, with each vertex's width being `stroke_width × pressure` in px. A stroke on the
 * `highlighter` layer becomes [Tool.HIGHLIGHTER]; Rnote has no eraser, so nothing maps to
 * [Tool.ERASER].
 *
 * @param stroke A slot from [RnoteSnapshot.strokes].
 * @return The converted stroke, or null if [stroke] is not a `brushstroke`.
 */
fun brushStrokeToStroke(stroke: RnoteStroke): Stroke? {
    if (stroke.kind != "brushstroke") return null
    val style = singleTagValue(stroke.body.obj("style"))
    val widthPx = style?.obj("stroke_width")?.num() ?: DEFAULT_STROKE_WIDTH_PX
    val pressures = ArrayList<Double>()
    val points = ArrayList<StrokePoint>()
    for (vertex in pathVertices(stroke.body.obj("path"))) {
        val pos = vertex.obj("pos")?.arr() ?: continue
        val x = pos.getOrNull(0)?.num() ?: continue
        val y = pos.getOrNull(1)?.num() ?: continue
        val pressure = vertex.obj("pressure")?.num() ?: 1.0
        pressures += pressure
        points += StrokePoint(pxToPt(x), pxToPt(y), pxToPt(widthPx * pressure))
    }
    return Stroke(
        tool = if (stroke.layer == "highlighter") Tool.HIGHLIGHTER else Tool.PEN,
        color = strokeColor(style),
        capStyle = null,
        points = points,
        uniformWidth = pressures.all { abs(it - 1.0) < PRESSURE_EPSILON },
        lineStyle = lineStyleOf(style?.obj("line_style")?.str()),
        fill = fillAlpha(style?.obj("fill_color")),
        extraAttrs = emptyMap(),
    )
}

/**
 * The vertices of a `path` in draw order: `start`, then each segment's `end`.
 *
 * Every fixture tags its segments `lineto`, but the tag is Rnote's segment *kind* (a curve would
 * use another), so we read the single tag's value whatever it is called and take its `end`. A
 * segment with no `end` is skipped rather than treated as a break in the stroke.
 */
private fun pathVertices(path: JsonValue?): List<JsonValue> {
    if (path == null) return emptyList()
    val vertices = ArrayList<JsonValue>()
    path.obj("start")?.let { vertices += it }
    for (segment in path.obj("segments")?.arr().orEmpty()) {
        singleTagValue(segment)?.obj("end")?.let { vertices += it }
    }
    return vertices
}

/**
 * The value behind a single-key tag object — how Rnote spells an enum variant with a payload.
 * Used for both the style (`smooth` or `textured`) and a path segment (`lineto`, …), so a
 * textured-brush file converts on the same path a smooth one does.
 */
private fun singleTagValue(tagged: JsonValue?): JsonValue? =
    (tagged as? JsonObject)?.members?.values?.firstOrNull()

/** `stroke_color` packed into our ARGB int, or opaque black when the file omits it. */
private fun strokeColor(style: JsonValue?): Int {
    val color = style?.obj("stroke_color") ?: return DEFAULT_COLOR
    return rnoteColor(color)?.toXopp() ?: DEFAULT_COLOR
}

/**
 * `.xopp` records a fill as a bare alpha, so a present `fill_color` collapses to its alpha and an
 * absent or JSON-null one means "no fill".
 */
private fun fillAlpha(fillColor: JsonValue?): Int? {
    val color = rnoteColor(fillColor ?: return null) ?: return null
    return (color.a.coerceIn(0.0, 1.0) * 255.0).roundToInt()
}

/** A four-channel Rnote colour object, or null if it is JSON `null` or missing a channel. */
private fun rnoteColor(color: JsonValue): RnoteColor? {
    if (color.isNull()) return null
    return RnoteColor(
        r = color.obj("r")?.num() ?: return null,
        g = color.obj("g")?.num() ?: return null,
        b = color.obj("b")?.num() ?: return null,
        a = color.obj("a")?.num() ?: return null,
    )
}

/** Rnote's `line_style` name → our [LineStyle]; anything unrecognised draws solid. */
private fun lineStyleOf(name: String?): LineStyle = when (name) {
    "dashed" -> LineStyle.DASHED
    "dotted" -> LineStyle.DOTTED
    "dashed_dotted", "dashdot" -> LineStyle.DASH_DOT
    else -> LineStyle.PLAIN
}
