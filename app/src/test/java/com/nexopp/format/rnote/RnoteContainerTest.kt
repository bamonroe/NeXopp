package com.nexopp.format.rnote

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container layer against the five ground-truth `.rnote` fixtures written by `rnote-cli`
 * 0.14.2 (`app/src/test/resources/fixtures/rnote/`), so gzip unwrapping and the version gate are
 * checked on real bytes rather than hand-written JSON.
 */
class RnoteContainerTest {

    private val fixtures = listOf("empty", "plain", "backgrounds", "layers", "text-image")

    private fun open(name: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
            ?.use { RnoteContainer.open(it) }
            ?: error("missing fixture fixtures/rnote/$name.rnote")

    @Test
    fun `every fixture unwraps to a versioned engine snapshot`() {
        for (name in fixtures) {
            val wrapper = open(name)
            assertEquals(name, "0.14.2", wrapper.version)
            assertEquals(name, 0, wrapper.major)
            assertEquals(name, 14, wrapper.minor)
            assertEquals(name, 2, wrapper.patch)
            assertNotNull("$name: engine_snapshot.document", wrapper.snapshot.obj("document"))
        }
    }

    @Test
    fun `a pre-0_6 file is rejected by version, not by a parse error`() {
        val e = assertThrows {
            RnoteContainer.parseJson("""{"version":"0.5.2","data":{"engine_snapshot":{}}}""")
        }
        assertEquals("unsupported .rnote version: 0.5.2", e.message)
    }

    @Test
    fun `0_6 is the oldest version accepted`() {
        val wrapper =
            RnoteContainer.parseJson("""{"version":"0.6.0","data":{"engine_snapshot":{}}}""")
        assertEquals("0.6.0", wrapper.version)
    }

    @Test
    fun `a pre-release tag on the patch does not stop the version parsing`() {
        val wrapper =
            RnoteContainer.parseJson("""{"version":"0.14.2-rc1","data":{"engine_snapshot":{}}}""")
        assertEquals(2, wrapper.patch)
    }

    @Test
    fun `a missing key names the key it missed`() {
        assertEquals(
            "missing \"data\" in the .rnote container",
            assertThrows { RnoteContainer.parseJson("""{"version":"0.14.2"}""") }.message,
        )
        assertEquals(
            "missing \"data.engine_snapshot\" in the .rnote container",
            assertThrows { RnoteContainer.parseJson("""{"version":"0.14.2","data":{}}""") }.message,
        )
        assertEquals(
            "missing \"version\" in the .rnote container",
            assertThrows { RnoteContainer.parseJson("""{"data":{"engine_snapshot":{}}}""") }.message,
        )
        assertEquals(
            "missing \"version\" in the .rnote container",
            assertThrows {
                RnoteContainer.parseJson("""{"version":" ","data":{"engine_snapshot":{}}}""")
            }.message,
        )
    }

    @Test
    fun `a gzip xopp is not a container`() {
        val xopp = javaClass.classLoader!!.getResourceAsStream("fixtures/plain.xopp")
            ?.use { it.readBytes() }
            ?: error("missing fixture fixtures/plain.xopp")
        // Gunzips fine — it is the JSON parser that refuses `<xournal`.
        val e = assertThrows { RnoteContainer.open(ByteArrayInputStream(xopp)) }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().isNotBlank())
    }

    private fun assertThrows(block: () -> Unit): IllegalArgumentException =
        try {
            block()
            error("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }
}
