package com.xopp.android.ui

/**
 * Encoding of a [RadialPalette] to and from the single string [SettingsStore] keeps in
 * `SharedPreferences`, following the same plain-text pattern as [encodeToolGroupSelections].
 *
 * The wire form is three `;`-separated fields — `name;innerSlots;outerSlots` — where each ring is a
 * comma-separated list of one action token per slot, and an empty token means an empty slot:
 *
 * ```
 * Palette;tool:PEN,toggle:ERASER,,undo,,,,;color:-16777216,color:-65536,,,,,,,,,,,,,,
 * ```
 *
 * Decoding is deliberately forgiving so a palette written by an older or newer build still loads:
 * unknown action tokens, unknown enum names and unparsable numbers become **empty slots**, and a
 * ring with too few or too many slots is padded/truncated to the ring's current size. That way a
 * slot type this build doesn't understand costs the user one slot, not the whole palette.
 */

/** Serialise [palette] into the one-line form [decodeRadialPalette] reads back. */
fun encodeRadialPalette(palette: RadialPalette): String = listOf(
    palette.name.replace(FIELD_SEP, ' ').replace(SLOT_SEP, ' ').replace(PALETTE_SEP, ' '),
    encodeRing(palette.inner),
    encodeRing(palette.outer),
).joinToString(FIELD_SEP.toString())

/**
 * Parse what [encodeRadialPalette] wrote, or `null` when [raw] is absent/blank — callers substitute
 * their own fallback (typically [RadialPalette.default]).
 */
fun decodeRadialPalette(raw: String?): RadialPalette? {
    if (raw.isNullOrBlank()) return null
    val fields = raw.split(FIELD_SEP)
    val name = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: RadialPalette.DEFAULT_NAME
    return RadialPalette(
        name = name,
        inner = decodeRing(fields.getOrNull(1), RadialRing.INNER),
        outer = decodeRing(fields.getOrNull(2), RadialRing.OUTER),
    )
}

/**
 * Serialise a whole palette list into one line: each palette in the single-palette form above,
 * separated by `|` (the same record separator [encodeToolPresets] uses).
 */
fun encodeRadialPalettes(palettes: List<RadialPalette>): String =
    palettes.joinToString(PALETTE_SEP.toString(), transform = ::encodeRadialPalette)

/**
 * Parse what [encodeRadialPalettes] wrote. Empty/blank input yields an empty list so the caller can
 * tell "nothing saved" from "one palette saved" and fall back (or migrate) accordingly; a record
 * this build can't read at all is dropped rather than taking the list down with it.
 */
fun decodeRadialPalettes(raw: String?): List<RadialPalette> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(PALETTE_SEP).mapNotNull(::decodeRadialPalette)
}

/** Separates the palette's three top-level fields. */
private const val FIELD_SEP: Char = ';'

/** Separates palettes in the list form. */
private const val PALETTE_SEP: Char = '|'

/** Separates one ring's slots. */
private const val SLOT_SEP: Char = ','

/** One ring's slots as comma-separated tokens, empty slots contributing an empty token. */
private fun encodeRing(slots: List<PaletteAction?>): String =
    slots.joinToString(SLOT_SEP.toString()) { it?.let(::encodeAction).orEmpty() }

/** A ring's worth of actions, padded with empty slots or truncated to [ring]'s current slot count. */
private fun decodeRing(raw: String?, ring: RadialRing): List<PaletteAction?> {
    val parsed = raw.orEmpty().split(SLOT_SEP).map { decodeAction(it.trim()) }
    return List(ring.slotCount) { parsed.getOrNull(it) }
}

/** One action as `kind` or `kind:argument` — the inverse of [decodeAction]. */
private fun encodeAction(action: PaletteAction): String = when (action) {
    is PaletteAction.SelectTool -> "tool:${action.tool.name}"
    is PaletteAction.ToggleTool -> "toggle:${action.tool.name}"
    is PaletteAction.SetColor -> "color:${action.argb}"
    is PaletteAction.SetWidth -> "width:${action.widthPt}"
    PaletteAction.Undo -> "undo"
    PaletteAction.Redo -> "redo"
    PaletteAction.ToggleFullPage -> "fullpage"
    is PaletteAction.Page -> "page:${action.op.name}"
    is PaletteAction.ApplyPreset -> "preset:${action.presetId}"
}

/** Parse one slot token, yielding `null` (an empty slot) for anything this build can't make sense of. */
private fun decodeAction(token: String): PaletteAction? {
    if (token.isEmpty()) return null
    val kind = token.substringBefore(':')
    val arg = token.substringAfter(':', missingDelimiterValue = "")
    return when (kind) {
        "tool" -> enumOrNull<EditorTool>(arg)?.let(PaletteAction::SelectTool)
        "toggle" -> enumOrNull<EditorTool>(arg)?.let(PaletteAction::ToggleTool)
        "color" -> arg.toIntOrNull()?.let(PaletteAction::SetColor)
        // Coerced rather than rejected: a width from a build with different bounds is still a width.
        "width" -> arg.toFloatOrNull()?.let { PaletteAction.SetWidth(it.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX)) }
        "undo" -> PaletteAction.Undo
        "redo" -> PaletteAction.Redo
        "fullpage" -> PaletteAction.ToggleFullPage
        "page" -> enumOrNull<PalettePageOp>(arg)?.let(PaletteAction::Page)
        "preset" -> arg.takeIf { it.isNotEmpty() }?.let(PaletteAction::ApplyPreset)
        else -> null
    }
}

/** An enum constant by name, or `null` if this build no longer has it. */
private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
    runCatching { enumValueOf<E>(name) }.getOrNull()
