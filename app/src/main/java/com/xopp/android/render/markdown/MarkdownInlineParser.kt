package com.xopp.android.render.markdown

/**
 * Turns a block's raw inline source (`**bold** and `code``) into the flat [StyledRun] list layout
 * measures. Pure and dependency-free, and the second half of the split [MarkdownParser] describes:
 * that pass decides block structure, this one decides character styling, and neither measures
 * anything.
 *
 * It runs in two stages. A **scanner** ([InlineScanner]) walks the source once, resolving the things
 * that are decided locally — backslash escapes, code spans, and link/image labels — and emitting the
 * rest as text tokens plus runs of `*`/`_` delimiters. A **delimiter pass**
 * ([InlineEmphasis]) then matches those runs against each other, marking the text between a matched
 * pair bold or italic; nesting works because the marks accumulate on the same tokens. Anything that
 * never finds a partner stays literal, which is what keeps `2 * 3 * 4` and a stray `_` intact.
 *
 * **Link URLs are dropped.** `[label](url)` renders as just `label`. The output of this pipeline is
 * a printed PDF page, where a URL is neither clickable nor wanted mid-sentence, so the label is the
 * whole of the information that survives. Images (`![alt](url)`) render their alt text the same way.
 */
object MarkdownInlineParser {

    /** Parse [source] into styled runs. Blank input yields an empty list. */
    fun parse(source: String): List<StyledRun> {
        if (source.isEmpty()) return emptyList()
        val tokens = InlineScanner(source).scan()
        InlineEmphasis.resolve(tokens)
        return merge(tokens)
    }

    /**
     * Flatten tokens to runs, folding unmatched delimiters back into literal text and joining
     * neighbours that share a style, so a wrapper never sees a needless run boundary.
     */
    private fun merge(tokens: List<InlineToken>): List<StyledRun> {
        val runs = mutableListOf<StyledRun>()
        for (token in tokens) {
            val run = token.toRun() ?: continue
            if (run.isEmpty) continue
            val last = runs.lastOrNull()
            if (last != null && last.sameStyle(run)) {
                runs[runs.size - 1] = last.copy(text = last.text + run.text)
            } else {
                runs.add(run)
            }
        }
        return runs
    }

    private fun StyledRun.sameStyle(other: StyledRun) =
        bold == other.bold && italic == other.italic && code == other.code
}

/**
 * One scanned piece of inline source. [Text] is literal characters carrying the styles applied so
 * far; [Delimiter] is an unresolved run of `*` or `_` that the emphasis pass may turn into styling
 * or leave as literal text.
 */
internal sealed interface InlineToken {

    fun toRun(): StyledRun?

    class Text(val text: String, var bold: Boolean = false, var italic: Boolean = false, val code: Boolean = false) : InlineToken {
        override fun toRun() = StyledRun(text, bold, italic, code)
    }

    /**
     * A run of [count] identical [char]s. [canOpen]/[canClose] come from CommonMark's flanking
     * rules; [remaining] shrinks as the emphasis pass spends the run, and whatever is left prints
     * literally with the styles it ended up inside.
     */
    class Delimiter(
        val char: Char,
        val count: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
        var remaining: Int = count,
        var bold: Boolean = false,
        var italic: Boolean = false,
    ) : InlineToken {
        override fun toRun() =
            if (remaining == 0) null else StyledRun(char.toString().repeat(remaining), bold, italic)
    }
}
