package com.xopp.android.render

import com.xopp.android.format.model.Document
import com.xopp.android.format.model.TextElement
import java.util.Locale

/** One search match in page-local point geometry, ready for highlight painting and navigation. */
data class SearchHit(
    val pageIndex: Int,
    val boxes: List<Bounds>,
)

/** Surface-facing count state for the editor chrome. [current] is one-based, or zero when empty. */
data class SearchStatus(
    val current: Int = 0,
    val total: Int = 0,
)

/**
 * Finds text in authored text boxes and in the extracted background-PDF text layer. Typed text uses
 * the same rough box metrics as element hit-testing; PDF text highlights whole words that overlap
 * the matched character span.
 */
object DocumentSearch {

    private const val TEXT_CHAR_W = 0.62
    private const val TEXT_LINE_H = 1.3

    fun find(doc: Document, pdfTextIndex: PdfTextIndex?, rawQuery: String): List<SearchHit> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val needle = query.lowercase(Locale.ROOT)
        return buildList {
            for ((pageIndex, page) in doc.pages.withIndex()) {
                pdfTextIndex?.let { addAll(pdfHits(it, pageIndex, needle)) }
                for (layer in page.layers) {
                    for (element in layer.elements) {
                        if (element is TextElement) addAll(textHits(pageIndex, element, needle))
                    }
                }
            }
        }
    }

    private fun textHits(pageIndex: Int, text: TextElement, needle: String): List<SearchHit> =
        buildList {
            val charW = text.size * TEXT_CHAR_W
            val lineH = text.size * TEXT_LINE_H
            for ((lineIndex, line) in text.content.split("\n").withIndex()) {
                val haystack = line.lowercase(Locale.ROOT)
                var start = haystack.indexOf(needle)
                while (start >= 0) {
                    val top = text.y + lineIndex * lineH
                    add(
                        SearchHit(
                            pageIndex = pageIndex,
                            boxes = listOf(
                                Bounds(
                                    left = text.x + start * charW,
                                    top = top,
                                    right = text.x + (start + needle.length) * charW,
                                    bottom = top + lineH,
                                ),
                            ),
                        ),
                    )
                    start = haystack.indexOf(needle, start + 1)
                }
            }
        }

    private fun pdfHits(index: PdfTextIndex, pageIndex: Int, needle: String): List<SearchHit> {
        val words = index.words(pageIndex)
        if (words.isEmpty()) return emptyList()
        val text = StringBuilder()
        val ranges = ArrayList<IntRange>(words.size)
        for (word in words) {
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length
            text.append(word.text)
            ranges += start until text.length
        }
        val haystack = text.toString().lowercase(Locale.ROOT)
        return buildList {
            var start = haystack.indexOf(needle)
            while (start >= 0) {
                val span = start until (start + needle.length)
                val boxes = words.indices
                    .filter { ranges[it].overlaps(span) }
                    .map { words[it].toBounds() }
                if (boxes.isNotEmpty()) add(SearchHit(pageIndex, boxes))
                start = haystack.indexOf(needle, start + 1)
            }
        }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && last >= other.first

    private fun PdfWord.toBounds(): Bounds = Bounds(left, top, right, bottom)
}
