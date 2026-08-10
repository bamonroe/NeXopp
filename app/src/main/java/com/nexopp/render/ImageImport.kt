package com.nexopp.render

import android.graphics.BitmapFactory
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import java.io.File

/**
 * Builds a fresh [Document] from an imported raster image — the image counterpart of [PdfImport].
 * A `.xopp` has exactly one home for a picture behind the page, `<background type="pixmap">`, so an
 * opened PNG/JPEG/WebP becomes a **single page** the size of the image with an empty layer to draw
 * on. This object only shapes the model — actually painting the pixmap is the renderer's job.
 *
 * [reference] is the string stored as the background's `filename` — on Android the source image's
 * `content://` URI (`domain="absolute"`), which the open path resolves back to reload the picture.
 */
object ImageImport {

    /**
     * The page size in points for a [widthPx] × [heightPx] image. An image carries pixels, not a
     * physical size, so it is read at the 72-dpi baseline `.xopp` user space already uses: one pixel
     * becomes one point. That is what desktop Xournal++ does for a pixmap background, and it keeps
     * the page at the picture's own aspect ratio.
     */
    fun sizePt(widthPx: Int, heightPx: Int): Pair<Double, Double> =
        widthPx.toDouble() to heightPx.toDouble()

    /** The one-page document for an image of [widthPx] × [heightPx] pixels linked at [reference]. */
    fun documentFor(widthPx: Int, heightPx: Int, reference: String): Document {
        val (w, h) = sizePt(widthPx, heightPx)
        return Document(
            pages = listOf(
                Page(
                    width = w,
                    height = h,
                    background = Background.Pixmap(domain = ABSOLUTE_DOMAIN, filename = reference),
                    layers = listOf(Layer(emptyList())),
                ),
            ),
        )
    }

    /**
     * The pixel dimensions of the image in [file], decoded **bounds-only** so a photo the size of a
     * tablet screen never has to be held in memory just to size a page. Null when the bytes don't
     * decode — the sniffer said "image", but a truncated or corrupt file still gets that far.
     */
    fun pixelSize(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }
}
