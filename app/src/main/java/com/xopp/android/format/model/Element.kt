package com.xopp.android.format.model

/** A drawable inside a [Layer]: stroke, text, image, or LaTeX image. */
sealed interface Element

/** The pen tool that produced a stroke. */
enum class Tool(val xml: String) {
    PEN("pen"),
    HIGHLIGHTER("highlighter"),
    ERASER("eraser");

    companion object {
        fun fromXml(value: String?): Tool =
            entries.firstOrNull { it.xml == value } ?: PEN
    }
}

/** One vertex of a stroke: position (pt) and the pen width there (pt). */
data class StrokePoint(val x: Double, val y: Double, val width: Double)

/** A `<stroke>`'s line pattern (`style` attribute); [PLAIN] is a solid line and omits the attr. */
enum class LineStyle(val xml: String) {
    PLAIN("plain"),
    DASHED("dash"),
    DASH_DOT("dashdot"),
    DOTTED("dot");

    companion object {
        fun fromXml(value: String?): LineStyle =
            entries.firstOrNull { it.xml == value } ?: PLAIN
    }
}

/**
 * A `<stroke>`. The on-disk `width` attribute is either a single value (constant width) or one
 * value per vertex (pressure). [uniformWidth] records which form the source used so we re-emit
 * it faithfully. [lineStyle] mirrors the desktop `style` attribute (dashed/dotted patterns);
 * [fill] is the desktop `fill` alpha (0..255) painted inside a closed stroke, or null for no fill.
 * [extraAttrs] preserves attributes we don't interpret (`ts`, `fn`, future ones) in their
 * original order.
 */
data class Stroke(
    val tool: Tool,
    val color: Int,
    val capStyle: String?,
    val points: List<StrokePoint>,
    val uniformWidth: Boolean,
    val lineStyle: LineStyle = LineStyle.PLAIN,
    val fill: Int? = null,
    val extraAttrs: Map<String, String> = emptyMap(),
) : Element

/**
 * A `<text>` box: content anchored at (x, y) top-left, in the given font/size (pt)/colour.
 * [extraAttrs] preserves attributes we don't interpret — desktop text boxes can carry the same
 * audio `fn`/`ts` pair strokes do, so dropping them would lose an audio link on save.
 */
data class TextElement(
    val font: String,
    val size: Double,
    val x: Double,
    val y: Double,
    val color: Int,
    val content: String,
    val extraAttrs: Map<String, String> = emptyMap(),
) : Element

/**
 * A layer child whose tag we don't model — a vendor or future element in a desktop-written file.
 * Captured verbatim ([attrs] in source order, [body] as the raw inner text) and re-emitted
 * untouched in document order, so an unknown element survives a load/save round trip.
 */
data class RawElement(
    val name: String,
    val attrs: Map<String, String> = emptyMap(),
    val body: String = "",
) : Element

/** An `<image>`: raw encoded bytes (PNG/JPEG) placed in a pt bounding box. */
data class ImageElement(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val data: ByteArray,
    /** Attributes on `<image>` we don't interpret, kept in source order so they round-trip. */
    val extraAttrs: Map<String, String> = emptyMap(),
) : Element {
    override fun equals(other: Any?): Boolean =
        other is ImageElement &&
            left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom &&
            data.contentEquals(other.data)

    override fun hashCode(): Int =
        31 * (31 * left.hashCode() + top.hashCode()) + data.contentHashCode()
}

/**
 * A `<teximage>`: LaTeX source (the `text` attribute) rendered into a pt bounding box.
 *
 * [data] is the desktop-rendered PNG carried in the element body, kept verbatim so a file
 * round-trips without losing its rendering; null when the source had no body.
 *
 * [latexInAttribute] records where the source *was*: newer files put it in the `text` attribute,
 * older ones in the element body. Like [Stroke.uniformWidth] it exists so we re-emit the shape the
 * file was authored in rather than silently converting it.
 */
data class TexImageElement(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val latex: String,
    val color: Int,
    val data: ByteArray? = null,
    val latexInAttribute: Boolean = true,
    /** Attributes on `<teximage>` we don't interpret, kept in source order so they round-trip. */
    val extraAttrs: Map<String, String> = emptyMap(),
) : Element {
    override fun equals(other: Any?): Boolean =
        other is TexImageElement &&
            left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom &&
            latex == other.latex && color == other.color &&
            (data ?: ByteArray(0)).contentEquals(other.data ?: ByteArray(0))

    override fun hashCode(): Int =
        31 * (31 * left.hashCode() + latex.hashCode()) + (data?.contentHashCode() ?: 0)
}
