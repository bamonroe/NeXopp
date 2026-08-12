package com.nexopp.format.rnote

import com.nexopp.format.json.JsonReader
import com.nexopp.format.json.JsonValue
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Top-level `.rnote` container I/O: gzip on the outside, one JSON object within — the same
 * wrapper a classic `.xopp` uses, with JSON in place of XML (see `docs/architecture.md`, section
 * "The `.rnote` format — container & serialisation"). This layer only unwraps and version-checks;
 * turning the snapshot into a document model belongs to the reader above it.
 */
object RnoteContainer {

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
