package com.xopp.android.render

/**
 * The positioned text layer of an imported PDF, and the pure geometry a text-selection tool needs.
 * All boxes are in **page-local points** (origin top-left, y-down — the same frame `.xopp` geometry
 * uses), so they map to the screen through a [PageBox] exactly like strokes. Extraction (PDFBox) is
 * a thin glue layer in [PdfTextExtractor]; everything here is Android-free and JVM-tested.
 */

/** One glyph from the PDF text layer, its box in page-local points. */
data class CharBox(
    val ch: Char,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/** A whole word (whitespace-delimited) with the union box of its glyphs, in page-local points. */
data class PdfWord(
    val text: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun contains(x: Double, y: Double): Boolean = x in left..right && y in top..bottom
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
}

/**
 * Groups a run of positioned glyphs (in reading order) into words. Breaks on whitespace glyphs and
 * — because many PDFs encode inter-word spacing as a gap rather than a space glyph — also on a wide
 * horizontal gap or a vertical line change relative to the glyph height.
 */
object PdfWordGrouper {

    /** A gap wider than this fraction of the glyph height starts a new word. */
    private const val GAP_RATIO = 0.35
    /** A vertical shift larger than this fraction of the glyph height is a new line (word break). */
    private const val LINE_RATIO = 0.6

    fun group(chars: List<CharBox>): List<PdfWord> {
        val words = ArrayList<PdfWord>()
        var current = ArrayList<CharBox>()
        fun flush() {
            if (current.isNotEmpty()) { words += union(current); current = ArrayList() }
        }
        var prev: CharBox? = null
        for (c in chars) {
            if (c.ch.isWhitespace()) { flush(); prev = null; continue }
            prev?.let { if (breaksWord(it, c)) flush() }
            current.add(c)
            prev = c
        }
        flush()
        return words
    }

    private fun breaksWord(p: CharBox, c: CharBox): Boolean {
        val h = (p.bottom - p.top).coerceAtLeast(1.0)
        val gap = c.left - p.right
        val verticalShift = kotlin.math.abs(c.top - p.top)
        return gap > GAP_RATIO * h || verticalShift > LINE_RATIO * h
    }

    private fun union(glyphs: List<CharBox>): PdfWord = PdfWord(
        text = buildString { glyphs.forEach { append(it.ch) } },
        left = glyphs.minOf { it.left },
        top = glyphs.minOf { it.top },
        right = glyphs.maxOf { it.right },
        bottom = glyphs.maxOf { it.bottom },
    )
}

/**
 * Per-page positioned words extracted from an imported PDF, in reading order. Backs the text-select
 * tool: pointer positions (page-local pt) resolve to word indices, and an index range yields the
 * selected boxes (to highlight) and text (to copy). Reading order is PDFBox's position sort.
 */
class PdfTextIndex(private val pages: List<List<PdfWord>>) {

    fun words(page: Int): List<PdfWord> = pages.getOrElse(page) { emptyList() }

    /** True when at least one page carried an extractable text layer (i.e. not a scanned image PDF). */
    val hasAnyText: Boolean = pages.any { it.isNotEmpty() }

    /**
     * Index of the word to anchor a selection at [x],[y] (page-local pt): the word under the point,
     * else the nearest word by centre distance. Null only when the page has no text.
     */
    fun anchorWord(page: Int, x: Double, y: Double): Int? {
        val ws = words(page)
        if (ws.isEmpty()) return null
        ws.indexOfFirst { it.contains(x, y) }.let { if (it >= 0) return it }
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in ws.indices) {
            val dx = ws[i].centerX - x
            val dy = ws[i].centerY - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** The words in the inclusive reading-order range [a]..[b] (either order), to highlight. */
    fun rangeBoxes(page: Int, a: Int, b: Int): List<PdfWord> {
        val ws = words(page)
        if (ws.isEmpty()) return emptyList()
        val lo = minOf(a, b).coerceIn(0, ws.lastIndex)
        val hi = maxOf(a, b).coerceIn(0, ws.lastIndex)
        return ws.subList(lo, hi + 1).toList()
    }

    /** The selected text for the inclusive range [a]..[b], words joined with single spaces. */
    fun rangeText(page: Int, a: Int, b: Int): String =
        rangeBoxes(page, a, b).joinToString(" ") { it.text }
}
