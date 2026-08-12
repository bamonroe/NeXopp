package com.nexopp.format.rnote

import com.nexopp.format.model.Background

/**
 * The one canvas background ↔ per-page `Background.Solid` mapping, per `docs/architecture.md`
 * section "Decision (2026-08-12): canvas ↔ pages, layers & backgrounds".
 *
 * Rnote has a single background for the whole canvas; we copy it onto every page on import and
 * collapse back to page 1's on export. Only the fill colour and the ruling style cross over —
 * `pattern_size`/`pattern_color` have no `.xopp` home, and a `Pdf`/`Pixmap` background has no
 * Rnote home.
 */

/** Rnote's default pattern pitch in px, and what Xournal++'s ruled sheet renders at (24 pt). */
private const val RULED_PITCH = 32.0

/** The graph/dotted pitch in px: 14.17 pt = 5 mm, what Xournal++'s graph sheet renders at. */
private const val GRAPH_PITCH = 18.893

/** Rnote's own default pattern colour — a pale blue. */
private val DEFAULT_PATTERN_COLOR = RnoteColor(r = 0.8, g = 0.9, b = 1.0, a = 1.0)

/** Rnote pattern name → `.xopp` ruling style. Anything else imports as a plain sheet. */
private val PATTERN_TO_STYLE = mapOf(
    "none" to "plain",
    "lines" to "ruled",
    "grid" to "graph",
    "dots" to "dotted",
    "isometric_grid" to "isograph",
    "isometric_dots" to "isodotted",
)

/**
 * `.xopp` ruling style → Rnote pattern. The inverse of [PATTERN_TO_STYLE] plus the two styles
 * Rnote can't draw: `lined` degrades to `lines` (the vertical margin rule is lost) and `staves`
 * to `none` (the ruling is lost entirely).
 */
private val STYLE_TO_PATTERN = PATTERN_TO_STYLE.entries.associate { (k, v) -> v to k } + mapOf(
    "lined" to "lines",
    "staves" to "none",
)

/**
 * Convert the canvas background into the page background every imported page carries.
 *
 * @param bg The parsed `document.config.background`.
 * @return The equivalent solid page background; an unrecognised pattern becomes `plain`.
 */
fun toXoppBackground(bg: RnoteBackground): Background.Solid = Background.Solid(
    color = bg.color.toXopp(),
    style = PATTERN_TO_STYLE[bg.pattern] ?: "plain",
)

/**
 * Convert a page background into the single canvas background written on export.
 *
 * A [Background.Pdf] or [Background.Pixmap] has no Rnote equivalent, so it becomes a white
 * unpatterned canvas and the reference is dropped — warning about that belongs to the save path,
 * not here.
 *
 * @param bg The page background to export.
 * @return The canvas background, with the pattern pitch and colour Rnote renders with.
 */
fun toRnoteBackground(bg: Background): RnoteBackground {
    val (color, pattern) = when (bg) {
        is Background.Solid -> rnoteColorOf(bg.color) to (STYLE_TO_PATTERN[bg.style] ?: "none")
        else -> RnoteColor(1.0, 1.0, 1.0, 1.0) to "none"
    }
    val pitch = when (pattern) {
        "grid", "dots", "isometric_grid", "isometric_dots" -> GRAPH_PITCH
        else -> RULED_PITCH
    }
    return RnoteBackground(
        color = color,
        pattern = pattern,
        patternWidth = pitch,
        patternHeight = pitch,
        patternColor = DEFAULT_PATTERN_COLOR,
    )
}
