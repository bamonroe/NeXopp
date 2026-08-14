package com.nexopp.format.rnote

import com.nexopp.format.FontDescription
import com.nexopp.format.json.JsonArray
import com.nexopp.format.json.JsonObject
import com.nexopp.format.json.JsonValue
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The per-stroke half of the `.rnote` mapping: `brushstroke` and `shapestroke` → [Stroke],
 * `textstroke` → [TextElement] and `bitmapimage` → [ImageElement]. The JSON shapes and every
 * constant here are documented in `docs/architecture.md`, section "The stroke & pen mapping
 * (measured against the fixture twins)"; the units and colour packing come from `RnoteUnits.kt`.
 */

/** The default pen width Rnote writes when `style.*.stroke_width` is absent, in px. */
private const val DEFAULT_STROKE_WIDTH_PX = 2.0

/** Opaque black — the colour a brush stroke with no `stroke_color` falls back to. */
private const val DEFAULT_COLOR = 0xFF000000.toInt()

/** Pressures within this of 1.0 count as "no pressure", i.e. a uniform-width stroke. */
private const val PRESSURE_EPSILON = 1e-6

/** Samples taken around an `ellipse`'s perimeter when flattening it to a polyline. */
private const val ELLIPSE_SAMPLES = 32

/** Samples taken along a `quadbez`/`cubbez`, counting both endpoints. */
private const val BEZIER_SAMPLES = 24

/** Half-angle of an arrow's head: each barb leaves the tip 150 degrees off the shaft direction. */
private const val ARROW_BARB_DEGREES = 150.0

/** An arrow barb is at most this many stroke widths long, and never more than a quarter shaft. */
private const val ARROW_BARB_WIDTHS = 5.0

/** The font a `textstroke` falls back to when `text_style.font_family` is absent. */
private const val DEFAULT_FONT = "Sans"

/** The `font_weight` at and above which a `textstroke` counts as bold (CSS semi-bold). */
private const val BOLD_WEIGHT = 600.0

/** The font size Rnote writes by default, in px — 12 pt once converted. */
private const val DEFAULT_FONT_SIZE_PX = 16.0

/** The ranged text attribute kinds a whole-string range can override the base `text_style` with. */
private val PROMOTABLE = setOf("font_family", "font_size", "font_weight", "font_style", "text_color")

/**
 * The result of walking a whole `.rnote` stroke list: what converted, and what did not.
 *
 * @property elements The converted elements, in the painters order they were handed to us.
 * @property skipped How many strokes each unconvertible kind cost, keyed by [RnoteStroke.kind].
 *   The reader hands this to the UI so the user is told what the file lost — never drop it
 *   silently (see `docs/architecture.md`, "the `.rnote` lossy-mapping policy").
 * @property lossyText How many `textstroke`s arrived with their `ranged_text_attributes` flattened
 *   away — the runs disagreed, or the box carried `underline`/`strikethrough`, which `.xopp` cannot
 *   express. The text itself is kept; only the styling is lost, so these are reported beside
 *   [skipped] rather than counted in it.
 */
data class RnoteConversion(
    val elements: List<Element>,
    val skipped: Map<String, Int>,
    val lossyText: Int = 0,
)

/**
 * Convert every stroke of a snapshot, skipping and counting the ones nothing here can express.
 *
 * A `vectorimage` (an SVG has no `.xopp` home), an unknown tag from a newer Rnote, a shape we
 * cannot flatten, and a stroke whose JSON is malformed all land in [RnoteConversion.skipped]
 * under their own kind rather than throwing or arriving as a fabricated element — one corrupt
 * stroke must not sink the rest of the file.
 *
 * A `textstroke` whose ranged styling could not be promoted still converts — its text is never
 * dropped — but is tallied in [RnoteConversion.lossyText] so the UI can say what it cost.
 *
 * @param strokes [RnoteSnapshot.strokes], already in ascending z order.
 * @return The elements in that same order, plus the per-kind tally of what was skipped and how
 *   many text boxes lost their styling.
 */
