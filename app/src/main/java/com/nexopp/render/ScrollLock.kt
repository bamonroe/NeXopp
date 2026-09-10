package com.nexopp.render

/**
 * Which axis the viewport refuses to pan along — the "scroll lock" the top bar toggles.
 *
 * Locking an axis is about keeping a hand-drawn line of thought straight: while you annotate down a
 * long page, a stray sideways component in every pan slowly walks the document off-centre. Locking
 * horizontal scrolling pins the sideways position so a one-finger drag can only travel up and down;
 * locking vertical does the mirror image for a wide, side-scrolled document.
 *
 * **It governs the one-finger pan only** — the Hand tool, or a finger with drawing turned off, plus
 * the fling that pan launches (only one-finger pans fling). A **two-finger pan is never locked**:
 * putting a second finger down is the deliberate "take me over there" gesture, and it stays the way
 * out of a lock rather than something the lock takes away. Neither is the mouse wheel, which has no
 * second-finger equivalent to escape with. Deliberate navigation (jumping to a page, a search hit, a
 * zoom re-anchor) is likewise untouched: those are destinations the user asked for by name rather
 * than a direction they dragged in.
 *
 * Nothing applies the lock implicitly — [ViewportState.scrollByWithinLock] is the only enforcement,
 * so which moves it governs is a decision each call site makes out loud.
 *
 * @property label How the mode reads in the settings list and the top bar's menu.
 * @property blocksX Sideways one-finger panning is frozen.
 * @property blocksY Up/down one-finger panning is frozen.
 */
enum class ScrollLock(val label: String, val blocksX: Boolean, val blocksY: Boolean) {
    /** Pans go wherever the fingers do. */
    NONE("Unlocked", blocksX = false, blocksY = false),

    /** Horizontal scrolling is locked: a one-finger pan can only move the document up and down. */
    HORIZONTAL("Lock horizontal", blocksX = true, blocksY = false),

    /** Vertical scrolling is locked: a one-finger pan can only move the document side to side. */
    VERTICAL("Lock vertical", blocksX = false, blocksY = true);

    /** [dx] as a one-finger pan is allowed to use it — zero on a locked horizontal axis. */
    fun allowX(dx: Float): Float = if (blocksX) 0f else dx

    /** [dy] as a one-finger pan is allowed to use it — zero on a locked vertical axis. */
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
            HORIZONTAL -> "Horizontal scrolling locked — one-finger pans move up and down only"
            VERTICAL -> "Vertical scrolling locked — one-finger pans move side to side only"
        }
}
