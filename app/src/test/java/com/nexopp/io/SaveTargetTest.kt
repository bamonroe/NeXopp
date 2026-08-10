package com.nexopp.io

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name offered when a PDF-backed document is saved as a `.xopp` for the first time. */
class SaveTargetTest {

    @Test
    fun `a pdf name becomes the same name as a xopp`() {
        assertEquals("bhm_prior.xopp", xoppNameFor("bhm_prior.pdf"))
    }

    @Test
    fun `the extension match ignores case`() {
        assertEquals("Notes.xopp", xoppNameFor("Notes.PDF"))
    }

    @Test
    fun `only the last extension is replaced`() {
        assertEquals("report.2026.xopp", xoppNameFor("report.2026.pdf"))
    }

    @Test
    fun `a name with no extension just gains one`() {
        assertEquals("scan.xopp", xoppNameFor("scan"))
    }

    @Test
    fun `a leading dot is part of the name, not an extension`() {
        assertEquals(".hidden.xopp", xoppNameFor(".hidden"))
    }

    @Test
    fun `a name that is already a xopp is left alone`() {
        assertEquals("notes.xopp", xoppNameFor("notes.xopp"))
    }

    @Test
    fun `a blank name falls back to Untitled`() {
        assertEquals("Untitled.xopp", xoppNameFor(""))
        assertEquals("Untitled.xopp", xoppNameFor("   "))
    }
}