fun convertStrokes(strokes: List<RnoteStroke>): RnoteConversion {
    val elements = ArrayList<Element>(strokes.size)
    val skipped = LinkedHashMap<String, Int>()
    var lossyText = 0
    for (stroke in strokes) {
        val element = convertStrokeOrNull(stroke)
        if (element == null) {
            skipped[stroke.kind] = (skipped[stroke.kind] ?: 0) + 1
        } else {
            elements += element
            if (stroke.kind == "textstroke" && textStyleIsLossy(stroke)) lossyText++
        }
    }
    return RnoteConversion(elements, skipped, lossyText)
}

/**
 * Whether one `textstroke`'s ranged styling was flattened away rather than promoted.
 *
 * Only meaningful for a stroke [textStrokeToText] converted; a stroke with no ranged attributes is
 * never lossy.
 */
internal fun textStyleIsLossy(stroke: RnoteStroke): Boolean {
    val content = stroke.body.obj("text")?.str() ?: return false
    return PromotedAttributes(stroke.body.obj("text_style"), content).lossy
}

/**
 * Convert a single stroke under the skip-and-report policy: null means "nothing here can express
 * it", whether because no converter claims the kind or because its body is malformed. Callers that
 * need to keep each element paired with its source stroke (the document reader, which needs the
 * stroke's layer slot) use this; [convertStrokes] is the same walk over a whole list.
 *
 * @param stroke A slot from [RnoteSnapshot.strokes].
 * @return The converted element, or null if it must be skipped and reported.
 */
fun convertStrokeOrNull(stroke: RnoteStroke): Element? = try {
    convertStroke(stroke)
} catch (_: IllegalArgumentException) {
    null
}

/** Dispatch one stroke to the converter for its kind, or null when no converter claims it. */
private fun convertStroke(stroke: RnoteStroke): Element? = when (stroke.kind) {
    "brushstroke" -> brushStrokeToStroke(stroke)
    "shapestroke" -> shapeStrokeToStroke(stroke)
    "textstroke" -> textStrokeToText(stroke)
    "bitmapimage" -> bitmapImageToImage(stroke)
    else -> null
}

/**
 * Convert one `brushstroke` into a [Stroke].
 *
 * Rnote's pressure polyline becomes our vertex list: `path.start` followed by each segment, with
 * each vertex's width being `stroke_width × pressure` in px. A `lineto` contributes its `end`
 * alone; a `quadbezto`/`cubbezto` is flattened to [BEZIER_SAMPLES] points so the curve is not
 * corner-cut into a straight line. A stroke on the `highlighter` layer becomes [Tool.HIGHLIGHTER];
 * Rnote has no eraser, so nothing maps to [Tool.ERASER].
 *
 * @param stroke A slot from [RnoteSnapshot.strokes].
 * @return The converted stroke, or null if [stroke] is not a `brushstroke`.
 */
fun brushStrokeToStroke(stroke: RnoteStroke): Stroke? {
    if (stroke.kind != "brushstroke") return null
    val style = strokeStyle(stroke.body)
    val pressures = ArrayList<Double>()
    val points = ArrayList<StrokePoint>()
    for (vertex in pathVertices(stroke.body.obj("path"))) {
        pressures += vertex.pressure
        points += StrokePoint(pxToPt(vertex.x), pxToPt(vertex.y), pxToPt(style.widthPx * vertex.pressure))
    }
    return style.toStroke(
        stroke,
        points,
        uniformWidth = pressures.all { abs(it - 1.0) < PRESSURE_EPSILON },
    )
}

/**
 * Convert one `shapestroke` into a [Stroke].
 *
 * Rnote stores a shape *parametrically* — a `line`'s two endpoints, an `ellipse`'s radii — while
 * `.xopp` only knows pressure polylines, so the shape is flattened to vertices here: straight
 * shapes give their corners, an `ellipse` [ELLIPSE_SAMPLES] samples around its perimeter, and a
 * `quadbez`/`cubbez` [BEZIER_SAMPLES] samples along the curve, and an `arrow` its shaft plus a
 * two-barb head retraced through the tip. Closed shapes (`rect`, `ellipse`,
 * `polygon`) repeat their first vertex last so a fill has something to close. The pen never varies
 * along a shape, so every vertex gets `style.*.stroke_width` and the stroke is uniform-width.
 *
 * @param stroke A slot from [RnoteSnapshot.strokes].
 * @return The converted stroke, or null if [stroke] is not a `shapestroke` or carries a shape tag
 *   we cannot flatten (a `vectorimage`-style parametric shape Rnote may add later).
 */
