package com.nexopp.render

import java.util.Locale

/**
 * Number and colour formatting for the SVG export path. SVG attribute values must use a `.`
 * decimal separator whatever the device locale is, so every number goes through [num] with
 * [Locale.ROOT] — a comma separator would silently corrupt a path's `d` attribute.
 *
 * Unlike the `.xopp` writer (which mirrors desktop's fixed 8 decimals for round-trip fidelity),
 * an SVG is a one-way flatten, so trailing zeros are trimmed to keep the file small.
 */
internal object SvgFormat {

    /** Millimetre-scale precision is plenty at pt units, and keeps the output readable. */
    private const val DECIMALS = 3

    /** A coordinate/length as a locale-independent decimal, with trailing zeros trimmed. */
    fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val s = String.format(Locale.ROOT, "%.${DECIMALS}f", v)
        val trimmed = s.trimEnd('0').trimEnd('.')
        return if (trimmed == "-0" || trimmed.isEmpty()) "0" else trimmed
    }

    /** The `#rrggbb` form of an ARGB int; alpha travels separately as an `*-opacity` attribute. */
    fun rgb(argb: Int): String = String.format(Locale.ROOT, "#%06x", argb and 0xFFFFFF)

    /** An 0..255 alpha channel as an SVG opacity in 0..1. */
    fun opacity(alpha: Int): String = num(alpha.coerceIn(0, 255) / 255.0)
}
