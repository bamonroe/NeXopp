package com.nexopp.render

/**
 * Which axis the viewport refuses to pan along — the "scroll lock" the top bar toggles.
 *
 * Locking an axis is about keeping a hand-drawn line of thought straight: while you annotate down a
 * long page, a stray sideways component in every pan slowly walks the document off-centre. Locking
 * horizontal scrolling pins the sideways position so a two-finger drag can only travel up and down;
 * locking vertical does the mirror image for a wide, side-scrolled document.
 *
 * The lock only ever constrains a *pan* — a drag, its fling, or a mouse wheel notch, all of which
 * reach the viewport through [ViewportState.scrollBy]. Deliberate navigation (jumping to a page, a
 * search hit, a zoom re-anchor) still goes wherever it must, because that is a destination the user
 * asked for by name rather than a direction they dragged in.
 *
 * @property label How the mode reads in the settings list and the top bar's menu.
 * @property blocksX Sideways panning is frozen.
 * @property blocksY Up/down panning is frozen.
 */
enum class ScrollLock(val label: String, val blocksX: Boolean, val blocksY: Boolean) {
    /** Pans go wherever the fingers do. */
    NONE("Unlocked", blocksX = false, blocksY = false),

    /** Horizontal scrolling is locked: a pan can only move the document up and down. */
    HORIZONTAL("Lock horizontal", blocksX = true, blocksY = false),

    /** Vertical scrolling is locked: a pan can only move the document side to side. */
    VERTICAL("Lock vertical", blocksX = false, blocksY = true);

    /** [dx] as the viewport is allowed to use it — zero on a locked horizontal axis. */
    fun allowX(dx: Float): Float = if (blocksX) 0f else dx

    /** [dy] as the viewport is allowed to use it — zero on a locked vertical axis. */
    fun allowY(dy: Float): Float = if (blocksY) 0f else dy

    /**
     * The mode one tap of the top-bar button away: off → horizontal → vertical → off, so the whole
     * setting is reachable without opening its menu.
     */
    fun next(): ScrollLock = entries[(ordinal + 1) % entries.size]

    /** A sentence for the button's tooltip/description, saying what a pan can still do. */
    val description: String
        get() = when (this) {
            NONE -> "Scrolling unlocked"
            HORIZONTAL -> "Horizontal scrolling locked — pans move up and down only"
            VERTICAL -> "Vertical scrolling locked — pans move side to side only"
        }
}
