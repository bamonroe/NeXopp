package com.nexopp.ui

import com.nexopp.format.model.LineStyle

/**
 * Encoding of the saved [ToolPreset] list to and from the single string [SettingsStore] keeps in
 * `SharedPreferences`, following the same plain-text pattern as [encodeRadialPalette].
 *
 * The wire form is `|`-separated presets, each of them eight `;`-separated fields:
 *
 * ```
 * fine-black;Fine black;PEN;-16777216;0.85;PLAIN;0;128|marker;Marker;HIGHLIGHTER;-256;8.0;PLAIN;1;90
 * ```
 *
 * Decoding is deliberately forgiving so a list written by an older or newer build still loads: a
 * preset with too few fields, an unknown tool/line-style name or an unparsable number is **dropped**
 * and the rest survive. One unreadable preset costs the user that preset, not the whole list.
 */

/** Separates one preset's fields. */
private const val FIELD_SEP: Char = ';'

/** Separates presets. */
private const val PRESET_SEP: Char = '|'

/** Serialise [presets] into the one-line form [decodeToolPresets] reads back. */
fun encodeToolPresets(presets: List<ToolPreset>): String =
    presets.joinToString(PRESET_SEP.toString()) { p ->
        listOf(
            sanitize(p.id),
            sanitize(p.name),
            p.tool.name,
            p.colorArgb.toString(),
            p.widthPt.toString(),
            p.lineStyle.name,
            if (p.fillEnabled) "1" else "0",
            p.fillAlpha.toString(),
        ).joinToString(FIELD_SEP.toString())
    }

/** Parse what [encodeToolPresets] wrote, skipping any preset this build can't make sense of. */
fun decodeToolPresets(raw: String?): List<ToolPreset> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(PRESET_SEP).mapNotNull(::decodePreset)
}

/** One preset, or `null` when a field is missing, unknown to this build, or unparsable. */
private fun decodePreset(raw: String): ToolPreset? {
    val f = raw.split(FIELD_SEP)
    if (f.size < 8) return null
    val id = f[0].trim().ifEmpty { return null }
    val tool = enumOrNull<EditorTool>(f[2].trim()) ?: return null
    val color = f[3].trim().toIntOrNull() ?: return null
    // Coerced rather than rejected: a width from a build with different bounds is still a width.
    val width = f[4].trim().toFloatOrNull()?.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX) ?: return null
    val style = enumOrNull<LineStyle>(f[5].trim()) ?: return null
    return ToolPreset(
        id = id,
        name = f[1].trim().ifEmpty { id },
        tool = tool,
        colorArgb = color,
        widthPt = width,
        lineStyle = style,
        fillEnabled = f[6].trim() == "1",
        // 0..255 matches the .xopp file format; 0 is valid for transparent fill.
        fillAlpha = (f[7].trim().toIntOrNull() ?: DEFAULT_FILL_ALPHA).coerceIn(0, 255),
    )
}

/** Strip the separators out of a free-text field so it can't break the wire form. */
private fun sanitize(text: String): String = text.replace(FIELD_SEP, ' ').replace(PRESET_SEP, ' ')

/** An enum constant by name, or `null` if this build no longer has it. */
private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
    runCatching { enumValueOf<E>(name) }.getOrNull()
