package com.xopp.android.format

import com.xopp.android.format.model.Document
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Top-level `.xopp` I/O: gzip on the outside, XML within. This is the whole round-trip surface
 * the app builds on. Uses only the JDK's `java.util.zip` — no third-party format libraries (see
 * `docs/architecture.md`).
 */
object Xopp {

    /** Read a `.xopp` stream (gzip + XML) into a [Document]. Does not close [input]. */
    fun open(input: InputStream): Document {
        val xml = GZIPInputStream(input).reader(Charsets.UTF_8).use { it.readText() }
        return XoppReader(xml).read()
    }

    /** Parse decompressed XML directly (used by tests and drift checks). */
    fun parseXml(xml: String): Document = XoppReader(xml).read()

    /** Serialise a [Document] to `.xopp` bytes (XML + gzip) on [output]. Does not close it. */
    fun save(doc: Document, output: OutputStream) {
        GZIPOutputStream(output).use { gz ->
            gz.writer(Charsets.UTF_8).use { w -> XoppWriter(w).write(doc) }
        }
    }

    /** Serialise a [Document] to XML text (no gzip) — for tests and drift checks. */
    fun toXml(doc: Document): String = StringBuilder().also { XoppWriter(it).write(doc) }.toString()
}
