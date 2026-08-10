package com.nexopp.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test

/**
 * Round-trips the real `udiff.xopp` sample at the repo root (3981 strokes) when it is present.
 * Validates the parser/writer against a genuine desktop Xournal++ file, not just synthetic XML.
 * Skipped (not failed) when the fixture is absent so CI without it stays green.
 */
class RealFileRoundTripTest {

    private fun sample(): File =
        listOf("../udiff.xopp", "udiff.xopp").map(::File).firstOrNull { it.exists() } ?: File("../udiff.xopp")

    @Test fun realFileReserializesToEqualModel() {
        val f = sample()
        assumeTrue("udiff.xopp fixture not present", f.exists())

        val doc1 = f.inputStream().use { Xopp.open(it) }
        assertTrue("expected at least one page", doc1.pages.isNotEmpty())
        val strokeCount = doc1.pages.sumOf { p -> p.layers.sumOf { l -> l.elements.size } }
        assertTrue("expected many strokes", strokeCount > 1000)

        val doc2 = Xopp.parseXml(Xopp.toXml(doc1))
        assertEquals(doc1, doc2)
    }
}
