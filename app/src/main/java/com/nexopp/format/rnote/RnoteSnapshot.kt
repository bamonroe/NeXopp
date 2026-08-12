package com.nexopp.format.rnote

import com.nexopp.format.json.JsonObject
import com.nexopp.format.json.JsonValue

/**
 * RGBA as Rnote stores it: four `0.0`–`1.0` channels, not 8-bit components.
 */
data class RnoteColor(val r: Double, val g: Double, val b: Double, val a: Double)

/**
 * The page format of the (single, infinite) Rnote canvas.
 *
 * @property width Page width in Rnote's document units.
 * @property height Page height in Rnote's document units.
 * @property dpi The document DPI the units are quoted at.
 * @property orientation `portrait` or `landscape`, as written.
 */
data class RnoteFormat(
    val width: Double,
    val height: Double,
    val dpi: Double,
    val orientation: String,
)

/**
 * The canvas background: a fill colour plus a repeating pattern.
 *
 * @property color The fill behind everything.
 * @property pattern The pattern name as written (`none`, `dots`, `lines`, `grid`, …).
 * @property patternWidth Horizontal pattern pitch (`pattern_size[0]`).
 * @property patternHeight Vertical pattern pitch (`pattern_size[1]`).
 * @property patternColor The colour the pattern is drawn in.
 */
data class RnoteBackground(
    val color: RnoteColor,
    val pattern: String,
    val patternWidth: Double,
    val patternHeight: Double,
    val patternColor: RnoteColor,
)

/**
 * One stroke slot, paired with its chrono entry. The body is left as raw JSON: interpreting a
 * brush stroke's path or a text stroke's runs belongs to the stroke→`Element` mapping above.
 *
 * @property index The slot position in `stroke_components`, which also indexes `chrono_components`.
 * @property kind The stroke object's single tag key — `brushstroke`, `shapestroke`, `textstroke`,
 *   `vectorimage`, `bitmapimage`, or whatever a newer Rnote writes.
 * @property body The value of that tag, uninterpreted.
 * @property z The chrono `t` counter — painters order, ascending.
 * @property layer `user_layer` for an ordinary stroke, else `highlighter`, `image` or `document`.
 * @property userLayer The user layer number when [layer] is `user_layer`, else null.
 */
data class RnoteStroke(
    val index: Int,
    val kind: String,
    val body: JsonValue,
    val z: Long,
    val layer: String,
    val userLayer: Int?,
)

/**
 * The `engine_snapshot` payload as a plain intermediate model — the step between
 * [RnoteContainer]'s raw JSON and the NeXopp document model. It keeps Rnote's own vocabulary
 * (one infinite canvas, chrono-ordered strokes, named layers) rather than pages and layers, so
 * the lossy part of the conversion stays in one place above this.
 *
 * Tolerant of the 0.6–0.14 payload shapes described in `docs/architecture.md`, section
 * "The `.rnote` format — container & serialisation": `format`/`background`/`layout` are looked up
 * under `document.config` first and on `document` itself as a fallback, and `camera` /
 * `snap_positions` are ignored.
 *
 * @property docX Canvas origin x.
 * @property docY Canvas origin y.
 * @property docWidth Canvas extent width — the whole scrollable area, not a page.
 * @property docHeight Canvas extent height.
 * @property strokes Every non-empty slot, already sorted into painters order.
 */
data class RnoteSnapshot(
    val format: RnoteFormat,
    val background: RnoteBackground,
    val layout: String,
    val docX: Double,
    val docY: Double,
    val docWidth: Double,
    val docHeight: Double,
    val strokes: List<RnoteStroke>,
) {
    companion object {

        /**
         * Parse an `engine_snapshot` object (as handed out by [RnoteContainer.open]).
         *
         * @param snapshot The `data.engine_snapshot` object.
         * @return The intermediate model, strokes in ascending z order.
         * @throws IllegalArgumentException If a required key is missing or has the wrong type; the
         *   message names the key's dotted path.
         */
        fun parse(snapshot: JsonValue): RnoteSnapshot {
            val document = snapshot.obj("document") ?: missing("document")
            val config = document.obj("config") ?: document
            return RnoteSnapshot(
                format = parseFormat(config.obj("format") ?: missing("document.format")),
                background = parseBackground(
                    config.obj("background") ?: missing("document.background"),
                ),
                layout = text(config, "layout", "document.layout"),
                docX = number(document, "x", "document.x"),
                docY = number(document, "y", "document.y"),
                docWidth = number(document, "width", "document.width"),
                docHeight = number(document, "height", "document.height"),
                strokes = parseStrokes(snapshot),
            )
        }
    }
}

