package com.xopp.android.format

/**
 * Converts between `.xopp` colour strings and ARGB ints (`0xAARRGGBB`, Android's `Color`
 * layout). On disk the form is `#RRGGBBAA` (alpha **last**); we also read 6-digit `#RRGGBB`
 * (implicit opaque) and the desktop named-colour keywords. See `docs/architecture.md`.
 */
object XoppColor {

    /**
     * Named colour keywords recognised by desktop Xournal++ — parsed verbatim from the XML.
     * Maps lowercase names to ARGB int values.
     */
    private val NAMED: Map<String, Int> = mapOf(
        "black" to 0xFF000000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
        "lightblue" to 0xFFADD8E6.toInt(),
        "lightgreen" to 0xFF90EE90.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
    )

    /**
     * Parse an on-disk colour string to an ARGB int. Recognises `#RRGGBBAA` (8-digit hex with alpha
     * last), `#RRGGBB` (6-digit hex, assumed opaque), and named colours (case-insensitive). Returns
     * opaque black (`0xFF000000`) for null, malformed, or unrecognised input.
     *
     * @param value The colour string from the XML (`#RRGGBBAA`, `#RRGGBB`, or a named colour).
     * @return The parsed ARGB int in `0xAARRGGBB` format, or opaque black on parse failure.
     */
    fun parse(value: String?): Int {
        if (value == null) return 0xFF000000.toInt()
        val v = value.trim()
        NAMED[v.lowercase()]?.let { return it }
        val hex = v.removePrefix("#")
        if (hex.startsWith("-") || hex.startsWith("+")) return 0xFF000000.toInt()
        return when (hex.length) {
            6 -> {
                val rgb = hex.toLongOrNull(16) ?: return 0xFF000000.toInt()
                0xFF000000.toInt() or rgb.toInt()
            }
            8 -> {
                val rgba = hex.toLongOrNull(16) ?: return 0xFF000000.toInt()
                val a = (rgba and 0xFF).toInt()
                val rgb = (rgba ushr 8).toInt() and 0xFFFFFF
                (a shl 24) or rgb
            }
            else -> 0xFF000000.toInt()
        }
    }

    /**
     * The alpha desktop Xournal++ gives a highlighter stroke whose stored colour is opaque —
     * half-transparent, so overlapping ink still reads through.
     */
    const val HIGHLIGHTER_ALPHA = 0x80

    /**
     * Replace the alpha channel of an ARGB int, keeping its RGB unchanged.
     *
     * @param alpha The new alpha value (0..255) to apply.
     * @return A new ARGB int with the specified alpha and the original RGB.
     */
    fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** Extract the alpha channel (0..255) from an ARGB int. */
    val Int.alpha: Int get() = (this ushr 24) and 0xFF

    /** Extract the red channel (0..255) from an ARGB int. */
    val Int.red: Int get() = (this ushr 16) and 0xFF

    /** Extract the green channel (0..255) from an ARGB int. */
    val Int.green: Int get() = (this ushr 8) and 0xFF

    /** Extract the blue channel (0..255) from an ARGB int. */
    val Int.blue: Int get() = this and 0xFF

    /**
     * Serialise an ARGB int to the on-disk `#RRGGBBAA` form (red, green, blue, then alpha), lowercase
     * hex with leading `#`. This is the inverse of [parse] for 8-digit hex strings.
     *
     * @param argb The colour in Android's `0xAARRGGBB` format.
     * @return The on-disk hex string in `#RRGGBBAA` format.
     */
    fun format(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        val rgba = (rgb.toLong() shl 8) or a.toLong()
        return "#" + rgba.toString(16).padStart(8, '0')
    }
}
