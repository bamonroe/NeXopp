/**
 * [DrawingSurfaceView]'s render loop and every brush stroke it puts on the canvas: the frame pacing
 * (`requestRender`/`render`), the page compositing pass (`paint` and its background/PDF-tile
 * helpers), and the chrome overlays drawn on top — selection, guide, page drag, eraser tip, hover
 * and the marquee band. Kept out of the view class itself so the surface stays a manageable size;
 * these are extensions on the view and read its state directly.
 */
package com.xopp.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.SurfaceHolder
import com.xopp.android.render.CanvasChrome.Companion.HANDLE_DRAW_PX
import com.xopp.android.format.model.Background
import kotlin.math.max
import kotlin.math.min

internal fun DrawingSurfaceView.relayout() {
    layout = PageStacker.stack(doc.pages, width, DrawingSurfaceDefaults.GAP_PX, zoom, columns)
    viewport.setBounds(width.toFloat(), height.toFloat(), layout.contentWidthPx, layout.totalHeightPx)
}

/**
 * Ask for one redraw from a background thread, collapsing a burst into a single frame. Several
 * tiles of the same pan often finish within one frame's time; posting a [render] for each would
 * repaint the whole viewport that many times over.
 */
internal fun DrawingSurfaceView.requestRender() {
    if (renderPosted.getAndSet(true)) return
    post {
        renderPosted.set(false)
        render()
    }
}

/**
 * Ask for a repaint at the next display frame, collapsing everything asked for in between into
 * one [paint].
 *
 * Painting is **never** done straight from an input handler. A stylus/touch digitiser reports
 * far faster than the display refreshes (240 Hz against 120 Hz on the large tablets), so a
 * synchronous paint per event posts two or more buffers per vsync. The compositor latches
 * whichever happens to be newest at each vsync, so the position it shows walks back and forth
 * between samples instead of advancing — the flicker seen when zoomed in on a big screen, where
 * a paint is slow enough to keep several buffers in flight. Pacing to the [Choreographer] posts
 * exactly one buffer per vsync, in phase, so every frame shown is the newest state.
 */
internal fun DrawingSurfaceView.render() {
    // A glide already paints once per vsync from [MomentumDriver]; posting a second callback for the
    // same frame would post two buffers per vsync and bring back exactly the buffer-walk flicker
    // above. This is the common case when zoomed in and panning: every PDF tile that lands calls
    // back into [requestRender] mid-fling. Whatever asked for this repaint is shown by the glide's
    // own frame anyway, so dropping the request loses nothing.
    if (paintPosted || momentum.isFlinging) return
    paintPosted = true
    choreographer.postFrameCallback(paintCallback)
}

/**
 * Lock the surface for one frame, preferring the **GPU** canvas.
 *
 * [SurfaceHolder.lockCanvas] hands back a *software* canvas: every pixel of the window is
 * rasterised and blended on the CPU, so a frame costs time proportional to the window's pixel
 * area no matter how little is on the page. On a large tablet that is several million pixels a
 * frame, which is why flicking pages full-screen crawls while the identical gesture in a
 * split-screen (half the pixels) stays smooth. [SurfaceHolder.lockHardwareCanvas] records the
 * same draw calls and replays them on the GPU, where fill rate is essentially free and the
 * cached page bitmaps become plain textured blits.
 *
 * Falls back to the software canvas if the hardware one is unavailable (no GL context, an
 * emulator without a working renderer), so the view still paints rather than going black.
 */
internal fun DrawingSurfaceView.lockCanvasForFrame(): Canvas? =
    try {
        holder.lockHardwareCanvas()
    } catch (_: IllegalStateException) {
        null
    } ?: holder.lockCanvas()