fun shapeStrokeToStroke(stroke: RnoteStroke): Stroke? {
    if (stroke.kind != "shapestroke") return null
    val (tag, shape) = singleTag(stroke.body.obj("shape")) ?: return null
    val style = strokeStyle(stroke.body)
    val vertices = shapeVertices(tag, shape, style.widthPx) ?: return null
    val widthPt = pxToPt(style.widthPx)
    return style.toStroke(
        stroke,
        vertices.map { (x, y) -> StrokePoint(pxToPt(x), pxToPt(y), widthPt) },
        uniformWidth = true,
    )
}

/**
 * Flatten one tagged shape body to its vertices in px, or null for a tag we do not know.
 *
 * A shape whose payload is malformed (an endpoint that is not a two-number array) also flattens to
 * null, so a half-written shape is skipped rather than landing as a broken stroke.
 */
private fun shapeVertices(tag: String, shape: JsonValue, widthPx: Double): List<Vertex>? = when (tag) {
    "line" -> listOfNotNull(vertexAt(shape.obj("start")), vertexAt(shape.obj("end")))
        .takeIf { it.size == 2 }
    "arrow" -> arrowVertices(shape, widthPx)
    "rect" -> boxCorners(shape)
    "ellipse" -> ellipseSamples(shape)
    "quadbez" -> bezierSamples(listOf("start", "cp", "end").map { vertexAt(shape.obj(it)) })
    "cubbez" -> bezierSamples(listOf("start", "cp1", "cp2", "end").map { vertexAt(shape.obj(it)) })
    "polyline" -> polylineVertices(shape)
    "polygon" -> polylineVertices(shape)?.let(::closed)
    else -> null
}

/**
 * Convert one `textstroke` into a [TextElement].
 *
 * `.xopp` gives a text box a single font, size and colour, so `font_family`, `font_size` and
 * `color` cross over, and `font_weight`/`font_style` fold into the Pango font description as the
 * `Bold` and `Italic` tokens (weight >= 600 is bold, style `italic` is italic).
 *
 * A `ranged_text_attributes` entry that spans the *whole* string is the "select all, make it bold"
 * case — Rnote records it as a range rather than a change to the base `text_style` — so the ranges
 * are flattened into runs and any of `font_family`, `font_size`, `font_weight`, `font_style` and
 * `text_color` that is uniform across every run **overrides** the base value. An attribute that
 * varies is dropped, as are `underline` and `strikethrough` (the Pango description has no token
 * for them) and `alignment`/`max_width`, per the lossy-mapping policy in `docs/architecture.md`.
 * One textstroke stays one `<text>`; it is never split into per-run boxes. The position is the
 * translation of the stroke's affine, in pt.
 *
 * @param stroke A slot from [RnoteSnapshot.strokes].
 * @return The converted text element, or null if [stroke] is not a `textstroke`.
 * @throws IllegalArgumentException If `text` is missing or the affine is malformed.
 */
fun textStrokeToText(stroke: RnoteStroke): TextElement? {
    if (stroke.kind != "textstroke") return null
    val content = stroke.body.obj("text")?.str()
        ?: throw IllegalArgumentException("missing textstroke.text")
    val style = stroke.body.obj("text_style")
    val promoted = PromotedAttributes(style, content)
    val (x, y) = textPosition(stroke.body.obj("transform"))
    return TextElement(
        font = fontDescription(style, promoted).compose(),
        size = pxToPt(promoted["font_size"]?.num() ?: style?.obj("font_size")?.num() ?: DEFAULT_FONT_SIZE_PX),
        x = x,
        y = y,
        color = (promoted["text_color"] ?: style?.obj("color"))?.let { rnoteColor(it)?.toXopp() }
            ?: DEFAULT_COLOR,
        content = content,
        extraAttrs = emptyMap(),
    )
}

