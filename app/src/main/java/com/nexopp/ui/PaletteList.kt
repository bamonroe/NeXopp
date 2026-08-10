package com.nexopp.ui

/**
 * The pure list edits behind the palette manager — adding, renaming, reordering and deleting one of
 * the user's saved [RadialPalette]s, following the same pattern as `ToolPresetList.kt`.
 *
 * A palette list is never empty: the pen always has something to open, so [removePalette] refuses to
 * delete the last entry. Every edit also carries the **active index** along with it, because moving
 * or deleting an entry shifts which palette that index points at; keeping the two together in
 * [PaletteSet] is what makes the adjustment testable off-device instead of scattered through the UI.
 */

/** The user's palettes plus which one the pen opens. The index is always inside [palettes]. */
data class PaletteSet(
    val palettes: List<RadialPalette> = listOf(RadialPalette.default()),
    val activeIndex: Int = 0,
) {
    /** The palette the pen opens — the active entry, or the first when the index is out of range. */
    val active: RadialPalette
        get() = palettes.getOrNull(activeIndex) ?: palettes.firstOrNull() ?: RadialPalette.default()

    /** A copy with at least one palette and the index clamped into range. */
    fun normalized(): PaletteSet {
        val list = palettes.ifEmpty { listOf(RadialPalette.default()) }
        return PaletteSet(list, activeIndex.coerceIn(0, list.lastIndex))
    }

    /** A copy with the palette at [index] replaced by [palette] (a no-op when [index] is out of range). */
    fun withPaletteAt(index: Int, palette: RadialPalette): PaletteSet {
        if (index !in palettes.indices) return this
        return copy(palettes = palettes.toMutableList().also { it[index] = palette })
    }
}

/** [set] with a fresh empty palette appended, named uniquely; the new palette becomes the active one. */
fun addPalette(set: PaletteSet, name: String = ""): PaletteSet {
    val chosen = uniquePaletteName(set.palettes, name.trim().ifEmpty { "Palette ${set.palettes.size + 1}" })
    val list = set.palettes + RadialPalette(name = chosen)
    return PaletteSet(list, list.lastIndex)
}

/**
 * [set] without the palette at [index]. Deleting the last remaining palette is refused (the pen
 * would have nothing to open), and the active index follows the surviving entries.
 */
fun removePalette(set: PaletteSet, index: Int): PaletteSet {
    if (set.palettes.size <= 1 || index !in set.palettes.indices) return set
    val list = set.palettes.toMutableList().also { it.removeAt(index) }
    val active = when {
        set.activeIndex > index -> set.activeIndex - 1
        else -> set.activeIndex
    }
    return PaletteSet(list, active.coerceIn(0, list.lastIndex))
}

/** [set] with the palette at [index] moved by [delta] positions; out-of-range moves are no-ops. */
fun movePalette(set: PaletteSet, index: Int, delta: Int): PaletteSet {
    val to = index + delta
    if (index !in set.palettes.indices || to !in set.palettes.indices) return set
    val list = set.palettes.toMutableList().also { it.add(to, it.removeAt(index)) }
    val active = when (set.activeIndex) {
        index -> to
        in minOf(index, to)..maxOf(index, to) -> set.activeIndex + if (to > index) -1 else 1
        else -> set.activeIndex
    }
    return PaletteSet(list, active)
}

/** [set] with the palette at [index] renamed, keeping the name unique and non-blank. */
fun renamePalette(set: PaletteSet, index: Int, name: String): PaletteSet {
    if (index !in set.palettes.indices) return set
    val wanted = name.trim().ifEmpty { RadialPalette.DEFAULT_NAME }
    val others = set.palettes.filterIndexed { i, _ -> i != index }
    return set.withPaletteAt(index, set.palettes[index].copy(name = uniquePaletteName(others, wanted)))
}

/** [set] with [index] made active (a no-op when it is out of range). */
fun activatePalette(set: PaletteSet, index: Int): PaletteSet =
    if (index in set.palettes.indices) set.copy(activeIndex = index) else set

/**
 * The palette set a stored pref file yields: the saved list when there is one, otherwise the single
 * palette a pre-list build wrote ([legacyRaw]) migrated into a one-entry list, otherwise the factory
 * default. Pure so the migration can be tested without an Android `SharedPreferences`.
 */
fun migratedPaletteSet(listRaw: String?, legacyRaw: String?, activeIndex: Int): PaletteSet {
    val saved = decodeRadialPalettes(listRaw)
    if (saved.isNotEmpty()) return PaletteSet(saved, activeIndex).normalized()
    return PaletteSet(listOf(decodeRadialPalette(legacyRaw) ?: RadialPalette.default()), 0)
}

/** [wanted], suffixed with " 2", " 3"… until no palette in [existing] already carries that name. */
private fun uniquePaletteName(existing: List<RadialPalette>, wanted: String): String {
    val taken = existing.map { it.name }.toSet()
    if (wanted !in taken) return wanted
    var n = 2
    while ("$wanted $n" in taken) n++
    return "$wanted $n"
}