internal fun DrawingSurfaceView.paint() {
    paintPosted = false
    if (!holder.surface.isValid) return
    val canvas = lockCanvasForFrame() ?: return
    try {
        canvas.drawColor(chrome.backdropColor)
        val visible = layout.visible(scrollY, height.toFloat())
        for (box in visible) {
            BackgroundRenderer.draw(
                canvas, box, scrollX, scrollY, pageBitmapFor(box), pdfTilesFor(box),
                width.toFloat(), height.toFloat(),
            )
            drawPageElements(canvas, box)
        }
        inkCache.retain(visible.mapTo(HashSet()) { it.index })
        retainPdfPins(visible)
        prefetchAround(visible)
        drawCurrent(canvas)
        drawTextSelection(canvas)
        selection?.let { drawSelectionBox(canvas, it) }
        if (overview.selected.isNotEmpty()) drawPageSelection(canvas)
        if (overview.dragging) drawPageDrag(canvas)
        if (gestures.banding) drawBand(canvas)
        if (vspace.active) drawVerticalSpaceGuide(canvas)
        drawGuide(canvas)
        if (erasing) drawEraserTip(canvas, eraseX, eraseY)
        if (hovering && showHover && current == null && !erasing) {
            if (erasesNow()) drawEraserTip(canvas, hoverX, hoverY) else drawHover(canvas)
        }
        paletteOverlay?.let {
            RadialPaletteRenderer.draw(canvas, chrome, it, width.toFloat(), height.toFloat())
        }
    } finally {
        holder.unlockCanvasAndPost(canvas)
    }
    reportCurrentPage()
    reportScroll()
}

/**
 * The picture to blit behind [box] — the rasterised PDF page for a `pdf` background, the decoded
 * image for a `pixmap` one, null for anything else (the renderer then draws sheet and ruling).
 * Both caches fill asynchronously, so this never stalls a frame.
 */
internal fun DrawingSurfaceView.pageBitmapFor(box: PageBox): Bitmap? = when (val bg = box.page.background) {
    is Background.Pdf -> pdfSource?.request(bg.pageNo, box.widthPx.toInt())
    is Background.Pixmap -> imageSource.request(bg.filename, box.widthPx.toInt())
    else -> null
}

/**
 * Open the bytes a `pixmap` background's `filename` points at. The local copy the loader made
 * ([setImageSources]) wins whenever there is one — it is the only form that works for a picture
 * bundled in a ZIP package or living beside the `.xopp`, and it outlives the grant the original
 * was opened under. Otherwise the reference is opened directly: a `content://` URI through the
 * resolver, a plain path off disk (what a desktop-written `.xopp` carries).
 */
internal fun DrawingSurfaceView.openBackgroundImage(reference: String): java.io.InputStream? = runCatching {
    imageBackgrounds[reference]?.takeIf { it.isFile }?.let { return@runCatching it.inputStream() }
    if (reference.contains("://")) {
        context.contentResolver.openInputStream(android.net.Uri.parse(reference))
    } else {
        java.io.File(reference).takeIf { it.isFile }?.inputStream()
    }
}.getOrNull()

/**
 * The full-resolution tiles for the visible part of a `pdf`-backed page, drawn over the
 * (upscaled) whole-page bitmap. Empty until the zoom passes the whole-page raster ceiling, so
 * normal reading costs exactly what it did before. Like [pageBitmapFor] this never blocks: a tile
 * that isn't cached yet is queued and arrives on a later frame.
 */
internal fun DrawingSurfaceView.pdfTilesFor(box: PageBox): List<PdfTile> {
    val bg = box.page.background as? Background.Pdf ?: return emptyList()
    val src = pdfSource ?: return emptyList()
    if (box.widthPx <= 0f || box.heightPx <= 0f) return emptyList()
    val x = scrollX - box.leftPx
    val y = scrollY - box.topPx
    return src.requestTiles(
        bg.pageNo, box.widthPx.toInt(),
        (x / box.widthPx).coerceIn(0f, 1f),
        (y / box.heightPx).coerceIn(0f, 1f),
        ((x + width) / box.widthPx).coerceIn(0f, 1f),
        ((y + height) / box.heightPx).coerceIn(0f, 1f),
    )
}