/**
 * The whole-string `ranged_text_attributes` of one `textstroke`, ready to override `text_style`.
 *
 * Uniformity is decided on the flattened runs (so overlapping and partial ranges are resolved
 * first); the value handed back is the *last* entry of that kind, which is the one flattening let
 * win on the bytes it shares.
 */
private class PromotedAttributes(style: JsonValue?, content: String) {
    private val ranged = style?.obj("ranged_text_attributes")
    private val runs = flattenRuns(ranged, content.toByteArray(Charsets.UTF_8).size)

    /**
     * Whether any ranged attribute was lost: a kind `.xopp` has no home for at all
     * (`underline`, `strikethrough`, or anything a newer Rnote adds), or a promotable kind whose
     * value varies along the string.
     */
    val lossy: Boolean = (ranged as? JsonArray)?.items.orEmpty()
        .mapNotNull { (it.obj("attribute") as? JsonObject)?.members?.keys?.firstOrNull() }
        .any { it !in PROMOTABLE || uniformAttribute(runs, it) == null }

    /** The uniform JSON value of [kind] across the whole string, or null when it is not uniform. */
    operator fun get(kind: String): JsonValue? {
        if (uniformAttribute(runs, kind) == null) return null
        return (ranged as? JsonArray)?.items?.lastOrNull { it.obj("attribute")?.obj(kind) != null }
            ?.obj("attribute")?.obj(kind)
    }
}

/**
 * Fold a `text_style` object into a [FontDescription], letting [promoted] whole-string attributes
 * override the base family, weight and style.
 *
 * Rnote stores a CSS-style `font_weight` (400 regular, 700 bold) and a `font_style` string of
 * `regular` or `italic`; anything absent or unrecognised means unstyled.
 *
 * @param style The stroke's `text_style` object, or null when it has none.
 * @param promoted The uniform ranged attributes of the same stroke.
 * @return The family plus bold/italic flags, ready for [FontDescription.compose].
 */
private fun fontDescription(style: JsonValue?, promoted: PromotedAttributes) = FontDescription(
    family = (promoted["font_family"] ?: style?.obj("font_family"))?.str() ?: DEFAULT_FONT,
    bold = (promoted["font_weight"] ?: style?.obj("font_weight"))?.num()?.let { it >= BOLD_WEIGHT } ?: false,
    italic = (promoted["font_style"] ?: style?.obj("font_style"))?.str() == "italic",
)

/**
 * Convert one `bitmapimage` into an [ImageElement].
 *
 * Rnote keeps the pixels raw — `image.data` is base64 of an uncompressed buffer in
 * `image.memory_format` — while `.xopp` embeds a PNG, so the pixels are un-premultiplied and
 * re-encoded through [RawImageCodec]. The bounds come from the stroke's `rectangle`: its affine's
 * translation is the centre and `cuboid.half_extents` the half width and height. Rotation and
 * shear in the affine have no `.xopp` home and are dropped per the lossy-mapping policy, so a
 * rotated image lands as its upright bounding box.
 *
 * @param stroke A slot from [RnoteSnapshot.strokes].
 * @return The converted image, or null if [stroke] is not a `bitmapimage`.
 * @throws IllegalArgumentException If the image or its rectangle is missing or malformed.
 */
fun bitmapImageToImage(stroke: RnoteStroke): ImageElement? {
    if (stroke.kind != "bitmapimage") return null
    val image = stroke.body.obj("image")
        ?: throw IllegalArgumentException("missing bitmapimage.image")
    val width = image.obj("pixel_width")?.num()?.toInt()
        ?: throw IllegalArgumentException("missing bitmapimage.image.pixel_width")
    val height = image.obj("pixel_height")?.num()?.toInt()
        ?: throw IllegalArgumentException("missing bitmapimage.image.pixel_height")
    val raw = RawImageCodec.decodeBase64(
        image.obj("data")?.str() ?: throw IllegalArgumentException("missing bitmapimage.image.data"),
    )
    val straight =
        if (RawImageCodec.isPremultiplied(image.obj("memory_format")?.str())) {
            RawImageCodec.unpremultiply(raw)
        } else {
            raw
        }
    val (cx, cy) = rectangleCentre(stroke.body.obj("rectangle"))
    val (hx, hy) = rectangleHalfExtents(stroke.body.obj("rectangle"))
    return ImageElement(
        left = pxToPt(cx - hx),
        top = pxToPt(cy - hy),
        right = pxToPt(cx + hx),
        bottom = pxToPt(cy + hy),
        data = RawImageCodec.encodePng(straight, width, height),
        extraAttrs = emptyMap(),
    )
}

