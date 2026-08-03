package com.xopp.android.tabs

import com.xopp.android.format.SaveFormat
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import org.junit.Assert.assertEquals
import org.junit.Test

/** The session index round-trips every tab record, including titles that contain its separators. */
class TabIndexTest {

    private fun doc() = Document(
        pages = listOf(Page(100.0, 100.0, Background.Solid(-1, "plain"), listOf(Layer(emptyList())))),
    )

    private fun roundTrip(session: TabSession): TabSession =
        TabIndex.decode(TabIndex.encode(session)) { doc() }

    @Test
    fun `every field survives a round trip`() {
        val session = TabSession(
            tabs = listOf(
                OpenTab("t1", "notes.xopp", doc(), "content://docs/1", SaveFormat.ZIPPED, "/data/bg.pdf", 4),
                OpenTab("t2", "Untitled", doc()),
            ),
            activeIndex = 1,
        )
        val back = roundTrip(session)
        assertEquals(session.activeIndex, back.activeIndex)
        assertEquals(session.tabs.map { it.copy(document = doc()) }, back.tabs)
    }

    @Test
    fun `a title containing tabs and newlines cannot break the record layout`() {
        val session = TabSession(listOf(OpenTab("t1", "a\tb\nc\\d", doc())), 0)
        val back = roundTrip(session)
        assertEquals(1, back.tabs.size)
        assertEquals("a\tb\nc\\d", back.tabs[0].title)
    }

    @Test
    fun `a truncated line is skipped rather than failing the whole restore`() {
        val text = "active\t0\nt1\tone\t\tORIGINAL\t\t0\nbroken\n"
        val back = TabIndex.decode(text) { doc() }
        assertEquals(listOf("t1"), back.tabs.map { it.id })
    }

    @Test
    fun `an unknown save format falls back to ORIGINAL`() {
        val back = TabIndex.decode("active\t0\nt1\tone\t\tBOGUS\t\t0\n") { doc() }
        assertEquals(SaveFormat.ORIGINAL, back.tabs[0].format)
    }

    @Test
    fun `an active index past the end is clamped`() {
        val back = TabIndex.decode("active\t9\nt1\tone\t\tORIGINAL\t\t0\n") { doc() }
        assertEquals(0, back.activeIndex)
    }

    @Test
    fun `an empty index decodes to an empty session`() {
        val back = TabIndex.decode("") { doc() }
        assertEquals(emptyList<OpenTab>(), back.tabs)
        assertEquals(0, back.activeIndex)
    }
}
