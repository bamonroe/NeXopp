package com.xopp.android.ui

/**
 * The data model behind the radial palette — the two-ring menu that pops up at the pen tip.
 *
 * It is deliberately plain Kotlin: no Compose, no Android. The rendering ([RadialPaletteMenu]) and
 * the gesture that opens it read this model, and every part of it is unit-testable on the JVM.
 *
 * A palette is two concentric rings of slots: an inner ring of [RadialRing.INNER] slots (the big,
 * easy flick targets) and an outer ring of [RadialRing.OUTER] (further out, more of them). Every
 * slot is either empty (`null`) or holds one [PaletteAction].
 */

/** The two rings of a palette, each with a fixed number of evenly-spaced slots. */
enum class RadialRing(val slotCount: Int) {
    INNER(8),
    OUTER(16),
}

/** One addressable position: a ring plus an index within it, counted clockwise from straight up. */
data class RadialSlot(val ring: RadialRing, val index: Int) {
    init {
        require(index in 0 until ring.slotCount) { "slot $index out of range for $ring" }
    }
}

/** The page-level operations a slot can invoke, mirroring the toolbar's pages popup. */
enum class PalettePageOp { NEW_AFTER, NEW_BEFORE, DUPLICATE, DELETE, NEXT, PREVIOUS }

/**
 * What a filled slot does when the flick lands on it. Each case maps onto something the editor
 * already knows how to do — a rail tool, the colour/width the pen carries, or a toolbar action —
 * so assigning a slot never introduces new behaviour, only a faster way to reach it.
 */
sealed interface PaletteAction {
    /** Make [tool] the live tool (see [EditorUiState.tool]). */
    data class SelectTool(val tool: EditorTool) : PaletteAction

    /** Flip between [tool] and the previously live tool (see [EditorUiState.toggleTool]). */
    data class ToggleTool(val tool: EditorTool) : PaletteAction

    /** Set the pen colour to this packed ARGB value. */
    data class SetColor(val argb: Int) : PaletteAction

    /** Set the pen width, in points; clamped to the toolbar's slider bounds. */
    data class SetWidth(val widthPt: Float) : PaletteAction {
        init {
            require(widthPt in PEN_WIDTH_MIN..PEN_WIDTH_MAX) { "width $widthPt out of range" }
        }
    }

    /** Undo the last edit. */
    data object Undo : PaletteAction

    /** Redo the last undone edit. */
    data object Redo : PaletteAction

    /** Show/hide the chrome (full-page view). */
    data object ToggleFullPage : PaletteAction

    /** A page-level operation. */
    data class Page(val op: PalettePageOp) : PaletteAction

    /**
     * Activate the saved [ToolPreset] with this id — the whole tool configuration in one flick.
     * The id is stored rather than the preset itself so editing a preset also updates the slot; a
     * slot naming a preset the user has since deleted simply does nothing.
     */
    data class ApplyPreset(val presetId: String) : PaletteAction

    /**
     * Activate whichever saved [ToolPreset] currently sits at position [index] (0-based) in the
     * preset list — the *position* half of [ApplyPreset]. A slot bound this way is "preset 1" for
     * good: reordering the presets changes what it fires, which is exactly the point when the ring
     * is muscle memory. A position past the end of the list simply does nothing.
     */
    data class ApplyPresetSlot(val index: Int) : PaletteAction

    /**
     * Make the palette named [paletteName] the active one and reopen the ring in place, so a second
     * flick picks from it without the pen leaving the glass. The palette is named rather than
     * indexed because reordering the list must not silently repoint the slot at a different menu; a
     * slot naming a palette the user has since deleted or renamed simply does nothing.
     */
    data class SwitchPalette(val paletteName: String) : PaletteAction
}

/**
 * One palette: the inner and outer rings, each a fixed-length list of optional actions. Instances
 * are immutable — every edit returns a new palette, which is what makes the configuration UI's
 * undo and the persisted round-trip straightforward.
 */
data class RadialPalette(
    val name: String = DEFAULT_NAME,
    val inner: List<PaletteAction?> = List(RadialRing.INNER.slotCount) { null },
    val outer: List<PaletteAction?> = List(RadialRing.OUTER.slotCount) { null },
) {
    init {
        require(inner.size == RadialRing.INNER.slotCount) { "inner ring must have ${RadialRing.INNER.slotCount} slots" }
        require(outer.size == RadialRing.OUTER.slotCount) { "outer ring must have ${RadialRing.OUTER.slotCount} slots" }
    }

    /** The action at [slot], or `null` if the slot is empty. */
    operator fun get(slot: RadialSlot): PaletteAction? = ring(slot.ring)[slot.index]

    /** The ring's slots, in clockwise order from straight up. */
    fun ring(ring: RadialRing): List<PaletteAction?> = when (ring) {
        RadialRing.INNER -> inner
        RadialRing.OUTER -> outer
    }

    /** A copy with [action] (or `null` to clear) at [slot]. */
    fun with(slot: RadialSlot, action: PaletteAction?): RadialPalette {
        val updated = ring(slot.ring).toMutableList().also { it[slot.index] = action }
        return when (slot.ring) {
            RadialRing.INNER -> copy(inner = updated)
            RadialRing.OUTER -> copy(outer = updated)
        }
    }

    /** A copy with [slot] emptied. */
    fun without(slot: RadialSlot): RadialPalette = with(slot, null)

    /** A copy with every slot emptied, keeping the palette's name. */
    fun cleared(): RadialPalette = RadialPalette(name = name)

    /** How many slots carry an action — what the renderer uses to skip an entirely empty ring. */
    val filledCount: Int get() = inner.count { it != null } + outer.count { it != null }

    /** True when nothing is assigned anywhere, i.e. opening the menu would show nothing. */
    val isEmpty: Boolean get() = filledCount == 0

    companion object {
        const val DEFAULT_NAME = "Palette"

        /**
         * The palette a fresh install starts from: the inner ring holds the tools and edits worth
         * reaching eyes-free, the outer ring the standard pen colours.
         */
        fun default(): RadialPalette = RadialPalette(
            inner = listOf(
                PaletteAction.SelectTool(EditorTool.PEN),
                PaletteAction.ToggleTool(EditorTool.ERASER),
                PaletteAction.SelectTool(EditorTool.HIGHLIGHTER),
                PaletteAction.ToggleTool(EditorTool.SELECT),
                PaletteAction.Undo,
                PaletteAction.Redo,
                PaletteAction.SelectTool(EditorTool.HAND),
                PaletteAction.ToggleFullPage,
            ),
            outer = List(RadialRing.OUTER.slotCount) { i ->
                PEN_COLORS.getOrNull(i)?.let { PaletteAction.SetColor(it) }
            },
        )
    }
}

/** Every slot of a palette, inner ring first — the iteration order the renderer and the config UI use. */
fun RadialPalette.slots(): List<Pair<RadialSlot, PaletteAction?>> =
    RadialRing.entries.flatMap { ring ->
        ring(ring).mapIndexed { i, action -> RadialSlot(ring, i) to action }
    }