/** The centre of a `rectangle`, in px — the translation of its own affine. */
private fun rectangleCentre(rectangle: JsonValue?): Pair<Double, Double> {
    val affine = rectangle?.obj("transform")?.obj("affine")?.arr()
        ?: throw IllegalArgumentException("missing rectangle.transform.affine")
    return affineTranslation(
        affine.map { it.num() ?: throw IllegalArgumentException("malformed transform.affine") },
    )
}

/** The half width and height of a `rectangle`, in px. */
private fun rectangleHalfExtents(rectangle: JsonValue?): Pair<Double, Double> {
    val extents = rectangle?.obj("cuboid")?.obj("half_extents")?.arr()
        ?: throw IllegalArgumentException("missing rectangle.cuboid.half_extents")
    val x = extents.getOrNull(0)?.num()
    val y = extents.getOrNull(1)?.num()
    if (x == null || y == null) throw IllegalArgumentException("malformed cuboid.half_extents")
    return x to y
}

/** A `transform.affine`'s translation in pt, or the origin when the stroke carries no transform. */
private fun textPosition(transform: JsonValue?): Pair<Double, Double> {
    val affine = transform?.obj("affine")?.arr() ?: return 0.0 to 0.0
    val (x, y) = affineTranslation(
        affine.map { it.num() ?: throw IllegalArgumentException("malformed transform.affine") },
    )
    return pxToPt(x) to pxToPt(y)
}

/** One sampled point of a `path`, in px, with the pressure that sets its width. */
private class PathVertex(val x: Double, val y: Double, val pressure: Double)

/**
 * The vertices of a `path` in draw order: `start`, then the points each segment adds.
 *
 * Upstream writes three segment kinds — `lineto {end}`, `quadbezto {cp, end}` and
 * `cubbezto {cp1, cp2, end}` — so a curved segment is flattened through the same [bezierSamples]
 * the shape flattener uses rather than collapsing to its `end`, which would straighten the stroke.
 * Only `end` is a full `{pos, pressure}` element; the control points are bare `[x, y]`, so every
 * interpolated point takes the segment end's pressure rather than a fabricated curve of its own. An
 * unknown tag falls back to its `end`, and a segment with nothing usable is skipped rather than
 * treated as a break in the stroke.
 */
private fun pathVertices(path: JsonValue?): List<PathVertex> {
    if (path == null) return emptyList()
    val vertices = ArrayList<PathVertex>()
    path.obj("start")?.let { pathVertexAt(it) }?.let { vertices += it }
    for (segment in path.obj("segments")?.arr().orEmpty()) {
        val (tag, body) = singleTag(segment) ?: continue
        val end = pathVertexAt(body.obj("end")) ?: continue
        val controls = when (tag) {
            "quadbezto" -> listOf(body.obj("cp"))
            "cubbezto" -> listOf(body.obj("cp1"), body.obj("cp2"))
            else -> emptyList()
        }
        val from = vertices.lastOrNull()
        if (controls.isEmpty() || from == null) {
            vertices += end
            continue
        }
        val curve = bezierSamples(
            listOf(from.x to from.y) + controls.map { vertexAt(it) } + listOf(end.x to end.y),
        )
        if (curve == null) {
            vertices += end
        } else {
            // Drop the sampled start: it is the previous vertex, already in the list.
            curve.drop(1).forEach { (x, y) -> vertices += PathVertex(x, y, end.pressure) }
        }
    }
    return vertices
}

