package com.xopp.android.render

import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentSearchTest {

    @Test fun findsTypedTextCaseInsensitively() {
        val doc = document(
            TextElement("Sans", 10.0, 20.0, 30.0, 0, "Alpha beta\nalpha"),
        )

        val hits = DocumentSearch.find(doc, null, "ALPHA")

        assertEquals(2, hits.size)
        assertEquals(Bounds(20.0, 30.0, 51.0, 43.0), hits[0].boxes.single())
        assertEquals(Bounds(20.0, 43.0, 51.0, 56.0), hits[1].boxes.single())
    }

    @Test fun findsBackgroundPdfPhraseAcrossWords() {
        val doc = document()
        val pdf = PdfTextIndex(
            listOf(
                listOf(
                    PdfWord("hello", 10.0, 20.0, 40.0, 30.0),
                    PdfWord("world", 45.0, 20.0, 80.0, 30.0),
                ),
            ),
        )

        val hits = DocumentSearch.find(doc, pdf, "lo wo")

        assertEquals(1, hits.size)
        assertEquals(
            listOf(Bounds(10.0, 20.0, 40.0, 30.0), Bounds(45.0, 20.0, 80.0, 30.0)),
            hits.single().boxes,
        )
    }

    private fun document(vararg elements: TextElement): Document =
        Document(pages = listOf(blankPage().copy(layers = listOf(Layer(elements.toList())))))
}
