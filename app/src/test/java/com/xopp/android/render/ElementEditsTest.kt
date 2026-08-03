package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the text/image/LaTeX placement edits behind [TextEditController]. */
class ElementEditsTest {

    private fun text(content: String, x: Double = 0.0, y: Double = 0.0) =
        TextElement("Sans", 10.0, x, y, 0, content)

    private fun page(vararg layers: Layer) =
        Page(100.0, 100.0, Background.Solid(0, "plain"), layers.toList())

    private fun doc(vararg pages: Page) = Document(pages = pages.toList())

    @Test fun addElementLandsOnTheResolvedActiveLayer() {
        val d = doc(page(Layer(emptyList()), Layer(emptyList())))
        val out = ElementEdits.addElement(d, 0, text("hi")) { 0 }!!
        assertEquals(1, out.pages[0].layers[0].elements.size)
        assertTrue(out.pages[0].layers[1].elements.isEmpty())
    }

    @Test fun addElementClampsAnOutOfRangeActiveLayer() {
        val d = doc(page(Layer(emptyList())))
        val out = ElementEdits.addElement(d, 0, text("hi")) { 7 }!!
        assertEquals(1, out.pages[0].layers[0].elements.size)
    }

    @Test fun addElementGivesAPagelessDocumentALayerToLandOn() {
        val d = doc(page())
        val out = ElementEdits.addElement(d, 0, text("hi")) { 0 }!!
        assertEquals(1, out.pages[0].layers.single().elements.size)
    }

    @Test fun addElementIsNullForAMissingPage() {
        assertNull(ElementEdits.addElement(doc(page(Layer(emptyList()))), 3, text("hi")) { 0 })
    }

    @Test fun replaceElementSwapsByIdentityNotEquality() {
        val a = text("same")
        val b = text("same") // equal to a, but a different instance
        val d = doc(page(Layer(listOf(a, b))))
        val out = ElementEdits.replaceElement(d, b, text("changed"))!!
        val els = out.pages[0].layers[0].elements
        assertSame(a, els[0])
        assertEquals("changed", (els[1] as TextElement).content)
    }

    @Test fun replaceElementWithNullRemovesIt() {
        val a = text("gone")
        val out = ElementEdits.replaceElement(doc(page(Layer(listOf(a)))), a, null)!!
        assertTrue(out.pages[0].layers[0].elements.isEmpty())
    }

    @Test fun replaceElementIsNullWhenTheElementIsAbsent() {
        val d = doc(page(Layer(listOf(text("kept")))))
        assertNull(ElementEdits.replaceElement(d, text("absent"), null))
    }

    @Test fun pickTextReturnsTheTopMostHit() {
        val under = text("under")
        val over = text("over")
        val d = doc(page(Layer(listOf(under)), Layer(listOf(over))))
        assertSame(over, ElementEdits.pickText(d, 0, 1.0, 1.0))
    }

    @Test fun pickTextMissesEmptyPageAndUnknownPages() {
        val d = doc(page(Layer(listOf(text("hi")))))
        assertNull(ElementEdits.pickText(d, 0, 90.0, 90.0))
        assertNull(ElementEdits.pickText(d, 5, 1.0, 1.0))
    }

    @Test fun hitsTextCoversTheContentBoxWithASmallPad() {
        val t = text("abcd", x = 10.0, y = 20.0)
        assertTrue(ElementEdits.hitsText(t, 12.0, 22.0))
        assertTrue(ElementEdits.hitsText(t, 7.0, 17.0))   // inside the 4pt pad
        assertFalse(ElementEdits.hitsText(t, 5.0, 22.0))  // outside it
        assertFalse(ElementEdits.hitsText(t, 12.0, 60.0)) // below one line's height
    }

    @Test fun hitsTextGrowsWithLineCount() {
        val one = text("a", y = 0.0)
        val three = text("a\nb\nc", y = 0.0)
        assertFalse(ElementEdits.hitsText(one, 1.0, 30.0))
        assertTrue(ElementEdits.hitsText(three, 1.0, 30.0))
    }
}
