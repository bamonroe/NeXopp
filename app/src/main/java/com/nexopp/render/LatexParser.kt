package com.nexopp.render

/**
 * A dependency-free, best-effort LaTeX math parser.
 *
 * This file is intentionally **pure Kotlin** — it has NO `android.*` imports — so the whole
 * tokenize/parse stage can be unit-tested on the JVM without an emulator. It turns a LaTeX
 * math source string (as Xournal++ stores it in a `<teximage>`, e.g. `\frac{a}{b}`, `x^2`,
 * `\sqrt{x}`, `\alpha + \beta`) into a small [LatexNode] expression tree. The measuring/drawing
 * of that tree lives in [LatexRenderer], which is the only Android-dependent half.
 *
 * Parsing is deliberately forgiving: unknown `\commands` degrade to their bare name text and
 * malformed input never throws — the worst case is a slightly wrong-looking tree.
 */

/** A node in the parsed math tree. A "group" `{...}` is represented as a [Row]. */
sealed interface LatexNode

/** An ordered sequence of nodes laid out left-to-right (also used for `{...}` groups). */
data class Row(val children: List<LatexNode>) : LatexNode

/** A run of literal glyph text (a letter, digit, operator, or a `\command` mapped to Unicode). */
data class SymbolRun(val text: String) : LatexNode

/** `base^sup` — [sup] drawn smaller and raised. */
data class Superscript(val base: LatexNode, val sup: LatexNode) : LatexNode

/** `base_sub` — [sub] drawn smaller and lowered. */
data class Subscript(val base: LatexNode, val sub: LatexNode) : LatexNode

/** `base^sup_sub` — both scripts on one base. */
data class SubSup(val base: LatexNode, val sub: LatexNode, val sup: LatexNode) : LatexNode

/** `\frac{num}{den}` — [num] over [den] with a rule line between. */
data class Fraction(val num: LatexNode, val den: LatexNode) : LatexNode

/** `\sqrt{radicand}` — a radical sign with a vinculum over [radicand]. */
data class Sqrt(val radicand: LatexNode) : LatexNode

/** Pure entry point: parse a LaTeX math string into a [LatexNode] tree. Never throws. */
object LatexParser {
    fun parse(src: String): LatexNode = ParserState(tokenize(src)).parseRow()
}

/** LaTeX-command → Unicode glyph table for the commands we render as literal text. */
private val COMMANDS: Map<String, String> = buildMap {
    // Lowercase Greek
    put("alpha", "α"); put("beta", "β"); put("gamma", "γ"); put("delta", "δ")
    put("epsilon", "ε"); put("varepsilon", "ε"); put("zeta", "ζ"); put("eta", "η")
    put("theta", "θ"); put("vartheta", "ϑ"); put("iota", "ι"); put("kappa", "κ")
    put("lambda", "λ"); put("mu", "μ"); put("nu", "ν"); put("xi", "ξ")
    put("pi", "π"); put("rho", "ρ"); put("sigma", "σ"); put("tau", "τ")
    put("upsilon", "υ"); put("phi", "φ"); put("varphi", "φ"); put("chi", "χ")
    put("psi", "ψ"); put("omega", "ω")
    // Uppercase Greek
    put("Gamma", "Γ"); put("Delta", "Δ"); put("Theta", "Θ"); put("Lambda", "Λ")
    put("Xi", "Ξ"); put("Pi", "Π"); put("Sigma", "Σ"); put("Phi", "Φ")
    put("Psi", "Ψ"); put("Omega", "Ω")
    // Operators & relations
    put("times", "×"); put("cdot", "·"); put("div", "÷"); put("pm", "±")
    put("mp", "∓"); put("leq", "≤"); put("le", "≤"); put("geq", "≥")
    put("ge", "≥"); put("neq", "≠"); put("ne", "≠"); put("approx", "≈")
    put("equiv", "≡"); put("sim", "∼"); put("propto", "∝"); put("infty", "∞")
    put("rightarrow", "→"); put("to", "→"); put("leftarrow", "←")
    put("Rightarrow", "⇒"); put("Leftarrow", "⇐"); put("leftrightarrow", "↔")
    put("sum", "∑"); put("prod", "∏"); put("int", "∫"); put("oint", "∮")
    put("partial", "∂"); put("nabla", "∇"); put("in", "∈"); put("notin", "∉")
    put("forall", "∀"); put("exists", "∃"); put("subset", "⊂"); put("supset", "⊃")
    put("cup", "∪"); put("cap", "∩"); put("cong", "≅"); put("perp", "⊥")
    put("angle", "∠"); put("cdots", "⋯"); put("ldots", "…"); put("dots", "…")
    put("prime", "′"); put("circ", "∘"); put("star", "⋆"); put("ast", "∗")
    // Single-character control words (escaped literals / spaces)
    put("{", "{"); put("}", "}"); put("%", "%"); put("&", "&"); put("#", "#"); put("$", "$")
    put(",", " "); put(";", " "); put(" ", " ")
}

