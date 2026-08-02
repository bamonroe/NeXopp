package com.xopp.android.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the microphone to a WAV sidecar on a background thread.
 *
 * One recorder serves one recording: [start] opens the file, [elapsedMs] is the clock strokes are
 * stamped against while it runs, and [stop] finalises the WAV. Reading the clock from the *bytes
 * written* rather than wall time keeps a stroke's `ts` pointing at the sample it was really drawn
 * over, even if the capture thread stalls.
 *
 * The caller is responsible for holding `RECORD_AUDIO` — [start] returns false rather than throwing
 * when the permission is missing or no microphone is available.
 */
class AudioRecorder {

    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** The file being written, or null when idle. */
    var file: File? = null
        private set

    /** True between a successful [start] and its [stop]. */
    val isRecording: Boolean get() = running.get()

    /**
     * Begin capturing into [target]. Returns false (leaving the recorder idle) if the microphone
     * can't be opened — a denied permission, a device with no mic, or another app holding it.
     */
    fun start(target: File): Boolean {
        if (running.get()) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBuffer <= 0) return false
        val bufferBytes = minBuffer * BUFFER_MULTIPLIER
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, bufferBytes)
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        val w = runCatching { WavWriter(target, SAMPLE_RATE) }.getOrElse {
            rec.release()
            return false
        }
        record = rec
        writer = w
        file = target
        running.set(true)
        rec.startRecording()
        thread = Thread({ pump(rec, w, bufferBytes) }, "xopp-audio-record").also { it.start() }
        return true
    }

    /** Drain the capture buffer into the WAV until [stop] flips the flag. */
    private fun pump(rec: AudioRecord, w: WavWriter, bufferBytes: Int) {
        val buffer = ByteArray(bufferBytes)
        while (running.get()) {
            val read = rec.read(buffer, 0, buffer.size)
            if (read > 0) synchronized(w) { w.write(buffer, read) } else if (read < 0) break
        }
    }

    /** Milliseconds captured so far — the `ts` a stroke started right now would carry. */
    fun elapsedMs(): Int {
        val w = writer ?: return 0
        return synchronized(w) { w.durationMs() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Stop capturing and finalise the WAV, returning the file written (null if nothing was running).
     * Safe to call twice; the second call is a no-op.
     */
    fun stop(): File? {
        if (!running.getAndSet(false)) return null
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        record?.runCatching { stop() }
        record?.release()
        record = null
        writer?.let { w -> synchronized(w) { w.close() } }
        writer = null
        return file.also { file = null }
    }

    private companion object {
        /** 44.1 kHz mono: the rate every Android device is required to support for capture. */
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Read in chunks several times the driver minimum so a scheduling hiccup can't drop audio. */
        const val BUFFER_MULTIPLIER = 4

        /** How long [stop] waits for the capture thread before finalising anyway. */
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
