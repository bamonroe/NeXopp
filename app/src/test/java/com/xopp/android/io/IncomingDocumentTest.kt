package com.xopp.android.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which incoming intents count as "open this document", and which URI they hand over. */
class IncomingDocumentTest {

    private val doc = "content://com.android.providers.downloads/document/42"

    @Test
    fun `a VIEW intent hands over its data uri`() {
        assertEquals(doc, IncomingDocument.uriString("android.intent.action.VIEW", doc))
    }

    @Test
    fun `an EDIT intent hands over its data uri`() {
        assertEquals(doc, IncomingDocument.uriString("android.intent.action.EDIT", doc))
    }

    @Test
    fun `a SEND intent hands over its stream extra`() {
        assertEquals(doc, IncomingDocument.uriString("android.intent.action.SEND", null, doc))
    }

    @Test
    fun `the data uri wins over the stream extra`() {
        val other = "content://other/7"
        assertEquals(doc, IncomingDocument.uriString("android.intent.action.VIEW", doc, other))
    }

    @Test
    fun `a plain launcher start opens nothing`() {
        assertNull(IncomingDocument.uriString("android.intent.action.MAIN", null))
        assertNull(IncomingDocument.uriString("android.intent.action.MAIN", doc))
    }

    @Test
    fun `an open action with no uri at all opens nothing`() {
        assertNull(IncomingDocument.uriString("android.intent.action.VIEW", null))
        assertNull(IncomingDocument.uriString("android.intent.action.VIEW", "", ""))
        assertNull(IncomingDocument.uriString(null, doc))
    }
}
