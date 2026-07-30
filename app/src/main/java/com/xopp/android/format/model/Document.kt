package com.xopp.android.format.model

/**
 * The in-memory analogue of a `.xopp` file. See `docs/architecture.md` for the on-disk schema
 * this mirrors. All geometry is in points (pt, 1/72 inch); origin top-left. Colors are ARGB
 * ints (`0xAARRGGBB`) — the same layout Android's `Color` uses.
 */
data class Document(
    val creator: String = "xopp_android",
    val fileVersion: String = "4",
    val pages: List<Page> = emptyList(),
    /** Base64 PNG thumbnail of page 1, verbatim, if the file carried one. */
    val preview: String? = null,
)

data class Page(
    val width: Double,
    val height: Double,
    val background: Background,
    val layers: List<Layer>,
)

/** A layer is an ordered list of drawables; document order is z-order and must be preserved. */
data class Layer(val elements: List<Element>)