/** A `{pos: [x, y], pressure}` element, or null when it is missing or malformed. */
private fun pathVertexAt(element: JsonValue?): PathVertex? {
    val (x, y) = vertexAt(element?.obj("pos")) ?: return null
    return PathVertex(x, y, element?.obj("pressure")?.num() ?: 1.0)
}

/** A point in Rnote px, before [pxToPt]. */
private typealias Vertex = Pair<Double, Double>

/** A bare `[x, y]` array as a [Vertex], or null when it is missing or not two numbers. */
private fun vertexAt(value: JsonValue?): Vertex? {
    val pair = value?.arr() ?: return null
    val x = pair.getOrNull(0)?.num() ?: return null
    val y = pair.getOrNull(1)?.num() ?: return null
    return x to y
}

/** The centre of a shape that carries its own affine — its translation, rotation dropped. */
private fun shapeCentre(shape: JsonValue): Vertex? {
    val affine = shape.obj("transform")?.obj("affine")?.arr() ?: return null
    if (affine.size != 9) return null
    return affineTranslation(affine.map { it.num() ?: return null })
}

/** A `rect`'s four corners from its centre and half extents, closed by repeating the first. */
private fun boxCorners(shape: JsonValue): List<Vertex>? {
    val (cx, cy) = shapeCentre(shape) ?: return null
    val (hx, hy) = vertexAt(shape.obj("cuboid")?.obj("half_extents")) ?: return null
    return closed(
        listOf(
            cx - hx to cy - hy,
            cx + hx to cy - hy,
            cx + hx to cy + hy,
            cx - hx to cy + hy,
        ),
    )
}

/** An `ellipse` as [ELLIPSE_SAMPLES] points around its perimeter, closed back onto the first. */
private fun ellipseSamples(shape: JsonValue): List<Vertex>? {
    val (cx, cy) = shapeCentre(shape) ?: return null
    val (rx, ry) = vertexAt(shape.obj("radii")) ?: return null
    return closed(
        (0 until ELLIPSE_SAMPLES).map { i ->
            val angle = 2.0 * PI * i / ELLIPSE_SAMPLES
            cx + rx * cos(angle) to cy + ry * sin(angle)
        },
    )
}

/**
 * A Bézier as [BEZIER_SAMPLES] points, endpoints included, evaluated by De Casteljau so the same
 * code serves the quadratic (three controls) and cubic (four) cases.
 *
 * @param controls The control points in order; a null among them means the shape was malformed.
 */
private fun bezierSamples(controls: List<Vertex?>): List<Vertex>? {
    if (controls.any { it == null }) return null
    val points = controls.filterNotNull()
    return (0 until BEZIER_SAMPLES).map { i ->
        var level = points
        val t = i.toDouble() / (BEZIER_SAMPLES - 1)
        while (level.size > 1) {
            level = level.zipWithNext { a, b ->
                a.first + (b.first - a.first) * t to a.second + (b.second - a.second) * t
            }
        }
        level.single()
    }
}

/**
 * An `arrow`'s shaft and head as one open polyline: `start` -> `tip` -> left barb -> `tip` ->
 * right barb.
 *
 * A `.xopp` stroke has no move-to, so the tip is retraced to get back out to the second barb and
 * keep the whole arrow a single stroke. Each barb leaves the tip at [ARROW_BARB_DEGREES] off the
 * shaft direction, [ARROW_BARB_WIDTHS] stroke widths long but never longer than a quarter of the
 * shaft, matching how upstream sizes the head.
 */
private fun arrowVertices(shape: JsonValue, widthPx: Double): List<Vertex>? {
    val (sx, sy) = vertexAt(shape.obj("start")) ?: return null
    val tip = vertexAt(shape.obj("tip")) ?: return null
    val (tx, ty) = tip
    val shaft = hypot(tx - sx, ty - sy)
    if (shaft == 0.0) return listOf(sx to sy, tip)
    val dx = (tx - sx) / shaft
    val dy = (ty - sy) / shaft
    val barb = minOf(shaft / 4.0, ARROW_BARB_WIDTHS * widthPx)
    val barbs = listOf(ARROW_BARB_DEGREES, -ARROW_BARB_DEGREES).map { degrees ->
        val a = Math.toRadians(degrees)
        tx + (dx * cos(a) - dy * sin(a)) * barb to ty + (dx * sin(a) + dy * cos(a)) * barb
    }
    return listOf(sx to sy, tip, barbs[0], tip, barbs[1])
}

