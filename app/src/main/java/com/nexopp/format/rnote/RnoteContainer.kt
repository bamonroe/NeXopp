package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import com.nexopp.format.json.JsonString
import com.nexopp.format.json.JsonValue
import com.nexopp.format.json.jsonObject
import com.nexopp.format.json.toJsonString
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Top-level `.rnote` container I/O: gzip on the outside, one JSON object within — the same
 * wrapper a classic `.xopp` uses, with JSON in place of XML (see `docs/architecture.md`, section
 * "The `.rnote` format — container & serialisation"). This layer only unwraps, version-checks and
 * re-wraps; turning a snapshot into a document model (or back) belongs to the reader and writer
 * above it.
 */
object RnoteContainer {

    /**
     * The version stamped on every file we write: the upstream release the whole mapping was read
     * out of. Rnote chains its `TryFrom` conversions **forward** from the version in the file, so
     * naming the one we actually match is what lets an older Rnote convert our payload rather than
     * misread it.
     */
    const val WRITE_VERSION = "0.14.2"

    /**
     * The oldest Rnote release whose payload shape we read. Older files use `store_snapshot` and
     * pre-reshuffle stroke structs, which would need real conversion code for files that predate
     * 2022 — they are rejected by version rather than failing as a parse error.
     */
    private const val MIN_MAJOR = 0
    private const val MIN_MINOR = 6

    /**
     * The unwrapped container: the version that wrote the file, split out for the payload-shape
     * fallbacks a reader needs, plus the `data.engine_snapshot` object itself.
     *
     * @property version The raw semver string as written, e.g. `0.14.2`.
     * @property major The semver major component.
     * @property minor The semver minor component.
     * @property patch The semver patch component, with any `-suffix` pre-release tag stripped.
     * @property snapshot The `data.engine_snapshot` object.
     */
    data class RnoteWrapper(
        val version: String,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val snapshot: JsonValue,
    )

    /**
     * Read a `.rnote` stream (gzip + JSON) into its [RnoteWrapper]. Does not close [input].
     * Upstream writes with `flate2` and reads with `MultiGzDecoder`, so multi-member streams must
     * be tolerated — the JDK's `GZIPInputStream` already does.
     *
     * @param input A stream positioned at the start of a `.rnote` file.
     * @return The parsed and version-checked container.
     * @throws IllegalArgumentException If the version is unsupported or a required key is missing.
     */
    fun open(input: InputStream): RnoteWrapper {
        val text = GZIPInputStream(input).reader(Charsets.UTF_8).use { it.readText() }
        return parseJson(text)
    }

    /**
     * Parse an already-decompressed container document, so tests (and any future non-gzip source)
     * need no gzip round trip.
     *
     * @param text The whole decompressed JSON document.
     * @return The parsed and version-checked container.
     * @throws IllegalArgumentException If the version is unsupported or a required key is missing.
     */
    fun parseJson(text: String): RnoteWrapper {
        val root = JsonReader(text).parse()
        val version = root.obj("version")?.str()
            ?: throw IllegalArgumentException("missing \"version\" in the .rnote container")
        if (version.isBlank()) {
            throw IllegalArgumentException("missing \"version\" in the .rnote container")
        }
        val (major, minor, patch) = semver(version)
        if (major < MIN_MAJOR || (major == MIN_MAJOR && minor < MIN_MINOR)) {
            throw IllegalArgumentException("unsupported .rnote version: $version")
        }
        val data = root.obj("data")
            ?: throw IllegalArgumentException("missing \"data\" in the .rnote container")
        val snapshot = data.obj("engine_snapshot")
            ?: throw IllegalArgumentException("missing \"data.engine_snapshot\" in the .rnote container")
        return RnoteWrapper(version, major, minor, patch, snapshot)
    }

    /**
     * Wrap an `engine_snapshot` in the container document, as compact JSON — the mirror of
     * [parseJson], and the seam tests use so they need no gzip round trip.
     *
     * @param snapshot The `engine_snapshot` object, from [writeSnapshot].
     * @return The whole decompressed container document.
     */
    fun writeJson(snapshot: JsonValue): String = jsonObject(
        "version" to JsonString(WRITE_VERSION),
        "data" to jsonObject("engine_snapshot" to snapshot),
    ).toJsonString()

    /**
     * Write a `.rnote` stream: [writeJson]'s UTF-8 bytes through gzip. Does not close [output],
     * matching [open]'s contract of not closing its input — but does finish the gzip member, so the
     * bytes are complete when it returns.
     *
     * @param snapshot The `engine_snapshot` object, from [writeSnapshot].
     * @param output The stream to write the file to.
     */
    fun write(snapshot: JsonValue, output: OutputStream) {
        val gzip = GZIPOutputStream(output)
        gzip.write(writeJson(snapshot).toByteArray(Charsets.UTF_8))
        gzip.finish()
    }

    /**
     * Split a semver string into its three numeric components. The patch may carry a pre-release
     * or build tag (`0.14.2-rc1`), which is dropped; a component we can't read counts as 0 so a
     * short or odd version still reaches the range check rather than throwing a number-format
     * error.
     *
     * @param version The raw version string from the container.
     * @return The major, minor and patch components.
     */
    private fun semver(version: String): Triple<Int, Int, Int> {
        val parts = version.split(".")
        fun part(i: Int): Int =
            parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(part(0), part(1), part(2))
    }
}
