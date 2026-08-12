package com.nexopp.format.rnote

import kotlin.math.roundToInt

/**
 * Shared conversion primitives every `.rnote` stroke converter needs: the px↔pt scale, the
 * colour packing, and the affine-transform translation. See `docs/architecture.md`,
 * "The stroke & pen mapping (measured against the fixture twins)".
 */

/** Rnote measures in px at 96 dpi; `.xopp` measures in pt at 72 dpi. */
const val PX_PER_PT = 4.0 / 3.0

/**
 * Convert a Rnote px length to `.xopp` points.
 *
 * @param px The length in Rnote px (96 dpi).
 * @return The same length in `.xopp` pt (72 dpi).
 */
fun pxToPt(px: Double): Double = px / PX_PER_PT

/**
 * Convert a `.xopp` point length to Rnote px.
 *
 * @param pt The length in `.xopp` pt (72 dpi).
 * @return The same length in Rnote px (96 dpi).
 */
fun ptToPx(pt: Double): Double = pt * PX_PER_PT

/**
 * Pack this Rnote colour into the `0xAARRGGBB` int the rest of `format/` uses (the same layout
 * [com.nexopp.format.XoppColor.parse] produces). Channels outside `0.0`–`1.0` are clamped.
 *
 * @return The colour as an ARGB int.
 */
fun RnoteColor.toXopp(): Int {
    fun channel(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return (channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/**
 * Unpack an ARGB int into Rnote's four `0.0`–`1.0` channels — the inverse of [toXopp].
 *
 * @param argb The colour in `0xAARRGGBB` form.
 * @return The equivalent [RnoteColor].
 */
fun rnoteColorOf(argb: Int): RnoteColor = RnoteColor(
    r = ((argb ushr 16) and 0xFF) / 255.0,
    g = ((argb ushr 8) and 0xFF) / 255.0,
    b = (argb and 0xFF) / 255.0,
    a = ((argb ushr 24) and 0xFF) / 255.0,
)

/**
 * Read the translation out of a `transform.affine` — the 9-element 3x3 matrix a `bitmapimage` or
 * `textstroke` carries. Rnote serialises it through nalgebra, which stores a matrix
 * **column-major**, so the translation sits at indices 6 and 7 (not 2 and 5). The fixture twins
 * confirm it: `text-image.rnote`'s textstroke is `[1,0,0,-0,1,0,96,96,1]` and its `.xopp` twin
 * places the text at 72 pt = 96 px.
 *
 * @param affine The 9-element column-major matrix as parsed from the JSON.
 * @return The (x, y) translation.
 * @throws IllegalArgumentException If the matrix is not 9 elements long.
 */
fun affineTranslation(affine: List<Double>): Pair<Double, Double> {
    require(affine.size == 9) { "malformed transform.affine" }
    return affine[6] to affine[7]
}