/**
 * Tell the PDF cache which pages are still on screen, so it stops protecting the tiles of pages
 * we have scrolled past. Uses the PDF page numbers, the same key [pdfTilesFor] pins under.
 */
internal fun DrawingSurfaceView.retainPdfPins(visible: List<PageBox>) {
    val src = pdfSource ?: return
    src.retain(visible.mapNotNullTo(HashSet()) { (it.page.background as? Background.Pdf)?.pageNo })
}

/**
 * Warm the pages just outside the viewport so scrolling meets a filled cache rather than a
 * rasterise. One page either side is enough to cover a flick at reading speed.
 */
internal fun DrawingSurfaceView.prefetchAround(visible: List<PageBox>) {
    val src = pdfSource ?: return
    if (visible.isEmpty()) return
    val first = visible.first().index
    val last = visible.last().index
    for (i in intArrayOf(first - 1, last + 1)) {
        val box = layout.boxes.getOrNull(i) ?: continue
        val bg = box.page.background as? Background.Pdf ?: continue
        src.prefetch(bg.pageNo, box.widthPx.toInt())
    }
}

/**
 * Paint one page's ink. The [InkCache] handles it as a single blit whenever it can; a gesture
 * that rewrites the page every frame (drag/resize/rotate/erase) would only thrash the raster, so
 * those fall through to submitting elements directly — as does any page too large to cache.
 */
internal fun DrawingSurfaceView.drawPageElements(canvas: Canvas, box: PageBox) {
    val hidden = if (hiddenLayers.isEmpty()) {
        emptySet()
    } else {
        box.page.layers.indices.filterTo(HashSet()) { isLayerHidden(box.index, it) }
    }
    if (inkCacheUsable &&
        inkCache.draw(canvas, box, scrollX, scrollY, hidden, strokePainter, elementRenderer)
    ) {
        return
    }
    PageRenderer.drawElements(
        canvas, box.page, box.scale, box.leftPx - scrollX, box.topPx - scrollY,
        strokePainter, elementRenderer, hidden, visibleBounds(box),
    )
}

/**
 * The viewport in this page's local pt space, so [PageRenderer] can drop elements that can't be
 * on screen. At high zoom a page spans many screens, where almost every stroke is off-screen and
 * submitting it costs thousands of canvas calls Skia would only clip away.
 */
internal fun DrawingSurfaceView.visibleBounds(box: PageBox): Bounds? {
    if (box.scale <= 0f) return null
    val s = box.scale.toDouble()
    val left = box.toPtX(0f, scrollX)
    val top = box.toPtY(0f, scrollY)
    return Bounds(left, top, left + width / s, top + height / s)
}

internal fun DrawingSurfaceView.drawCurrent(canvas: Canvas) {
    val pts = current ?: return
    val box = layout.boxes.getOrNull(currentPage) ?: return
    strokePainter.draw(
        canvas, pts, tool, strokeColor(), box.scale, box.leftPx - scrollX, box.topPx - scrollY,
        currentLineStyle, currentFill,
    )
}

/** Highlight the selected PDF-text word boxes (the same frame as strokes, so it tracks scroll/zoom). */
internal fun DrawingSurfaceView.drawTextSelection(canvas: Canvas) {
    val index = pdfTextIndex ?: return
    if (textSelPage < 0 || textSelAnchor < 0) return
    val box = layout.boxes.getOrNull(textSelPage) ?: return
    for (w in index.rangeBoxes(textSelPage, textSelAnchor, textSelFocus)) {
        val l = (w.left * box.scale + box.leftPx - scrollX).toFloat()
        val t = (w.top * box.scale + box.topPx - scrollY).toFloat()
        val r = (w.right * box.scale + box.leftPx - scrollX).toFloat()
        val b = (w.bottom * box.scale + box.topPx - scrollY).toFloat()
        canvas.drawRect(l, t, r, b, chrome.textSelect)
    }
}

