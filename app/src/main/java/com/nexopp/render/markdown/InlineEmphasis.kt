package com.nexopp.render.markdown

/**
 * The second stage of [MarkdownInlineParser]: pair up the delimiter runs [InlineScanner] left
 * behind and stamp the text between each pair bold or italic.
 *
 * It is CommonMark's delimiter-stack walk in miniature. Scanning left to right, each run that can
 * close looks back for the nearest run of the same character that can open; a match spends two
 * delimiters from each side for **strong** or one for *emphasis*, and the styling is applied to
 * every token in between. Because a token can be stamped more than once, nesting needs no recursion
 * — `**bold *and italic* **` simply gets both marks on the inner tokens. Unspent delimiters keep
 * their `remaining` count and print literally.
 */
internal object InlineEmphasis {

    fun resolve(tokens: MutableList<InlineToken>) {
        for (closer in tokens.indices) {
            val end = tokens[closer] as? InlineToken.Delimiter ?: continue
            if (!end.canClose) continue
            while (end.remaining > 0) {
                val opener = findOpener(tokens, closer) ?: break
                pair(tokens, opener, closer)
            }
        }
    }

    /** The nearest opening run before [closer] with the same character and delimiters left to spend. */
    private fun findOpener(tokens: List<InlineToken>, closer: Int): Int? {
        val end = tokens[closer] as InlineToken.Delimiter
        for (j in closer - 1 downTo 0) {
            val start = tokens[j] as? InlineToken.Delimiter ?: continue
            if (start.char != end.char || !start.canOpen || start.remaining == 0) continue
            if (!balanced(start, end)) continue
            return j
        }
        return null
    }

    /**
     * CommonMark's "rule of three": when one side of a pair can both open and close, a match is only
     * legal unless the sum of the two run lengths is a multiple of three (and not both multiples
     * themselves). It is what stops `*a**b*` from mis-nesting.
     */
    private fun balanced(start: InlineToken.Delimiter, end: InlineToken.Delimiter): Boolean {
        if (!(end.canOpen || start.canClose)) return true
        if ((start.count + end.count) % 3 != 0) return true
        return start.count % 3 == 0 && end.count % 3 == 0
    }

    /** Spend one or two delimiters from each side and style everything between them. */
    private fun pair(tokens: MutableList<InlineToken>, opener: Int, closer: Int) {
        val start = tokens[opener] as InlineToken.Delimiter
        val end = tokens[closer] as InlineToken.Delimiter
        val strong = start.remaining >= 2 && end.remaining >= 2
        val spend = if (strong) 2 else 1
        start.remaining -= spend
        end.remaining -= spend
        for (k in opener + 1 until closer) style(tokens[k], strong)
    }

    private fun style(token: InlineToken, strong: Boolean) {
        when (token) {
            is InlineToken.Text -> if (strong) token.bold = true else token.italic = true
            is InlineToken.Delimiter -> if (strong) token.bold = true else token.italic = true
        }
    }
}
