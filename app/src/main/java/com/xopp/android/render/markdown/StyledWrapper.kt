package com.xopp.android.render.markdown

import com.xopp.android.render.TextWrapping

/**
 * Which face a fragment is drawn (and measured) in. Markdown puts several of these on one line, which
 * is exactly why [StyledWrapper] exists alongside the single-font
 * [com.xopp.android.render.TextPaginator] path: the emitter maps each key to a real font.
 */
enum class RunStyle {
    REGULAR,
    BOLD,
    ITALIC,
    BOLD_ITALIC,
    CODE,
}

/** The face a run is drawn in. `code` wins over emphasis: inline code is monospaced, never bolded. */
val StyledRun.style: RunStyle
    get() = when {
        code -> RunStyle.CODE
        bold && italic -> RunStyle.BOLD_ITALIC
        bold -> RunStyle.BOLD
        italic -> RunStyle.ITALIC
        else -> RunStyle.REGULAR
    }

/**
 * A piece of one laid-out line: text in a single [style], placed at [xPt] from the line's left edge
 * and [widthPt] wide. Baselines are pagination's job, so a fragment carries no Y.
 */
data class RunFragment(
    val text: String,
    val style: RunStyle,
    val xPt: Double,
    val widthPt: Double,
)

/** Measures [text] in the face named by [style], in points. Injected so wrapping stays pure. */
typealias StyledMeasurer = (RunStyle, String) -> Float

/**
 * Wraps a flat list of [StyledRun]s into lines of positioned [RunFragment]s, measuring each stretch
 * with the metrics of its own face.
 *
 * The break rules match the plain-text path exactly — greedy word fill, whitespace-separated words,
 * and a mid-word hard break only when a word cannot fit on a line of its own — with the shared
 * character-level pieces living in [TextWrapping]. What differs is that a *word* may span several
 * runs (`**bold**tail` is one word in two faces), so words are measured segment by segment and a
 * line accumulates an x offset as it fills.
 *
 * Pure and Android-free: give it synthetic metrics and it is fully unit-testable.
 */
object StyledWrapper {

    /** Any run of inline whitespace — one word separator, however it was spelled. */
    private val WHITESPACE = Regex("[ \\t\\n\\r]+")

    /** One whitespace-free word, as the (style, text) segments it is built from. */
    private data class Word(val segments: List<Pair<RunStyle, String>>)

    /** Wrap [runs] to [maxWidth] points. Always returns at least one (possibly empty) line. */
    fun wrap(runs: List<StyledRun>, maxWidth: Double, measure: StyledMeasurer): List<List<RunFragment>> {
        val words = tokenize(runs)
        if (words.isEmpty()) return listOf(emptyList())

        val lines = mutableListOf<List<RunFragment>>()
        val line = LineBuilder(measure)
        for (word in words) {
            if (!line.isEmpty) {
                // A space joins the word to the line only if the pair still fits; otherwise wrap.
                val trial = line.copy()
                trial.append(trial.lastStyle ?: word.segments.first().first, " ")
                if (appendWord(trial, word, maxWidth)) {
                    line.adopt(trial)
                    continue
                }
                lines.add(line.take())
            }
            if (appendWord(line, word, maxWidth)) continue
            // Too long even alone: break it across lines, character by character.
            hardBreakWord(line, word, maxWidth, lines)
        }
        lines.add(line.take())
        return lines
    }

    /** Append [word] whole if the line still fits [maxWidth]; leave [line] untouched and fail if not. */
    private fun appendWord(line: LineBuilder, word: Word, maxWidth: Double): Boolean {
        val trial = line.copy()
        for ((style, text) in word.segments) {
            if (trial.widthIfAppended(style, text) > maxWidth) return false
            trial.append(style, text)
        }
        line.adopt(trial)
        return true
    }

    /** Fill [line] (and further lines into [lines]) with [word], breaking it mid-word as needed. */
    private fun hardBreakWord(
        line: LineBuilder,
        word: Word,
        maxWidth: Double,
        lines: MutableList<List<RunFragment>>,
    ) {
        for ((style, text) in word.segments) {
            val chunks = TextWrapping.hardBreak(text, maxWidth - line.widthPt, maxWidth) {
                line.measure(style, it)
            }
            chunks.forEachIndexed { index, chunk ->
                if (index > 0) lines.add(line.take())
                if (chunk.isNotEmpty()) line.append(style, chunk)
            }
        }
    }

    /**
     * Split [runs] into whitespace-free words. Runs of spaces, tabs and soft newlines all collapse to
     * a single separator, which is markdown's own inline whitespace rule (verbatim spacing belongs to
     * code blocks, which are never inline-wrapped). A word carries every (style, text) segment it
     * crosses, so styling that changes mid-word — the `**bold**tail` case — survives wrapping.
     */
    private fun tokenize(runs: List<StyledRun>): List<Word> {
        val words = mutableListOf<Word>()
        var current = mutableListOf<Pair<RunStyle, String>>()
        fun flush() {
            if (current.isNotEmpty()) {
                words.add(Word(current))
                current = mutableListOf()
            }
        }
        for (run in runs) {
            if (run.isEmpty) continue
            val pieces = run.text.split(WHITESPACE)
            pieces.forEachIndexed { index, piece ->
                if (index > 0) flush()
                if (piece.isNotEmpty()) current.add(run.style to piece)
            }
        }
        flush()
        return words
    }

    /**
     * Accumulates one line's fragments, merging adjacent same-style text so a wrapped line holds one
     * fragment per face rather than one per word. Merged text is re-measured rather than summed,
     * because kerning makes a run's width less than the sum of its pieces.
     */
    private class LineBuilder(val measure: StyledMeasurer) {
        private val fragments = mutableListOf<RunFragment>()

        /** Total advance so far — where the next fragment starts. */
        var widthPt: Double = 0.0
            private set

        val isEmpty: Boolean get() = fragments.isEmpty()
        val lastStyle: RunStyle? get() = fragments.lastOrNull()?.style

        /** The line width that appending [text] in [style] would produce, without appending it. */
        fun widthIfAppended(style: RunStyle, text: String): Double {
            val last = fragments.lastOrNull()
            return if (last != null && last.style == style) last.xPt + measure(style, last.text + text)
            else widthPt + measure(style, text)
        }

        fun append(style: RunStyle, text: String) {
            val last = fragments.lastOrNull()
            if (last != null && last.style == style) {
                val merged = last.text + text
                val width = measure(style, merged).toDouble()
                fragments[fragments.lastIndex] = last.copy(text = merged, widthPt = width)
                widthPt = last.xPt + width
            } else {
                val width = measure(style, text).toDouble()
                fragments.add(RunFragment(text, style, widthPt, width))
                widthPt += width
            }
        }

        /** A scratch copy, so a candidate append can be measured and then kept or thrown away. */
        fun copy(): LineBuilder = LineBuilder(measure).also { it.adopt(this) }

        /** Replace this line's contents with [other]'s — how a trial copy gets committed. */
        fun adopt(other: LineBuilder) {
            fragments.clear()
            fragments.addAll(other.fragments)
            widthPt = other.widthPt
        }

        /** Hand back the finished line and start an empty one. */
        fun take(): List<RunFragment> {
            val out = fragments.toList()
            fragments.clear()
            widthPt = 0.0
            return out
        }
    }
}
