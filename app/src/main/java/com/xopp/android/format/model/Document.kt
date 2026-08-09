package com.xopp.android.format.model

/**
 * The in-memory analogue of a `.xopp` file. See `docs/architecture.md` for the on-disk schema
 * this mirrors. All geometry is in points (pt, 1/72 inch); origin top-left. Colors are ARGB
 * ints (`0xAARRGGBB`) — the same layout Android's `Color` uses.
 */
data class Document(
    /** The `creator` attribute from the root `<xournal>` element. */
    val creator: String = "xopp_android",
    /** The `fileversion` attribute from the root `<xournal>` element (desktop Xournal++ uses "4"). */
    val fileVersion: String = "4",
    /** The pages in document order — z-order for layers within each page. */
    val pages: List<Page> = emptyList(),
    /** Base64 PNG thumbnail of page 1, verbatim, if the file carried one. */
    val preview: String? = null,
    /** The `<title>` text the file carried; null means the writer's default banner. */
    val title: String? = null,
)

/**
 * A page in the document: dimensions in points, a background, and stacked layers.
 *
 * @param width Page width in points (1/72 inch).
 * @param height Page height in points (1/72 inch).
 * @param background The page's background (ruled sheet, pixmap image, or PDF).
 * @param layers The drawable layers in z-order (first = bottom, last = top).
 * @param extraAttrs Attributes on `<page>` we don't interpret, kept in source order so they round-trip.
 */
data class Page(
    val width: Double,
    val height: Double,
    val background: Background,
    val layers: List<Layer>,
    /** Attributes on `<page>` we don't interpret, kept in source order so they round-trip. */
    val extraAttrs: Map<String, String> = emptyMap(),
)

/**
 * A layer is an ordered list of drawables; document order is z-order and must be preserved.
 * [name] mirrors desktop Xournal++'s optional `<layer name="…">` attribute (null when the source
 * omitted it). Layer *visibility* is a view-only editor state and is deliberately not stored here,
 * since the `.xopp` format has no place for it (a hidden layer still round-trips with its content).
 *
 * @param elements The drawable elements in this layer (strokes, text, images).
 * @param name The optional layer name from the XML, or null if unnamed.
 * @param extraAttrs Attributes on `<layer>` we don't interpret, kept in source order so they round-trip.
 */
data class Layer(
    val elements: List<Element>,
    val name: String? = null,
    /** Attributes on `<layer>` we don't interpret, kept in source order so they round-trip. */
    val extraAttrs: Map<String, String> = emptyMap(),
)
