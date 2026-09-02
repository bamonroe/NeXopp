package com.nexopp.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The string half of PDF background references: which shape a reference is, and how the relative
 * one — the only portable one — is derived and resolved. See [PdfReference].
 */
class PdfReferenceTest {

    @Test
    fun `a bare name or subpath is relative`() {
        assertTrue(PdfReference.isRelative("bg.pdf"))
        assertTrue(PdfReference.isRelative("scans/bg.pdf"))
        assertTrue(PdfReference.isRelative("../shared/bg.pdf"))
    }

    @Test
    fun `absolute paths and URIs are not relative`() {
        assertFalse(PdfReference.isRelative("/home/bam/bg.pdf"))
        assertFalse(PdfReference.isRelative("content://x/document/1"))
        assertFalse(PdfReference.isRelative("file:///tmp/bg.pdf"))
        assertFalse(PdfReference.isRelative(""))
    }

    @Test
    fun `an attach reference names the sibling desktop resolves`() {
        assertEquals("notes.xopp.bg.pdf", PdfReference.attachSiblingName("notes.xopp", "bg.pdf"))
    }

    @Test
    fun `a relative reference resolves against the document folder`() {
        assertEquals("home/bam/bg.pdf", PdfReference.resolveRelative("/home/bam/notes.xopp", "bg.pdf"))
        assertEquals(
            "home/bam/scans/bg.pdf",
            PdfReference.resolveRelative("/home/bam/notes.xopp", "scans/bg.pdf"),
        )
    }

    @Test
    fun `dot segments fold away and an escaping reference is refused`() {
        assertEquals("home/bg.pdf", PdfReference.resolveRelative("/home/bam/notes.xopp", "../bg.pdf"))
        assertEquals("home/bam/bg.pdf", PdfReference.resolveRelative("/home/bam/notes.xopp", "./bg.pdf"))
        assertNull(PdfReference.resolveRelative("/notes.xopp", "../../bg.pdf"))
    }

    @Test
    fun `a same-folder PDF relativises to its bare name`() {
        assertEquals("bg.pdf", PdfReference.relativeReference("/home/bam/notes.xopp", "/home/bam/bg.pdf"))
        assertEquals(
            "scans/bg.pdf",
            PdfReference.relativeReference("/home/bam/notes.xopp", "/home/bam/scans/bg.pdf"),
        )
    }

    @Test
    fun `a PDF outside the document folder does not relativise`() {
        assertNull(PdfReference.relativeReference("/home/bam/notes.xopp", "/tmp/bg.pdf"))
        assertNull(PdfReference.relativeReference("/home/bam/notes.xopp", "/home/other/bg.pdf"))
    }

    @Test
    fun `SAF document ids split into volume and path`() {
        assertEquals("primary" to "Docs/notes.xopp", PdfReference.splitDocumentId("primary:Docs/notes.xopp"))
        assertEquals("" to "raw", PdfReference.splitDocumentId("raw"))
        assertEquals("primary:Docs/a", PdfReference.joinDocumentId("primary", "Docs/a"))
    }

    @Test
    fun `two ids in the same SAF folder relativise`() {
        assertEquals(
            "bg.pdf",
            PdfReference.relativeDocumentId("primary:Docs/notes.xopp", "primary:Docs/bg.pdf"),
        )
        assertEquals(
            "scans/bg.pdf",
            PdfReference.relativeDocumentId("primary:Docs/notes.xopp", "primary:Docs/scans/bg.pdf"),
        )
        // Both at the volume root: still a real folder, so still relativisable.
        assertEquals(
            "bg.pdf",
            PdfReference.relativeDocumentId("primary:notes.xopp", "primary:bg.pdf"),
        )
    }

    @Test
    fun `only ids that look like paths relativise`() {
        assertTrue(PdfReference.isPathShaped("primary:notes.xopp"))
        assertTrue(PdfReference.isPathShaped("primary:Docs/notes.xopp"))
        assertTrue(PdfReference.isPathShaped("Docs/notes.xopp"))
        // Dropbox hands out a bare UUID: no volume root, no separator, no folder to speak of.
        assertFalse(PdfReference.isPathShaped("a504fa52-9f52-4a7f-9e93-52c6f81a3be7"))
        assertFalse(PdfReference.isPathShaped("notes.xopp"))
    }

    @Test
    fun `opaque provider ids never relativise into a bogus sibling name`() {
        assertNull(
            PdfReference.relativeDocumentId(
                "a504fa52-9f52-4a7f-9e93-52c6f81a3be7",
                "b1e7c3d0-1f44-4c02-8f0a-6d2b9a5e7c11",
            ),
        )
        assertNull(
            PdfReference.relativeDocumentId(
                "primary:Docs/notes.xopp",
                "b1e7c3d0-1f44-4c02-8f0a-6d2b9a5e7c11",
            ),
        )
    }

    @Test
    fun `a relative reference does not resolve against an opaque id`() {
        assertNull(
            PdfReference.resolveRelativeDocumentId("a504fa52-9f52-4a7f-9e93-52c6f81a3be7", "bg.pdf"),
        )
    }

    @Test
    fun `an opaque reference beside an opaque id is tried as a document id of its own`() {
        // What an older build wrote into a Dropbox-hosted .xopp: the PDF's whole document id as the
        // "relative" name. Wrong off-device, but the provider that wrote it still knows the id.
        val xopp = "a504fa52-9f52-4a7f-9e93-52c6f81a3be7"
        val pdf = "b1e7c3d0-1f44-4c02-8f0a-6d2b9a5e7c11"
        assertEquals(pdf, PdfReference.siblingDocumentId(xopp, pdf))
        // A genuine path-shaped id still resolves as a sibling, never as a whole id.
        assertEquals(
            "primary:Docs/bg.pdf",
            PdfReference.siblingDocumentId("primary:Docs/notes.xopp", "bg.pdf"),
        )
        // A multi-segment reference is a path, so an opaque id has nothing to resolve it against.
        assertNull(PdfReference.siblingDocumentId(xopp, "scans/bg.pdf"))
        assertNull(PdfReference.siblingDocumentId(xopp, ""))
    }

    @Test
    fun `ids on different volumes or folders do not relativise`() {
        assertNull(PdfReference.relativeDocumentId("primary:Docs/notes.xopp", "sdcard:Docs/bg.pdf"))
        assertNull(PdfReference.relativeDocumentId("primary:Docs/notes.xopp", "primary:Other/bg.pdf"))
        // An opaque Downloads-style id has no path to compare against.
        assertNull(PdfReference.relativeDocumentId("primary:Docs/notes.xopp", "msf:1234"))
    }

    @Test
    fun `a relative reference resolves back to a sibling document id`() {
        assertEquals(
            "primary:Docs/bg.pdf",
            PdfReference.resolveRelativeDocumentId("primary:Docs/notes.xopp", "bg.pdf"),
        )
        assertEquals(
            "primary:Docs/notes.xopp.bg.pdf",
            PdfReference.resolveRelativeDocumentId("primary:Docs/notes.xopp", "notes.xopp.bg.pdf"),
        )
    }

    @Test
    fun `relativise then resolve is a round trip`() {
        val xopp = "primary:Docs/notes.xopp"
        val pdf = "primary:Docs/scans/bg.pdf"
        val relative = PdfReference.relativeDocumentId(xopp, pdf)!!
        assertEquals(pdf, PdfReference.resolveRelativeDocumentId(xopp, relative))
    }
}
