package com.xopp.android.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Streams 16-bit little-endian PCM into a canonical RIFF/WAVE file.
 *
 * Android's `MediaRecorder` can't emit WAV, so recording goes through the raw `AudioRecord` API and
 * this writer frames the samples. The two length fields in a RIFF header sit *before* the audio, so
 * the header is written up front with zeros and patched in [close] once the final byte count is
 * known — that also means a recording killed mid-way leaves a file with a zero length field rather
 * than a truncated-but-plausible one.
 *
 * WAV (rather than Ogg/Opus) because it is what the `.xopp` sidecar convention uses and what desktop
 * Xournal++ plays back without any codec of its own.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
) : Closeable {

    private val out = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0
    private var closed = false

    init {
        out.setLength(0)
        out.write(header(sampleRate, channels, dataBytes = 0))
    }

    /** Append the first [length] bytes of [buffer] as PCM sample data. */
    fun write(buffer: ByteArray, length: Int) {
        if (length <= 0) return
        out.write(buffer, 0, length)
        dataBytes += length
    }

    /** Milliseconds of audio written so far — the clock strokes are timestamped against. */
    fun durationMs(): Long {
        val bytesPerMs = sampleRate.toLong() * channels * BYTES_PER_SAMPLE / 1000
        return if (bytesPerMs == 0L) 0 else dataBytes / bytesPerMs
    }

    /** Patch the RIFF/data length fields to what was actually written, then close the file. */
    override fun close() {
        if (closed) return
        closed = true
        out.use {
            it.seek(0)
            it.write(header(sampleRate, channels, dataBytes))
        }
    }

    companion object {
        /** Bytes per sample per channel — this writer is 16-bit PCM only. */
        const val BYTES_PER_SAMPLE: Int = 2

        /** The fixed size of a canonical RIFF/WAVE header, i.e. the offset audio data starts at. */
        const val HEADER_BYTES: Int = 44

        /**
         * The 44-byte canonical header for a PCM stream of [dataBytes]. Exposed (and pure) so the
         * byte layout can be asserted in a JVM unit test without recording anything.
         */
        fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
            val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
            val h = ByteArray(HEADER_BYTES)
            ascii(h, 0, "RIFF")
            le32(h, 4, dataBytes + HEADER_BYTES - 8) // everything after this field
            ascii(h, 8, "WAVE")
            ascii(h, 12, "fmt ")
            le32(h, 16, 16)                                   // PCM fmt chunk size
            le16(h, 20, 1)                                    // format 1 = uncompressed PCM
            le16(h, 22, channels)
            le32(h, 24, sampleRate.toLong())
            le32(h, 28, byteRate.toLong())
            le16(h, 32, channels * BYTES_PER_SAMPLE)          // block align
            le16(h, 34, BYTES_PER_SAMPLE * 8)                 // bits per sample
            ascii(h, 36, "data")
            le32(h, 40, dataBytes)
            return h
        }

        private fun ascii(b: ByteArray, at: Int, s: String) {
            for (i in s.indices) b[at + i] = s[i].code.toByte()
        }

        private fun le16(b: ByteArray, at: Int, v: Int) {
            b[at] = (v and 0xFF).toByte()
            b[at + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun le32(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }
}
