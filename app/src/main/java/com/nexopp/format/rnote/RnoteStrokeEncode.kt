package com.nexopp.format.rnote

import com.nexopp.format.FontDescription
import com.nexopp.format.json.JsonArray
import com.nexopp.format.json.JsonNull
import com.nexopp.format.json.JsonNumber
import com.nexopp.format.json.JsonString
import com.nexopp.format.json.JsonValue
import com.nexopp.format.json.jsonNumbers
import com.nexopp.format.json.jsonObject
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.LineStyle
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import java.util.Base64

/**
 * The export half of the per-element mapping: [Stroke] → `brushstroke`, [TextElement] →
 * `textstroke`, [ImageElement]/[TexImageElement] → `bitmapimage`. The exact mirror of
 * `RnoteStrokeConvert.kt`, against the same JSON shapes documented in `docs/architecture.md`
 * ("The stroke & pen mapping (measured against the fixture twins)" and the feature-gap matrix).
 *
 * Every function here returns a bare stroke **body** — the value behind the single tag key. Wrapping
 * it as `{"brushstroke": …}` and pairing it with a chrono slot belongs to `RnoteSnapshotWriter.kt`,
 * which is the only thing that knows an element's position in painters order.
 *
 * An element these encoders cannot express returns **null** rather than throwing: one unreadable
 * image must not sink a whole save, and `RnoteExportWarnings` already tells the user what it cost.
 */

/** Upstream's default and the identity curve, so our baked-in widths are not re-curved on reopen. */
private const val PRESSURE_CURVE = "linear"

/** The `memory_format` every `bitmapimage` we write uses — the one the fixtures carry. */
private const val MEMORY_FORMAT = "R8g8b8a8Premultiplied"

/** Rnote's `font_weight` for a bold run; CSS numbering, and above the 600 the reader calls bold. */
private const val BOLD_WEIGHT = 700.0

/** Rnote's `font_weight` for an unstyled run. */
private const val REGULAR_WEIGHT = 400.0

/**
 * Encode one [Stroke] as a `brushstroke` body.
 *
 * The vertex list becomes Rnote's pressure path: point 0 is `path.start` and every later point a
 * `lineto` segment — always `lineto`, never a Bézier, since our model holds no curve to preserve.
 * Width is factored back the way the import measured it: `stroke_width` is the widest point (in px)
 * and each vertex's `pressure` its share of that maximum, so a uniform-width stroke comes out as
 * all-`1.0` pressures exactly as Rnote writes one.
 *
 * The tool is deliberately **not** encoded here — highlighter-ness lives in the chrono layer slot,
 * which [slotFor] decides.
 *
 * @param stroke The stroke to write.
 * @return The `brushstroke` body, or null for a stroke with **no points at all**: upstream's `path`
 *   requires a `start`, so writing one would produce a file Rnote refuses to open.
 */
fun strokeToBrushStroke(stroke: Stroke): JsonValue? {
    if (stroke.points.isEmpty()) return null
    val maxWidthPt = stroke.points.maxOf { it.width }
    fun pressure(width: Double): Double = if (maxWidthPt > 0.0) width / maxWidthPt else 1.0
    fun vertex(index: Int): JsonValue = stroke.points[index].let {
        jsonObject(
            "pos" to jsonNumbers(ptToPx(it.x), ptToPx(it.y)),
            "pressure" to JsonNumber(pressure(it.width)),
        )
    }
    return jsonObject(
        "path" to jsonObject(
            "start" to vertex(0),
            "segments" to JsonArray(
                stroke.points.indices.drop(1).map { jsonObject("lineto" to jsonObject("end" to vertex(it))) },
            ),
        ),
        "style" to jsonObject(
            "smooth" to jsonObject(
                "stroke_width" to JsonNumber(ptToPx(maxWidthPt)),
                "stroke_color" to colorJson(rnoteColorOf(stroke.color)),
                "fill_color" to fillColorJson(stroke),
                "pressure_curve" to JsonString(PRESSURE_CURVE),
                "line_style" to JsonString(lineStyleName(stroke.lineStyle)),
                "line_cap" to JsonString(lineCapName(stroke.capStyle)),
            ),
        ),
    )
}

/**
 * Encode one [TextElement] as a `textstroke` body.
 *
 * `.xopp` gives a box one uniform style, so the whole of it lands in the base `text_style` and
 * `ranged_text_attributes` is written empty — there is nothing partial to record. The Pango `font`
 * string is split back into family plus the `font_weight`/`font_style` pair by [FontDescription],
 * the same class the import side composes it with. `max_width` and `alignment` are Rnote's own
 * defaults: `<text>` has no extent, so neither has a meaning to carry across.
 *
 * @param text The text box to write.
 * @return The `textstroke` body.
 */
fun textToTextStroke(text: TextElement): JsonValue {
    val font = FontDescription.parse(text.font)
    return jsonObject(
        "text" to JsonString(text.content),
        "transform" to jsonObject("affine" to affineOf(ptToPx(text.x), ptToPx(text.y))),
        "text_style" to jsonObject(
            "font_family" to JsonString(font.family),
            "font_size" to JsonNumber(ptToPx(text.size)),
            "font_weight" to JsonNumber(if (font.bold) BOLD_WEIGHT else REGULAR_WEIGHT),
            "font_style" to JsonString(if (font.italic) "italic" else "regular"),
            "color" to colorJson(rnoteColorOf(text.color)),
            "max_width" to JsonNull,
            "alignment" to JsonString("start"),
            "ranged_text_attributes" to JsonArray(emptyList()),
        ),
    )
}

