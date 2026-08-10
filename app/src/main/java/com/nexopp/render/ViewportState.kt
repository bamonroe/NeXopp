package com.nexopp.render

/**
 * The viewport: where the document is scrolled to ([scrollX], [scrollY]) and how far it's zoomed.
 *
 * This is the one owner of the scroll/zoom numbers and of the clamps that keep them inside the
 * content — [DrawingSurfaceView] reads through it rather than keeping its own copies, so "how far
 * can we scroll" and "what does a zoom do to the scroll offset" have a single, unit-testable home.
 *
 * The class is pure: it knows the view size and the content extent only because the host pushes
 * them in with [setBounds] after every relayout. Zoom changes need a relayout in the middle (the
 * stacked layout's px scale with the zoom), so [zoomTo] and [zoomAbout] take that as a callback and
 * re-clamp against the *new* extent afterwards.
 */
internal class ViewportState {

    /** Horizontal scroll offset in pixels (clamped to 0..maxScrollX). */
    var scrollX = 0f
    /** Vertical scroll offset in pixels (clamped to 0..maxScrollY). */
    var scrollY = 0f
    /** Zoom scale factor (clamped to [MIN_ZOOM]..[MAX_ZOOM]); 1.0 = 100%. */
    var zoom = 1f
        private set

    /** Current view width in pixels; set via [setBounds]. */
    private var viewWidth = 0f
    /** Current view height in pixels; set via [setBounds]. */
    private var viewHeight = 0f
    /** Current content width in pixels after zoom; set via [setBounds]. */
    private var contentWidthPx = 0f
    /** Current content height in pixels after zoom; set via [setBounds]. */
    private var contentHeightPx = 0f

    /**
     * Adopt the current view size and content extent, then clamp the offsets into the new range.
     * @param viewWidth View width in pixels.
     * @param viewHeight View height in pixels.
     * @param contentWidthPx Content width in pixels (after zoom).
     * @param contentHeightPx Content height in pixels (after zoom).
     */
    fun setBounds(viewWidth: Float, viewHeight: Float, contentWidthPx: Float, contentHeightPx: Float) {
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.contentWidthPx = contentWidthPx
        this.contentHeightPx = contentHeightPx
        clamp()
    }

    /** Maximum horizontal scroll in pixels (contentWidthPx - viewWidth, minimum 0). */
    fun maxScrollX(): Float = (contentWidthPx - viewWidth).coerceAtLeast(0f)
    /** Maximum vertical scroll in pixels (contentHeightPx - viewHeight, minimum 0). */
    fun maxScrollY(): Float = (contentHeightPx - viewHeight).coerceAtLeast(0f)

    /** True when there is anywhere left to scroll in either axis. */
    fun canScroll(): Boolean = maxScrollX() > 0f || maxScrollY() > 0f

    /** Pull both offsets back inside the scrollable range (0..maxScroll). */
    fun clamp() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = scrollY.coerceIn(0f, maxScrollY())
    }

    /**
     * Set the vertical offset (clamped to 0..maxScrollY).
     * @param y Target scroll position in pixels.
     */
    fun scrollToY(y: Float) {
        scrollY = y.coerceIn(0f, maxScrollY())
    }

    /**
     * Scroll by ([dx], [dy]) pixels, clamped; false when nothing moved (pinned at a bound).
     * @param dx Horizontal delta in pixels.
     * @param dy Vertical delta in pixels.
     * @return true if scroll position changed.
     */
    fun scrollBy(dx: Float, dy: Float): Boolean {
        val prevX = scrollX
        val prevY = scrollY
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX())
        return scrollX != prevX || scrollY != prevY
    }

    /**
     * Zoom to [target] (clamped to [MIN_ZOOM]..[MAX_ZOOM]), keeping the viewport centre roughly fixed.
     * [relayout] must re-stack the pages at the new zoom and push the fresh extent back in via [setBounds].
     * @param target Target zoom scale (1.0 = 100%).
     * @param relayout Callback to recompute layout at the new zoom.
     * @return false if zoom didn't change (already at clamp boundary).
     */
    fun zoomTo(target: Float, relayout: () -> Unit): Boolean =
        zoomAbout(viewWidth / 2f, viewHeight / 2f, target.coerceIn(MIN_ZOOM, MAX_ZOOM) / zoom, relayout)

    /**
     * Multiply the zoom by [factor] (clamped to [MIN_ZOOM]..[MAX_ZOOM]) while keeping the content point
     * under the viewport pixel ([focusVx], [focusVy]) fixed — the anchor for pinch-zoom.
     * @param focusVx Focus X in viewport pixels (e.g. pinch centroid).
     * @param focusVy Focus Y in viewport pixels.
     * @param factor Zoom multiplier (>1 zooms in, <1 zooms out).
     * @param relayout Callback to recompute layout at the new zoom.
     * @return false if zoom didn't change (already at clamp boundary).
     */
    fun zoomAbout(focusVx: Float, focusVy: Float, factor: Float, relayout: () -> Unit): Boolean {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return false
        // Work in zoom-invariant fractions so the anchor survives the relayout (layout px scale with zoom).
        val xFrac = if (contentWidthPx > 0f) (scrollX + focusVx) / contentWidthPx else 0f
        val yFrac = if (contentHeightPx > 0f) (scrollY + focusVy) / contentHeightPx else 0f
        zoom = next
        relayout()
        scrollX = (xFrac * contentWidthPx - focusVx).coerceIn(0f, maxScrollX())
        scrollY = (yFrac * contentHeightPx - focusVy).coerceIn(0f, maxScrollY())
        return true
    }

    /** Reset to the top-left at 100% zoom, for adopting a fresh document. */
    fun reset() {
        scrollX = 0f
        scrollY = 0f
        zoom = 1f
    }

    companion object {
        const val ZOOM_STEP = 1.25f
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 10f
    }
}