/** A `polyline`/`polygon`'s vertices: its `start` followed by every point in `path`. */
private fun polylineVertices(shape: JsonValue): List<Vertex>? {
    val start = vertexAt(shape.obj("start")) ?: return null
    return listOf(start) + shape.obj("path")?.arr().orEmpty().map { vertexAt(it) ?: return null }
}

/** The same vertices with the first repeated last, so a closed shape's fill has an edge to close. */
private fun closed(vertices: List<Vertex>): List<Vertex> =
    if (vertices.isEmpty() || vertices.first() == vertices.last()) vertices else vertices + vertices.first()

/**
 * The pen settings shared by every stroke kind, read once out of the single-tagged `style` object
 * (`smooth` or `textured`) so `brushstroke` and `shapestroke` read them the same way.
 */
private class StrokeStyle(
    val widthPx: Double,
    val color: Int,
    val lineStyle: LineStyle,
    val capStyle: String,
    val fill: Int?,
) {
    /** Finish a [Stroke] from already-converted [points], taking the tool from the source layer. */
    fun toStroke(source: RnoteStroke, points: List<StrokePoint>, uniformWidth: Boolean): Stroke = Stroke(
        tool = if (source.layer == "highlighter") Tool.HIGHLIGHTER else Tool.PEN,
        color = color,
        capStyle = capStyle,
        points = points,
        uniformWidth = uniformWidth,
        lineStyle = lineStyle,
        fill = fill,
        extraAttrs = emptyMap(),
    )
}

/** Read a stroke body's `style` tag into the settings every converter needs. */
private fun strokeStyle(body: JsonValue?): StrokeStyle {
    val style = singleTagValue(body?.obj("style"))
    return StrokeStyle(
        widthPx = style?.obj("stroke_width")?.num() ?: DEFAULT_STROKE_WIDTH_PX,
        color = strokeColor(style),
        lineStyle = lineStyleOf(style?.obj("line_style")?.str()),
        capStyle = capStyleOf(style?.obj("line_cap")?.str()),
        fill = fillAlpha(style?.obj("fill_color")),
    )
}

/** The single-key tag of an enum-with-payload object, as its name and its value. */
private fun singleTag(tagged: JsonValue?): Pair<String, JsonValue>? =
    (tagged as? JsonObject)?.members?.entries?.firstOrNull()?.let { it.key to it.value }

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

/**
 * Rnote's `line_style` name → our [LineStyle].
 *
 * Upstream (`rnote-compose`, `style::smooth::LineStyle`) writes exactly five names: `solid`,
 * `dotted`, `dashed_narrow`, `dashed_equidistant` and `dashed_wide`. `.xopp` has one dash pattern,
 * so all three `dashed_*` widths collapse onto [LineStyle.DASHED]; `solid`, an absent value and any
 * name a newer Rnote adds draw [LineStyle.PLAIN].
 */
private fun lineStyleOf(name: String?): LineStyle = when (name) {
    "dotted" -> LineStyle.DOTTED
    "dashed_narrow", "dashed_equidistant", "dashed_wide" -> LineStyle.DASHED
    else -> LineStyle.PLAIN
}

/**
 * Rnote's `line_cap` name → the `.xopp` `capStyle` word.
 *
 * Upstream (`rnote-compose`, `style::smooth::LineCap`) has exactly two values: `rounded` maps onto
 * `round` and `straight` onto `butt`. `.xopp`'s third word, `square`, has no Rnote source. An
 * absent value — and any name a newer Rnote adds — takes Rnote's own default, `round`.
 */
private fun capStyleOf(name: String?): String = when (name) {
    "straight" -> "butt"
    else -> "round"
}