/**
 * Encode one [ImageElement] as a `bitmapimage` body.
 *
 * Rnote keeps pixels **raw**, so the embedded PNG is decoded back to an RGBA buffer, re-premultiplied
 * to match [MEMORY_FORMAT] and base64'd — the exact inverse of what [bitmapImageToImage] does on
 * import. See [bitmapImageJson] for the two nested rectangles.
 *
 * @param image The picture to write.
 * @return The `bitmapimage` body, or null when the bytes are not a PNG [RawImageCodec] can decode
 *   (a JPEG, say) — the caller skips and reports it rather than writing a broken stroke.
 */
fun imageToBitmapImage(image: ImageElement): JsonValue? = bitmapImageJson(
    data = image.data,
    left = image.left,
    top = image.top,
    right = image.right,
    bottom = image.bottom,
)

/**
 * Encode one [TexImageElement] as a `bitmapimage` body of its **already-rendered** PNG.
 *
 * Rnote has no LaTeX box, and per the feature-gap matrix exporting the rendering keeps more than
 * dropping the element outright does; the source itself is lost, which `exportWarnings` reports.
 *
 * @param tex The LaTeX box to write.
 * @return The `bitmapimage` body, or null when the box carries no rendering or one we cannot decode.
 */
fun texImageToBitmapImage(tex: TexImageElement): JsonValue? {
    val rendered = tex.data ?: return null
    return bitmapImageJson(
        data = rendered,
        left = tex.left,
        top = tex.top,
        right = tex.right,
        bottom = tex.bottom,
    )
}

/**
 * The shared `bitmapimage` body: a raw-pixel `image` block, and the pt box as Rnote's centre + half
 * extents.
 *
 * There are **two** rectangles, and they live in different spaces. The inner one is the image's own,
 * in pixels, centred on itself; the outer one is where the picture sits on the canvas, in px. That
 * is the shape `text-image.rnote` carries and the shape the import reads.
 */
private fun bitmapImageJson(
    data: ByteArray,
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
): JsonValue? {
    val raw = RawImageCodec.decodeToRaw(data) ?: return null
    val pixels = Base64.getEncoder().encodeToString(RawImageCodec.premultiply(raw.rgba))
    return jsonObject(
        "image" to jsonObject(
            "data" to JsonString(pixels),
            "rectangle" to rectangleJson(
                cx = raw.width / 2.0,
                cy = raw.height / 2.0,
                hx = raw.width / 2.0,
                hy = raw.height / 2.0,
            ),
            "pixel_width" to JsonNumber(raw.width.toDouble()),
            "pixel_height" to JsonNumber(raw.height.toDouble()),
            "memory_format" to JsonString(MEMORY_FORMAT),
        ),
        "rectangle" to rectangleJson(
            cx = ptToPx((left + right) / 2.0),
            cy = ptToPx((top + bottom) / 2.0),
            hx = ptToPx((right - left) / 2.0),
            hy = ptToPx((bottom - top) / 2.0),
        ),
    )
}

/** A `{cuboid:{half_extents},transform:{affine}}` box — Rnote's centre-and-half-extents rectangle. */
private fun rectangleJson(cx: Double, cy: Double, hx: Double, hy: Double): JsonValue = jsonObject(
    "cuboid" to jsonObject("half_extents" to jsonNumbers(hx, hy)),
    "transform" to jsonObject("affine" to affineOf(cx, cy)),
)

/**
 * A translation-only 3x3 affine as Rnote serialises one: nine elements, **column-major** (nalgebra's
 * storage order), so the translation sits at indices 6 and 7 rather than 2 and 5. Index 3 carries
 * the negative zero every fixture has there.
 *
 * @param x The x translation, in canvas px.
 * @param y The y translation, in canvas px.
 * @return The nine-element matrix.
 */
fun affineOf(x: Double, y: Double): JsonValue =
    jsonNumbers(1.0, 0.0, 0.0, -0.0, 1.0, 0.0, x, y, 1.0)

/** A four-channel Rnote colour object. */
private fun colorJson(color: RnoteColor): JsonValue = jsonObject(
    "r" to JsonNumber(color.r),
    "g" to JsonNumber(color.g),
    "b" to JsonNumber(color.b),
    "a" to JsonNumber(color.a),
)

/**
 * A stroke's `fill_color`: JSON `null` for no fill, else the **stroke colour at the fill alpha**.
 * `.xopp` records a fill as a bare alpha over the stroke colour, so that is the only hue we have to
 * give Rnote's full RGBA — the matrix marks the round trip **approx** for exactly this reason.
 */
private fun fillColorJson(stroke: Stroke): JsonValue {
    val alpha = stroke.fill ?: return JsonNull
    return colorJson(rnoteColorOf((alpha shl 24) or (stroke.color and 0x00FFFFFF)))
}

/**
 * Our [LineStyle] → Rnote's `line_style` name. Rnote has three dash widths and `.xopp` one dash
 * pattern, so both dashed spellings take the middle one, `dashed_equidistant`.
 */
private fun lineStyleName(style: LineStyle): String = when (style) {
    LineStyle.PLAIN -> "solid"
    LineStyle.DOTTED -> "dotted"
    LineStyle.DASHED, LineStyle.DASH_DOT -> "dashed_equidistant"
}

/**
 * A `.xopp` `capStyle` → Rnote's `line_cap` name. Rnote has only two caps, so `square` collapses
 * onto `straight` beside `butt`; an absent cap takes Rnote's default, `rounded`.
 */
private fun lineCapName(capStyle: String?): String = when (capStyle) {
    "butt", "square" -> "straight"
    else -> "rounded"
}