/** The document format block; `orientation` is cosmetic, so a missing one defaults to portrait. */
private fun parseFormat(format: JsonValue): RnoteFormat = RnoteFormat(
    width = number(format, "width", "document.format.width"),
    height = number(format, "height", "document.format.height"),
    dpi = number(format, "dpi", "document.format.dpi"),
    orientation = format.obj("orientation")?.str() ?: "portrait",
)

/** The background block. `pattern_size` is a two-element `[width, height]` array. */
private fun parseBackground(background: JsonValue): RnoteBackground {
    val size = background.obj("pattern_size")?.arr()
        ?: missing("document.background.pattern_size")
    return RnoteBackground(
        color = parseColor(
            background.obj("color") ?: missing("document.background.color"),
            "document.background.color",
        ),
        pattern = text(background, "pattern", "document.background.pattern"),
        patternWidth = size.getOrNull(0)?.num()
            ?: missing("document.background.pattern_size[0]"),
        patternHeight = size.getOrNull(1)?.num()
            ?: missing("document.background.pattern_size[1]"),
        patternColor = parseColor(
            background.obj("pattern_color") ?: missing("document.background.pattern_color"),
            "document.background.pattern_color",
        ),
    )
}

/** All four channels are required — a half-written colour is a corrupt file, not an old one. */
private fun parseColor(color: JsonValue, path: String): RnoteColor = RnoteColor(
    r = number(color, "r", "$path.r"),
    g = number(color, "g", "$path.g"),
    b = number(color, "b", "$path.b"),
    a = number(color, "a", "$path.a"),
)

/**
 * Read `stroke_components` against the parallel `chrono_components`, skipping the null slots
 * (slot 0 is always null, and deleted strokes leave holes). An unknown tag is kept with its tag as
 * the kind rather than throwing, so a file from a newer Rnote still loads what it can.
 */
private fun parseStrokes(snapshot: JsonValue): List<RnoteStroke> {
    val slots = snapshot.obj("stroke_components")?.arr() ?: emptyList()
    val chrono = snapshot.obj("chrono_components")?.arr() ?: emptyList()
    val strokes = ArrayList<RnoteStroke>(slots.size)
    for ((index, slot) in slots.withIndex()) {
        val tagged = slot.obj("value") as? JsonObject ?: continue
        val kind = tagged.members.keys.firstOrNull()
            ?: missing("stroke_components[$index].value.<tag>")
        val entry = chrono.getOrNull(index)?.obj("value")
        val layer = entry?.obj("layer")
        strokes += RnoteStroke(
            index = index,
            kind = kind,
            body = tagged.members.getValue(kind),
            z = entry?.obj("t")?.num()?.toLong() ?: index.toLong(),
            layer = layer?.str() ?: "user_layer",
            userLayer = if (layer?.str() != null) null else userLayer(layer),
        )
    }
    return strokes.sortedWith(compareBy({ it.z }, { it.index }))
}

/** `{"user_layer":n}`, or the default layer 0 when the chrono entry is missing or null. */
private fun userLayer(layer: JsonValue?): Int =
    layer?.obj("user_layer")?.num()?.toInt() ?: 0

/** A required string member. */
private fun text(owner: JsonValue, key: String, path: String): String =
    owner.obj(key)?.str() ?: missing(path)

/** A required numeric member. */
private fun number(owner: JsonValue, key: String, path: String): Double =
    owner.obj(key)?.num() ?: missing(path)

/** Never returns — typed as [Nothing] so it can stand in for any missing value. */
private fun missing(path: String): Nothing =
    throw IllegalArgumentException("missing \"$path\" in the .rnote snapshot")
