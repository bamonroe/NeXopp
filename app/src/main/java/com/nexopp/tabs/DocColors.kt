package com.nexopp.tabs

/**
 * Which tabs are views of the *same* document, expressed as a colour.
 *
 * Tab titles are file names, and two unrelated files are often named alike, so a title alone can't
 * tell "the same document, open twice" from "two documents with the same name". A tab whose
 * [OpenTab.docKey] is shared with another open tab therefore gets a small coloured dot, and the two
 * views of one document carry the *same* colour — so duplicates are matched by eye at a glance while
 * same-named-but-different files stay plain.
 *
 * Pure and Android-free (colours are packed ARGB ints) so the assignment is unit-tested directly.
 */
object DocColors {

    /**
     * The dot palette — deliberately few, saturated and far apart in hue, since only a handful of
     * documents are ever mirrored at once and the dot is 8dp across.
     */
    val PALETTE = listOf(
        0xFF1E88E5.toInt(), // blue
        0xFFE53935.toInt(), // red
        0xFF43A047.toInt(), // green
        0xFF8E24AA.toInt(), // purple
        0xFFF9A825.toInt(), // amber
        0xFF00897B.toInt(), // teal
    )

    /**
     * Map each document key that appears more than once in [keys] to its dot colour; keys open only
     * once are absent, so a lookup returning null means "no dot". [keys] is every open tab's key
     * across *both* panes, in strip order, so the colour a document gets is stable for as long as
     * the same set of duplicates is open.
     */
    fun assign(keys: List<String>): Map<String, Int> {
        val counts = keys.groupingBy { it }.eachCount()
        val out = LinkedHashMap<String, Int>()
        for (key in keys) {
            if (counts.getValue(key) < 2 || key in out) continue
            out[key] = PALETTE[out.size % PALETTE.size]
        }
        return out
    }
}
