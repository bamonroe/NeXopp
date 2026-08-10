package com.nexopp.audio

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun le32(b: ByteArray, at: Int): Long =
        (0 until 4).sumOf { (b[at + it].toLong() and 0xFF) shl (8 * it) }

    private fun ascii(b: ByteArray, at: Int, len: Int) =
        String(b, at, len, Charsets.US_ASCII)

    @Test
    fun `header describes a 44_1 kHz mono 16-bit stream`() {
        val h = WavWriter.header(sampleRate = 44_100, channels = 1, dataBytes = 1_000)
        assertEquals(WavWriter.HEADER_BYTES, h.size)
        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(44_100L, le32(h, 24))
        assertEquals(44_100L * 2, le32(h, 28)) // byte rate = rate × channels × 2
        assertEquals(1_000L, le32(h, 40))
        assertEquals(1_000L + WavWriter.HEADER_BYTES - 8, le32(h, 4))
    }

    @Test
    fun `closing patches the length fields to what was written`() {
        val file = File(tmp.root, "clip.wav")
        val samples = ByteArray(400) { (it % 128).toByte() }
        WavWriter(file, sampleRate = 8_000).use { it.write(samples, samples.size) }

        val bytes = file.readBytes()
        assertEquals(WavWriter.HEADER_BYTES + 400, bytes.size)
        assertEquals(400L, le32(bytes, 40))
        assertEquals(400L + WavWriter.HEADER_BYTES - 8, le32(bytes, 4))
        // The audio itself must land verbatim after the header — this is what playback decodes.
        assertArrayEquals(samples, bytes.copyOfRange(WavWriter.HEADER_BYTES, bytes.size))
    }

    @Test
    fun `duration is derived from the bytes actually written`() {
        val file = File(tmp.root, "clip.wav")
        WavWriter(file, sampleRate = 8_000).use { w ->
            assertEquals(0L, w.durationMs())
            // 8 kHz mono 16-bit = 16 bytes per ms; 1600 bytes is 100 ms.
            w.write(ByteArray(1_600), 1_600)
            assertEquals(100L, w.durationMs())
            // A zero-length write is a no-op, not a rewind.
            w.write(ByteArray(8), 0)
            assertEquals(100L, w.durationMs())
        }
    }
}
