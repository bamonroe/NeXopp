/**
 * [DrawingSurfaceView]'s background selection tool: a rectangle-only marquee that copies a
 * flattened page region into the app's image clipboard.
 */
package com.xopp.android.render

import android.graphics.Bitmap
import android.view.MotionEvent
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.ImageElement
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Down with the background-select tool: clear other edit modes and start a rectangle marquee. */
internal fun DrawingSurfaceView.beginBackgroundSelect(event: MotionEvent) {
    scrolling = false
    erasing = false
    placing = false
    current = null
    gestures.clearSelection()
    clearTextSelection()
    val page = layout.pageAt(event.x + scrollX, event.y + scrollY) ?: return
    backgroundSelecting = true
    backgroundSelectPage = page.index
    backgroundSelectX0 = event.x
    backgroundSelectY0 = event.y
    backgroundSelectX1 = event.x
    backgroundSelectY1 = event.y
    render()
}

/** Drag the live background-copy rectangle. */
internal fun DrawingSurfaceView.backgroundSelectMove(event: MotionEvent) {
    backgroundSelectX1 = event.x
    backgroundSelectY1 = event.y
    render()
}

/** Release the background-copy rectangle and put the flattened region on the image clipboard. */
internal fun DrawingSurfaceView.commitBackgroundSelect() {
    if (!backgroundSelecting) return
    val pageIndex = backgroundSelectPage
    backgroundSelecting = false
    backgroundSelectPage = -1
    val box = layout.boxes.getOrNull(pageIndex)
    val page = doc.pages.getOrNull(pageIndex)
    if (box == null || page == null) {
        render()
        return
    }
    if (hypot(backgroundSelectX1 - backgroundSelectX0, backgroundSelectY1 - backgroundSelectY0) <=
        DrawingSurfaceDefaults.TAP_SLOP_PX
    ) {
        render()
        return
    }
    val region = backgroundSelectionRegion(box) ?: run {
        render()
        return
    }
    val pageImage = backgroundSelectionPageImage(page.background, box.widthPx.toInt())
    val bitmap = PageRegionRenderer.render(page, region, box.scale, pageImage = pageImage) ?: run {
        render()
        return
    }
    val bytes = bitmap.toPngBytes()
    bitmap.recycle()
    if (bytes == null) {
        render()
        return
    }
    clipboard = listOf(ImageElement(region.left, region.top, region.right, region.bottom, bytes))
    onClipboardChanged?.invoke(true)
    render()
}

/** The dragged rectangle, converted to page-local pt and clipped to the page. */
internal fun DrawingSurfaceView.backgroundSelectionRegion(box: PageBox): Bounds? {
    val left = min(box.toPtX(backgroundSelectX0, scrollX), box.toPtX(backgroundSelectX1, scrollX))
        .coerceIn(0.0, box.page.width)
    val top = min(box.toPtY(backgroundSelectY0, scrollY), box.toPtY(backgroundSelectY1, scrollY))
        .coerceIn(0.0, box.page.height)
    val right = max(box.toPtX(backgroundSelectX0, scrollX), box.toPtX(backgroundSelectX1, scrollX))
        .coerceIn(0.0, box.page.width)
    val bottom = max(box.toPtY(backgroundSelectY0, scrollY), box.toPtY(backgroundSelectY1, scrollY))
        .coerceIn(0.0, box.page.height)
    if (right <= left || bottom <= top) return null
    return Bounds(left, top, right, bottom)
}

/** Synchronously resolve the full-page background bitmap for a flattened copy. */
private fun DrawingSurfaceView.backgroundSelectionPageImage(background: Background, targetWidthPx: Int): Bitmap? =
    when (background) {
        is Background.Pdf -> pdfSource?.render(background.pageNo, targetWidthPx)
        is Background.Pixmap -> imageSource.render(background.filename, targetWidthPx)
        else -> null
    }

private fun Bitmap.toPngBytes(): ByteArray? {
    val out = ByteArrayOutputStream()
    return if (compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
}
