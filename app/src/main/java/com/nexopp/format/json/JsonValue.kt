package com.nexopp.format.json

/**
 * A parsed JSON document as a plain tree. Deliberately untyped: tolerating several Rnote
 * versions means probing for keys rather than binding a schema (see `docs/architecture.md`,
 * "The .rnote format — container & serialisation"). Kept dependency-free so the same code runs
 * on-device and in JVM unit tests.
 *
 * The accessors below all return null on a type mismatch, so a reader can chain them without
 * type checks: `root.path("engine_snapshot", "stroke_components")?.arr()`.
 */
sealed class JsonValue {

    /** The member for [key], or null unless this is a [JsonObject] that has it. */
    fun obj(key: String): JsonValue? = (this as? JsonObject)?.members?.get(key)

    /** The items, or null unless this is a [JsonArray]. */
    fun arr(): List<JsonValue>? = (this as? JsonArray)?.items

    /** The text, or null unless this is a [JsonString]. */
    fun str(): String? = (this as? JsonString)?.value

    /** The value, or null unless this is a [JsonNumber]. */
    fun num(): Double? = (this as? JsonNumber)?.value

    /** The value, or null unless this is a [JsonBool]. */
    fun bool(): Boolean? = (this as? JsonBool)?.value

    /** True only for the JSON literal `null` — not for a missing key. */
    fun isNull(): Boolean = this is JsonNull

    /** Walks [obj] once per key; null as soon as one step is missing or not an object. */
    fun path(vararg keys: String): JsonValue? {
        var cur: JsonValue? = this
        for (key in keys) cur = cur?.obj(key) ?: return null
        return cur
    }
}

/** Members keep source order (a LinkedHashMap); on a duplicate key the last one wins. */
class JsonObject(val members: Map<String, JsonValue>) : JsonValue()

class JsonArray(val items: List<JsonValue>) : JsonValue()

class JsonString(val value: String) : JsonValue()

/** Every JSON number is a [Double]; the format has no integer/float distinction. */
class JsonNumber(val value: Double) : JsonValue()

class JsonBool(val value: Boolean) : JsonValue()

object JsonNull : JsonValue()
