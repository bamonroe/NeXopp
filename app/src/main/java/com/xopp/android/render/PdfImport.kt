package com.xopp.android.render

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page

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
     * The pages one `.xopp` page per PDF page, ready to become a whole document ([documentFor]) or to
     * be appended after an existing document's pages ([PageOps.appendPages]). Only the first page
     * carries `filename`/`domain`, so these pages must land as the document's *only* PDF-backed run.
     */
    fun pagesFor(cache: PdfPageCache, reference: String): List<Page> =
        (0 until cache.pageCount).map { i ->
            val (w, h) = cache.pageSizePt(i)
            Page(
                width = w,
                height = h,
                background = Background.Pdf(
                    filename = if (i == 0) reference else null,
                    pageNo = i,
                    domain = if (i == 0) "absolute" else null,
                ),
                layers = listOf(Layer(emptyList())),
            )
        }
}