// --- Tokenizer ---------------------------------------------------------------------------

/** A lexical token from a LaTeX math source — the parser's input alphabet. */
private sealed interface Token {
    /** A single literal character (letter, digit, operator). */
    data class CharTok(val c: Char) : Token
    /** A `\command` name without the backslash (e.g. `"alpha"`, `"frac"`). */
    data class CommandTok(val name: String) : Token
    /** Opening brace `{` — starts a group. */
    data object LBrace : Token
    /** Closing brace `}` — ends a group. */
    data object RBrace : Token
    /** Caret `^` — superscript marker. */
    data object Caret : Token
    /** Underscore `_` — subscript marker. */
    data object Underscore : Token
}

/** Split [src] into [Token]s. Whitespace is dropped (math mode ignores it). */
private fun tokenize(src: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    while (i < src.length) {
        when (val c = src[i]) {
            '\\' -> i = readCommand(src, i + 1, out)
            '{' -> { out.add(Token.LBrace); i++ }
            '}' -> { out.add(Token.RBrace); i++ }
            '^' -> { out.add(Token.Caret); i++ }
            '_' -> { out.add(Token.Underscore); i++ }
            ' ', '\t', '\n', '\r' -> i++
            else -> { out.add(Token.CharTok(c)); i++ }
        }
    }
    return out
}

/** Read a `\command` starting at [start] (just past the backslash); returns the next index. */
private fun readCommand(src: String, start: Int, out: MutableList<Token>): Int {
    if (start >= src.length) { out.add(Token.CommandTok("")); return start }
    val first = src[start]
    if (!first.isLetter()) { out.add(Token.CommandTok(first.toString())); return start + 1 }
    var j = start
    while (j < src.length && src[j].isLetter()) j++
    out.add(Token.CommandTok(src.substring(start, j)))
    return j
}

// --- Recursive-descent parser ------------------------------------------------------------

/** Recursive-descent parser state: a cursor over [tokens] with helpers for atom/group parsing. */
private class ParserState(private val tokens: List<Token>) {
    private var i = 0

    private fun peek(): Token? = tokens.getOrNull(i)
    private fun advance(): Token? = tokens.getOrNull(i)?.also { i++ }

    /** Parse a run of atoms until end-of-input or an unconsumed `}`. */
    fun parseRow(): LatexNode {
        val items = ArrayList<LatexNode>()
        loop@ while (true) {
            when (peek()) {
                null, Token.RBrace -> break@loop
                Token.Caret -> { i++; attachScript(items, sup = parseAtom(), sub = null) }
                Token.Underscore -> { i++; attachScript(items, sup = null, sub = parseAtom()) }
                else -> items.add(parseAtom())
            }
        }
        return Row(items)
    }

    /** Parse exactly one atom: a group, a command (with its own args), or a single char. */
    private fun parseAtom(): LatexNode = when (val t = advance()) {
        null -> Row(emptyList())
        Token.LBrace -> parseRow().also { expectRBrace() }
        Token.RBrace, Token.Caret, Token.Underscore -> Row(emptyList())
        is Token.CharTok -> SymbolRun(t.c.toString())
        is Token.CommandTok -> parseCommand(t.name)
    }

    private fun parseCommand(name: String): LatexNode = when (name) {
        "frac", "dfrac", "tfrac" -> Fraction(parseAtom(), parseAtom())
        "sqrt" -> Sqrt(parseAtom())
        else -> SymbolRun(COMMANDS[name] ?: name)
    }

    private fun expectRBrace() { if (peek() == Token.RBrace) i++ }

    /** Attach a super/subscript to the most recent atom, merging into [SubSup] when possible. */
    private fun attachScript(items: MutableList<LatexNode>, sup: LatexNode?, sub: LatexNode?) {
        val base = if (items.isEmpty()) Row(emptyList()) else items.removeAt(items.size - 1)
        items.add(mergeScript(base, sup, sub))
    }
}

private fun mergeScript(base: LatexNode, sup: LatexNode?, sub: LatexNode?): LatexNode = when {
    // A second script on an already-scripted base fills in the missing side.
    base is Superscript && sub != null -> SubSup(base.base, sub, base.sup)
    base is Subscript && sup != null -> SubSup(base.base, base.sub, sup)
    sup != null -> Superscript(base, sup)
    sub != null -> Subscript(base, sub)
    else -> base
}
