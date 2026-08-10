package com.nexopp.io

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Staging must never let two overlapping opens share a path — a slow remote read used to overwrite
 * `open.tmp` while another open was still parsing it, and both tabs came up showing one document.
 *
 * This is an instrumented test rather than a JVM one because [UriStaging] needs a real
 * `ContentResolver`; the path-uniqueness half of the promise is also covered on the JVM by
 * `ScratchDirTest`.
 */
@RunWith(AndroidJUnit4::class)
class UriStagingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "staging-test").also { it.deleteRecursively() }
    private val staging = UriStaging(context.contentResolver, dir)

    private fun source(name: String, text: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(text) })

    @Test fun twoStagedOpensGetDistinctFilesHoldingTheirOwnBytes() {
        val a = staging.stageIn(source("src-a.xopp", "document A"), "open")
        val b = staging.stageIn(source("src-b.xopp", "document B"), "open")

        assertNotEquals(a.absolutePath, b.absolutePath)
        assertTrue(a.isFile && b.isFile)
        assertEquals("document A", a.readText())
        assertEquals("document B", b.readText())
    }

    @Test fun stagingTheSameSourceTwiceStillYieldsTwoFiles() {
        val uri = source("src-same.xopp", "same bytes")
        val a = staging.stageIn(uri, "open")
        val b = staging.stageIn(uri, "open")

        assertNotEquals(a.absolutePath, b.absolutePath)
        assertEquals("same bytes", a.readText())
        assertEquals("same bytes", b.readText())
    }

    @Test fun aStagedWriteRoundTripsBackOutToItsUri() {
        val target = File(context.cacheDir, "out.xopp").apply { writeText("stale") }
        val scratch = staging.newFile("save").apply { writeText("fresh bytes") }

        staging.stageOut(scratch, Uri.fromFile(target))

        assertEquals("fresh bytes", target.readText())
    }
}
