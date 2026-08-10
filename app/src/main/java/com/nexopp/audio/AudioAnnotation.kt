package com.nexopp.audio

import com.nexopp.format.model.Document
import com.nexopp.format.model.Stroke
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A stroke's link into an audio recording: the sidecar file it was drawn over and how far into that
 * file the stroke began. This is exactly the `fn` / `ts` pair desktop Xournal++ writes on `<stroke>`
 * (see `docs/architecture.md` → stroke attributes), so anything we stamp here replays on the desktop
 * and anything the desktop wrote replays here.
 */
data class AudioRef(
    /** The sidecar's bare file name (no directory), e.g. `2026-08-02T09-14-33.wav`. */
    val filename: String,
    /** Milliseconds from the start of that recording to the moment the stroke was started. */
    val startMs: Int,
)

/** The `<stroke>` attribute naming the audio sidecar. */
const val ATTR_AUDIO_FILENAME: String = "fn"

/** The `<stroke>` attribute holding the offset into the sidecar, in milliseconds. */
const val ATTR_AUDIO_TIMESTAMP: String = "ts"

/**
 * The recording this stroke replays from, or null when it carries no usable link. Xournal++ writes
 * `ts="0" fn=""` on strokes that have no audio, so a blank `fn` is "unlinked", not "file named ''".
 */
fun Stroke.audioRef(): AudioRef? {
    val fn = extraAttrs[ATTR_AUDIO_FILENAME]?.takeIf { it.isNotBlank() } ?: return null
    val ts = extraAttrs[ATTR_AUDIO_TIMESTAMP]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    return AudioRef(fn, ts)
}

/**
 * This stroke with [ref] stamped onto its `fn`/`ts` attributes. Every other extra attribute the file
 * carried is preserved untouched, which is what keeps a round-trip lossless for documents that use
 * attributes we don't model.
 */
fun Stroke.withAudio(ref: AudioRef): Stroke = copy(
    extraAttrs = extraAttrs +
        mapOf(ATTR_AUDIO_FILENAME to ref.filename, ATTR_AUDIO_TIMESTAMP to ref.startMs.toString()),
)

/**
 * Every sidecar file name [doc] references, de-duplicated. This is the set that must travel with the
 * `.xopp` for it to stay playable — used both to pull sidecars in on open and to push them back out
 * on save.
 */
fun documentAudioFiles(doc: Document): Set<String> =
    doc.pages.asSequence()
        .flatMap { it.layers.asSequence() }
        .flatMap { it.elements.asSequence() }
        .filterIsInstance<Stroke>()
        .mapNotNull { it.audioRef()?.filename }
        .toCollection(LinkedHashSet())

/**
 * The name a new recording started at [epochMillis] gets: desktop Xournal++'s local-time
 * `yyyy-MM-ddTHH-mm-ss.wav` convention, so recordings made on either side sort together in a folder
 * and never collide within a second.
 */
fun audioFileName(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    FILENAME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(epochMillis)) + ".wav"

private val FILENAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
