package com.nexopp.audio

import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioAnnotationTest {

    private val WHITE = Background.Solid(0xFFFFFFFF.toInt(), "plain")

    private fun stroke(extra: Map<String, String>) = Stroke(
        Tool.PEN, 0xFF000000.toInt(), "round",
        listOf(StrokePoint(0.0, 0.0, 1.0), StrokePoint(1.0, 1.0, 1.0)),
        uniformWidth = true, extraAttrs = extra,
    )

    @Test
    fun `reads a desktop fn ts pair`() {
        val ref = stroke(mapOf("fn" to "clip.wav", "ts" to "4200")).audioRef()
        assertEquals(AudioRef("clip.wav", 4200), ref)
    }

    @Test
    fun `an empty filename means no audio`() {
        // Xournal++ writes ts="0" fn="" on every unannotated stroke — that must not read as a link.
        assertNull(stroke(mapOf("fn" to "", "ts" to "0")).audioRef())
        assertNull(stroke(emptyMap()).audioRef())
    }

    @Test
    fun `a malformed or negative timestamp falls back to the start of the clip`() {
        assertEquals(AudioRef("c.wav", 0), stroke(mapOf("fn" to "c.wav", "ts" to "abc")).audioRef())
        assertEquals(AudioRef("c.wav", 0), stroke(mapOf("fn" to "c.wav", "ts" to "-5")).audioRef())
        assertEquals(AudioRef("c.wav", 0), stroke(mapOf("fn" to "c.wav")).audioRef())
    }

    @Test
    fun `stamping preserves every other extra attribute`() {
        val stamped = stroke(mapOf("custom" to "keep", "fn" to "", "ts" to "0"))
            .withAudio(AudioRef("new.wav", 90))
        assertEquals("keep", stamped.extraAttrs["custom"])
        assertEquals("new.wav", stamped.extraAttrs["fn"])
        assertEquals("90", stamped.extraAttrs["ts"])
        assertEquals(AudioRef("new.wav", 90), stamped.audioRef())
    }

    @Test
    fun `document audio files are de-duplicated across pages and layers`() {
        val doc = Document(
            pages = listOf(
                Page(100.0, 100.0, WHITE, layers = listOf(Layer(listOf(
                    stroke(mapOf("fn" to "a.wav", "ts" to "0")),
                    stroke(mapOf("fn" to "a.wav", "ts" to "500")),
                    stroke(emptyMap()),
                )))),
                Page(100.0, 100.0, WHITE, layers = listOf(Layer(listOf(
                    stroke(mapOf("fn" to "b.wav", "ts" to "10")),
                )))),
            ),
        )
        assertEquals(setOf("a.wav", "b.wav"), documentAudioFiles(doc))
    }

    @Test
    fun `recordings are named in desktop local-time convention`() {
        // 2026-08-02T09:14:33Z, read in UTC.
        assertEquals("2026-08-02T09-14-33.wav", audioFileName(1_785_662_073_000L, ZoneId.of("UTC")))
    }
}
