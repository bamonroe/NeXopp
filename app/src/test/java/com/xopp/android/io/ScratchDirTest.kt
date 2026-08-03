package com.xopp.android.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The scratch folder's one promise: overlapping stages never share a path, so a slow remote open
 * can't overwrite the bytes another open is still parsing.
 */
class ScratchDirTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun allocationsAreUniqueEvenUnderOneName() {
        val scratch = ScratchDir(tmp.newFolder())
        val files = (1..50).map { scratch.newFile("open").apply { writeText("doc $it") } }
        assertEquals(50, files.map { it.absolutePath }.toSet().size)
        files.forEachIndexed { i, f -> assertEquals("doc ${i + 1}", f.readText()) }
    }

    @Test fun concurrentAllocationsDoNotCollide() {
        val scratch = ScratchDir(tmp.newFolder())
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..8).map {
            Thread { repeat(25) { paths.add(scratch.newFile("open").absolutePath) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(200, paths.toSet().size)
    }

    @Test fun newFileCreatesTheFolderOnDemand() {
        val dir = java.io.File(tmp.newFolder(), "missing")
        val file = ScratchDir(dir).newFile("save").apply { writeText("bytes") }
        assertTrue(file.isFile)
        assertEquals("bytes", file.readText())
    }

    @Test fun theNameIsUsedAsAReadablePrefix() {
        val file = ScratchDir(tmp.newFolder()).newFile("import")
        assertTrue(file.name, file.name.startsWith("import-") && file.name.endsWith(".tmp"))
    }
}