/** Draw the dashed selection outline (padded a little), the four resize handles, and — for an
 * all-stroke selection — the top rotate knob. */
internal fun DrawingSurfaceView.drawSelectionBox(canvas: Canvas, sel: ActiveSelection) {
    val box = layout.boxes.getOrNull(sel.pageIndex) ?: return
    val page = doc.pages.getOrNull(sel.pageIndex) ?: return
    val b = SelectionTester.boundsOf(page, sel.refs) ?: return
    val l = (b.left * box.scale + box.leftPx - scrollX).toFloat() - DrawingSurfaceDefaults.SELECT_PAD_PX
    val t = (b.top * box.scale + box.topPx - scrollY).toFloat() - DrawingSurfaceDefaults.SELECT_PAD_PX
    val r = (b.right * box.scale + box.leftPx - scrollX).toFloat() + DrawingSurfaceDefaults.SELECT_PAD_PX
    val bot = (b.bottom * box.scale + box.topPx - scrollY).toFloat() + DrawingSurfaceDefaults.SELECT_PAD_PX
    canvas.drawRect(l, t, r, bot, chrome.selectionFill)
    canvas.drawRect(l, t, r, bot, chrome.selectionStroke)
    // Corner resize handles.
    for (hx in floatArrayOf(l, r)) for (hy in floatArrayOf(t, bot)) {
        canvas.drawCircle(hx, hy, HANDLE_DRAW_PX, chrome.handle)
    }
    // Rotate knob poking out midway from the right edge (strokes only).
    if (gestures.isAllStrokes(sel)) {
        val midY = (t + bot) / 2f
        val knobX = r + DrawingSurfaceDefaults.ROTATE_ARM_PX
        canvas.drawLine(r, midY, knobX, midY, chrome.handleArm)
        canvas.drawCircle(knobX, midY, HANDLE_DRAW_PX, chrome.handle)
    }
}

/** Draw the guide overlay in view px: the setsquare's outline, or the compass's circle and hub. */
internal fun DrawingSurfaceView.drawGuide(canvas: Canvas) {
    val g = guide ?: return
    val box = layout.boxes.getOrNull(guideDrag.page) ?: return
    val s = box.scale
    fun vx(x: Double) = box.toViewX(x, scrollX)
    fun vy(y: Double) = box.toViewY(y, scrollY)
    when (g) {
        is DrawingGuide.Setsquare -> {
            val c = g.corners()
            chrome.guidePath.reset()
            chrome.guidePath.moveTo(vx(c[0].first), vy(c[0].second))
            chrome.guidePath.lineTo(vx(c[1].first), vy(c[1].second))
            chrome.guidePath.lineTo(vx(c[2].first), vy(c[2].second))
            chrome.guidePath.close()
            canvas.drawPath(chrome.guidePath, chrome.guideFill)
            canvas.drawPath(chrome.guidePath, chrome.guideStroke)
        }
        is DrawingGuide.Compass -> {
            canvas.drawCircle(vx(g.x), vy(g.y), (g.radius * s).toFloat(), chrome.guideStroke)
            canvas.drawCircle(vx(g.x), vy(g.y), HANDLE_DRAW_PX, chrome.guideHandle)
        }
    }
    val tip = guideDrag.tipOf(g)
    canvas.drawCircle(vx(tip.first), vy(tip.second), HANDLE_DRAW_PX, chrome.guideHandle)
}

