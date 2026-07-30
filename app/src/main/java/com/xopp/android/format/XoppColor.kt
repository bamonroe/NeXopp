package com.xopp.android.format

/**
 * Converts between `.xopp` colour strings and ARGB ints (`0xAARRGGBB`, Android's `Color`
 * layout). On disk the form is `#RRGGBBAA` (alpha **last**); we also read 6-digit `#RRGGBB`
 * (implicit opaque) and the desktop named-colour keywords. See `docs/architecture.md`.
 */
object XoppColor {

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

    /** Parse an on-disk colour to an ARGB int. Defaults to opaque black on garbage. */
    fun parse(value: String?): Int {
        if (value == null) return 0xFF000000.toInt()
        val v = value.trim()
        NAMED[v.lowercase()]?.let { return it }
        val hex = v.removePrefix("#")
        return when (hex.length) {
            6 -> 0xFF000000.toInt() or hex.toLong(16).toInt()
            8 -> {
                val rgba = hex.toLong(16)
                val a = (rgba and 0xFF).toInt()
                val rgb = (rgba ushr 8).toInt() and 0xFFFFFF
                (a shl 24) or rgb
            }
            else -> 0xFF000000.toInt()
        }
    }

    /** Serialise an ARGB int to the on-disk `#RRGGBBAA` form (alpha last), lowercase. */
    fun format(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        val rgba = (rgb.toLong() shl 8) or a.toLong()
        return "#" + rgba.toString(16).padStart(8, '0')
    }
}
