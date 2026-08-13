package com.nexopp.io

import com.nexopp.format.SaveFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/** The name offered when a PDF- or image-backed document is saved for the first time. */
class SaveTargetTest {

    @Test
    fun `a pdf name becomes the same name as a xopp`() {
        assertEquals("bhm_prior.xopp", saveNameFor("bhm_prior.pdf", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `the extension match ignores case`() {
        assertEquals("Notes.xopp", saveNameFor("Notes.PDF", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `only the last extension is replaced`() {
        assertEquals("report.2026.xopp", saveNameFor("report.2026.pdf", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `a name with no extension just gains one`() {
        assertEquals("scan.xopp", saveNameFor("scan", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `a leading dot is part of the name, not an extension`() {
        assertEquals(".hidden.xopp", saveNameFor(".hidden", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `a name that is already a xopp is left alone`() {
        assertEquals("notes.xopp", saveNameFor("notes.xopp", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `a blank name falls back to Untitled`() {
        assertEquals("Untitled.xopp", saveNameFor("", SaveFormat.XOPP_GZIP))
        assertEquals("Untitled.xopp", saveNameFor("   ", SaveFormat.XOPP_GZIP))
    }

    @Test
    fun `the rnote format swaps in its own extension`() {
        assertEquals("bhm_prior.rnote", saveNameFor("bhm_prior.pdf", SaveFormat.RNOTE))
    }

    @Test
    fun `a name that is already a rnote is left alone`() {
        assertEquals("notes.rnote", saveNameFor("notes.rnote", SaveFormat.RNOTE))
    }

    @Test
    fun `a blank name falls back to Untitled in the save format`() {
        assertEquals("Untitled.rnote", saveNameFor("", SaveFormat.RNOTE))
    }
}