/** Grey out the lifted page and outline the slot it would drop into. */
internal fun DrawingSurfaceView.drawPageDrag(canvas: Canvas) {
    layout.boxes.getOrNull(overview.dragIndex)?.let { box ->
        canvas.drawRect(
            box.leftPx - scrollX, box.topPx - scrollY,
            box.rightPx - scrollX, box.bottomPx - scrollY, chrome.pageLift,
        )
    }
    layout.boxes.getOrNull(overview.dropIndex)?.let { box ->
        canvas.drawRect(
            box.leftPx - scrollX, box.topPx - scrollY,
            box.rightPx - scrollX, box.bottomPx - scrollY, chrome.pageDrop,
        )
    }
}

/** Tint and outline every selected page, so the pending bulk delete's targets are obvious. */
internal fun DrawingSurfaceView.drawPageSelection(canvas: Canvas) {
    for (index in overview.selected) {
        val box = layout.boxes.getOrNull(index) ?: continue
        val l = box.leftPx - scrollX
        val t = box.topPx - scrollY
        val r = box.rightPx - scrollX
        val b = box.bottomPx - scrollY
        canvas.drawRect(l, t, r, b, chrome.pageSelectFill)
        canvas.drawRect(l, t, r, b, chrome.pageSelect)
    }
}

/**
 * The eraser tip outline (view px): a thin circle at exactly the radius [PageEraser] will clear,
 * so the boundary is visible. Pure chrome — it is never part of the document or the saved file.
 */
internal fun DrawingSurfaceView.drawEraserTip(canvas: Canvas, vx: Float, vy: Float) {
    val box = layout.pageAt(vx + scrollX, vy + scrollY) ?: layout.boxes.getOrNull(currentPage)
    val scale = box?.scale ?: 1f
    canvas.drawCircle(vx, vy, (eraserRadiusPt * scale).toFloat(), chrome.eraserOutline)
}

internal fun DrawingSurfaceView.drawHover(canvas: Canvas) {
    val r = (baseWidthPt * 3f).coerceIn(6f, 28f)
    chrome.tintHover(colorArgb)
    canvas.drawCircle(hoverX, hoverY, r, chrome.hover)
}

/** Draw the vertical-space grab line across the page being reflowed (view px). */
internal fun DrawingSurfaceView.drawVerticalSpaceGuide(canvas: Canvas) {
    val box = layout.boxes.getOrNull(vspace.page) ?: return
    val left = box.leftPx - scrollX
    val right = left + box.widthPx
    canvas.drawRect(left, vspace.lineViewY - 1f, right, vspace.lineViewY + 1f, chrome.selectionStroke)
}

/** Draw the live marquee (view px): a rectangle, or the traced lasso path in lasso mode. */
internal fun DrawingSurfaceView.drawBand(canvas: Canvas) {
    val pts = gestures.lassoPts
    if (lassoMode && pts.size >= 4) {
        chrome.lassoPath.reset()
        chrome.lassoPath.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { chrome.lassoPath.lineTo(pts[i], pts[i + 1]); i += 2 }
        chrome.lassoPath.close()
        canvas.drawPath(chrome.lassoPath, chrome.bandFill)
        canvas.drawPath(chrome.lassoPath, chrome.selectionStroke)
        return
    }
    val l = min(gestures.bandX0, gestures.bandX1)
    val t = min(gestures.bandY0, gestures.bandY1)
    val r = max(gestures.bandX0, gestures.bandX1)
    val bot = max(gestures.bandY0, gestures.bandY1)
    canvas.drawRect(l, t, r, bot, chrome.bandFill)
    canvas.drawRect(l, t, r, bot, chrome.selectionStroke)
}

/**
 * True when the pointer in hand rubs out: the Eraser tool, a pen flipped onto its eraser tip, or
 * a hovering stylus with the barrel held while the button is bound to [BarrelAction.ERASE] — the
 * same rule [InputClassifier] applies at pointer-down, so the preview matches what a touch does.
 */
internal fun DrawingSurfaceView.erasesNow(): Boolean =
    InputClassifier.classify(hoverKind, barrelWasDown, activeTool(), inputSettings) ==
        GestureIntent.ERASE
