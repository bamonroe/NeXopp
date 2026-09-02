package com.nexopp.io

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

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

    @Test fun aWellBehavedProviderIsWrittenThroughAndTruncated() {
        val target = awkward("honest", "honest.xopp")
        val scratch = staging.newFile("save").apply { writeText("fresh") }

        staging.stageOut(scratch, target)

        assertEquals("fresh", contentsOf("honest.xopp"))
    }

    /**
     * The save that a provider without truncation support used to lose. `"wt"` is refused outright,
     * so the write has to fall back to `"w"` — and then trim the tail itself, or the shorter
     * document reopens with the end of the older, longer one still stuck to it.
     */
    @Test fun aProviderThatRefusesTruncationStillGetsExactlyTheNewBytes() {
        val target = awkward("no-truncate", "no-truncate.xopp")
        val scratch = staging.newFile("save").apply { writeText("fresh") }

        staging.stageOut(scratch, target)

        assertEquals("fresh", contentsOf("no-truncate.xopp"))
    }

    /**
     * The other half of the EBADF report: a provider that answers a write mode with a **read-only**
     * descriptor. The open succeeds, so the failure used to land on the first `write(2)` as a bare
     * `write failed: EBADF (Bad file descriptor)`. It must now be refused up front, named, and — the
     * part that matters to the user — leave the file that is already on disk alone.
     */
    @Test fun aReadOnlyDescriptorIsRefusedBeforeAnythingIsWritten() {
        val target = awkward("read-only", "read-only.xopp")
        val scratch = staging.newFile("save").apply { writeText("fresh") }

        val failure = assertThrows(IOException::class.java) {
            staging.stageOut(scratch, target)
        }

        val message = failure.message.orEmpty()
        assertTrue("should name the read-only descriptor, was: $message", message.contains("read-only descriptor"))
        assertFalse("should not surface a bare errno, was: $message", message.contains("EBADF"))
        assertEquals("the existing file must survive a refused save", LONG_STALE, contentsOf("read-only.xopp"))
    }

    /**
     * A document served by [AwkwardProvider] with the given [behaviour], holding [LONG_STALE] to
     * start with. Seeded through the provider's honest path — the backing file lives in the test
     * package's sandbox, which this process can't touch directly.
     */
    private fun awkward(behaviour: String, name: String): Uri {
        context.contentResolver.openOutputStream(AwkwardProvider.uri("honest", name), "wt").use {
            checkNotNull(it).write(LONG_STALE.toByteArray())
        }
        return AwkwardProvider.uri(behaviour, name)
    }

    /** What [AwkwardProvider] is holding for [name] now, read back the same way. */
    private fun contentsOf(name: String): String =
        context.contentResolver.openInputStream(AwkwardProvider.uri("honest", name)).use {
            checkNotNull(it).readBytes().decodeToString()
        }

    private companion object {
        /** Longer than anything the tests write, so a missing truncate shows up as a leftover tail. */
        const val LONG_STALE = "stale bytes from a previous, much longer save"
    }
}
