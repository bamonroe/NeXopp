package com.nexopp.render

import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page

/**
 * Builds a fresh [Document] whose pages are backed by the pages of an imported PDF — the same shape
 * desktop Xournal++ produces when you "annotate a PDF": one `.xopp` page per PDF page, each with a
 * `<background type="pdf">` and an empty layer to draw on. Only the first page carries `filename`
 * and `domain`; the rest reference the same PDF by `pageno` alone, matching the on-disk convention
 * (see `docs/architecture.md`). The actual rasterisation is done by [PdfPageCache].
 *
 * [reference] is the string stored as the background's `filename` — on Android that's the source
 * PDF's `content://` URI (domain="absolute"), which the open path resolves back to reload the PDF.
 */
object PdfImport {

    fun documentFor(cache: PdfPageCache, reference: String): Document =
        Document(pages = pagesFor(cache, reference))

    /**
     * One `.xopp` page per page of [cache], ready to become a whole document ([documentFor]) or to be
     * appended after an existing document's pages ([PageOps.appendPages]).
     *
     * [reference] is carried by the *first* produced page (with `domain="absolute"`), so these pages
     * normally land as the document's only PDF-backed run. Pass `null` when appending onto a document
     * that already carries the reference — the case where the incoming PDF has been merged into the
     * existing background PDF ([PdfMerger]) and the existing reference is re-pointed at the joined
     * file instead. [pageNoOffset] is then where these pages start inside that joined PDF.
     */
    fun pagesFor(cache: PdfPageCache, reference: String?, pageNoOffset: Int = 0): List<Page> =
        (0 until cache.pageCount).map { i ->
            val (w, h) = cache.pageSizePt(i)
            Page(
                width = w,
                height = h,
                background = Background.Pdf(
                    filename = if (i == 0) reference else null,
                    pageNo = i + pageNoOffset,
                    domain = if (i == 0 && reference != null) ABSOLUTE_DOMAIN else null,
                ),
                layers = listOf(Layer(emptyList())),
            )
        }
}
