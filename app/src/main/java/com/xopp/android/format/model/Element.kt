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

/**
 * A `<stroke>`. The on-disk `width` attribute is either a single value (constant width) or one
 * value per vertex (pressure). [uniformWidth] records which form the source used so we re-emit
 * it faithfully. [extraAttrs] preserves attributes we don't interpret (`ts`, `fn`, future ones)
 * in their original order.
 */
data class Stroke(
    val tool: Tool,
    val color: Int,
    val capStyle: String?,
    val points: List<StrokePoint>,
    val uniformWidth: Boolean,
    val extraAttrs: Map<String, String> = emptyMap(),
) : Element

/** A `<text>` box: content anchored at (x, y) top-left, in the given font/size (pt)/colour. */
data class TextElement(
    val font: String,
    val size: Double,
    val x: Double,
    val y: Double,
    val color: Int,
    val content: String,
) : Element

/** An `<image>`: raw encoded bytes (PNG/JPEG) placed in a pt bounding box. */
data class ImageElement(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val data: ByteArray,
) : Element {
    override fun equals(other: Any?): Boolean =
        other is ImageElement &&
            left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom &&
            data.contentEquals(other.data)

    override fun hashCode(): Int =
        31 * (31 * left.hashCode() + top.hashCode()) + data.contentHashCode()
}

/** A `<teximage>`: LaTeX source rendered into a pt bounding box. */
data class TexImageElement(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val latex: String,
    val color: Int,
) : Element
