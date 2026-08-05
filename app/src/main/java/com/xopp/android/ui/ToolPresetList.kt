package com.xopp.android.ui

/**
 * The pure list edits behind the presets popup — saving, reordering and deleting a [ToolPreset].
 *
 * The UI ([PresetsPopupButton]) does nothing but call these and hand the result to
 * `onSettingsChange`, which keeps every rule about ids and ordering unit-testable off-device.
 */

/**
 * [presets] with [preset] appended, or replaced in place when a preset with the same id is already
 * saved. Saving under an existing name therefore overwrites it rather than growing a duplicate.
 */
fun addToolPreset(presets: List<ToolPreset>, preset: ToolPreset): List<ToolPreset> {
    val at = presets.indexOfFirst { it.id == preset.id }
    if (at < 0) return presets + preset
    return presets.toMutableList().also { it[at] = preset }
}

/**
 * [presets] with the entry at [id] rewritten from [captured] — the live tool's colour, width, style
 * and fill — while keeping that slot's own id, name and position. This is the long-press "overwrite
 * this preset" edit: the handle stays valid, so a palette slot bound to it keeps working.
 */
fun overwriteToolPreset(presets: List<ToolPreset>, id: String, captured: ToolPreset): List<ToolPreset> =
    presets.map { if (it.id == id) captured.copy(id = it.id, name = it.name) else it }

/** [presets] without the one identified by [id] (a no-op when no such preset is saved). */
fun removeToolPreset(presets: List<ToolPreset>, id: String): List<ToolPreset> =
    presets.filterNot { it.id == id }

/** [presets] with the entry at [index] moved by [delta] positions; out-of-range moves are no-ops. */
fun moveToolPreset(presets: List<ToolPreset>, index: Int, delta: Int): List<ToolPreset> {
    val to = index + delta
    if (index !in presets.indices || to !in presets.indices) return presets
    return presets.toMutableList().also { it.add(to, it.removeAt(index)) }
}

/** [presets] with the entry at [id] renamed (its id is stable, so the handle survives a rename). */
fun renameToolPreset(presets: List<ToolPreset>, id: String, name: String): List<ToolPreset> =
    presets.map { if (it.id == id) it.copy(name = name) else it }
