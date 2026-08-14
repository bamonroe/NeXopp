package com.nexopp.format.rnote

import com.nexopp.format.json.JsonArray
import com.nexopp.format.json.JsonBool
import com.nexopp.format.json.JsonNumber
import com.nexopp.format.json.JsonObject
import com.nexopp.format.json.JsonString
import com.nexopp.format.json.JsonValue

/**
 * Flattening of a `textstroke`'s `text_style.ranged_text_attributes` into a partition of the
 * string (see `docs/architecture.md`, "The .rnote format — container & serialisation").
 *
 * Rnote stores styling as a list of `{range: [start, end], attribute: {kind: value}}` entries whose
 * ranges are **byte** offsets into the UTF-8 encoding of `text` and may overlap; a later entry wins
 * over an earlier one on the bytes they share. `.xopp` has no ranged text styling, so the reader
 * can only keep what is uniform across the whole string — flattening first makes "uniform?" a
 * simple question to ask.
 */

/** One maximal stretch of `[startByte, endByte)` over which every attribute value is constant. */
data class TextRun(val startByte: Int, val endByte: Int, val attrs: Map<String, String>)

/**
 * Partition `0 until textByteLength` by the attributes that apply to each byte.
 *
 * Runs come back non-overlapping and ascending and together cover the whole string; bytes no entry
 * covers become runs with an empty [TextRun.attrs]. Entries whose range is malformed, reversed or
 * out of bounds are ignored rather than throwing, since a foreign writer's file must still open.
 * Values are the raw JSON rendered to a string so runs compare by equality.
 *
 * @param rangedAttrs The `ranged_text_attributes` array, or null when the stroke has none.
 * @param textByteLength The UTF-8 byte length of the stroke's `text`.
 */
fun flattenRuns(rangedAttrs: JsonValue?, textByteLength: Int): List<TextRun> {
    if (textByteLength <= 0) return emptyList()
    val entries = (rangedAttrs as? JsonArray)?.items.orEmpty().mapNotNull { it.asRangedAttribute(textByteLength) }
    if (entries.isEmpty()) return listOf(TextRun(0, textByteLength, emptyMap()))

    // Per-byte attribute maps, later entries overwriting earlier ones, then coalesced into runs.
    val perByte = Array(textByteLength) { mutableMapOf<String, String>() }
    for (entry in entries) {
        for (byte in entry.startByte until entry.endByte) perByte[byte][entry.kind] = entry.value
    }
    return coalesce(perByte)
}

/**
 * The one value of [kind] shared by every run, or null when the runs disagree or any run lacks it.
 *
 * This is the "safe to promote onto the text box" test: an attribute that holds for the whole
 * string survives the trip to `.xopp`, one that varies cannot.
 */
fun uniformAttribute(runs: List<TextRun>, kind: String): String? {
    if (runs.isEmpty()) return null
    val first = runs.first().attrs[kind] ?: return null
    return if (runs.all { it.attrs[kind] == first }) first else null
}

/** One attribute entry, already validated against the string's length. */
private class RangedAttribute(val startByte: Int, val endByte: Int, val kind: String, val value: String)

/** Read `{range: [start, end], attribute: {kind: value}}`, or null when it is unusable. */
private fun JsonValue.asRangedAttribute(textByteLength: Int): RangedAttribute? {
    val range = obj("range")?.arr() ?: return null
    if (range.size != 2) return null
    val start = range[0].num()?.toInt() ?: return null
    val end = range[1].num()?.toInt() ?: return null
    if (start < 0 || end > textByteLength || start >= end) return null
    val attribute = obj("attribute") as? JsonObject ?: return null
    val (kind, value) = attribute.members.entries.firstOrNull() ?: return null
    return RangedAttribute(start, end, kind, value.render())
}

/** Merge neighbouring bytes that carry identical attributes into one [TextRun] each. */
private fun coalesce(perByte: Array<MutableMap<String, String>>): List<TextRun> {
    val runs = mutableListOf<TextRun>()
    var start = 0
    for (byte in 1..perByte.size) {
        if (byte < perByte.size && perByte[byte] == perByte[start]) continue
        runs += TextRun(start, byte, perByte[start].toMap())
        start = byte
    }
    return runs
}

/**
 * Render a JSON value to a comparable string. Scalars render bare (an integral number without its
 * `.0`) so a font size reads as `12`; a composite value such as `text_color` renders structurally,
 * which is enough for the equality comparisons runs need.
 */
private fun JsonValue.render(): String = when (this) {
    is JsonString -> value
    is JsonNumber -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
    is JsonBool -> value.toString()
    is JsonArray -> items.joinToString(",", "[", "]") { it.render() }
    is JsonObject -> members.entries.joinToString(",", "{", "}") { "${it.key}:${it.value.render()}" }
    else -> "null"
}
