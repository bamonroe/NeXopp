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
 * re-clamp against the *new* extent afterwards. A [setBounds] that changes the *view size* (rotation,
 * multi-window, split view) re-anchors the document point at the viewport centre rather than clamping.
 *
 * That re-anchoring has to survive a viewport it doesn't fit in. Narrowing a pane for split view
 * shrinks the fit-to-width layout until the whole document is shorter than the viewport, so the
 * restored offset is clamped away and the reader's place is gone before the pane widens again. The
 * wanted centre is therefore kept as a pending [anchor] whenever a restore is clamped, and re-applied
 * on the next [setBounds] — so an on→off round trip lands exactly where it started. Any real scroll
 * (a pan, a page jump, a zoom) drops the anchor, since the reader has chosen a new place to be.
 */
internal class ViewportState {

    private var _scrollX = 0f
    private var _scrollY = 0f

    /** Horizontal scroll offset in pixels (clamped to 0..maxScrollX); writing drops a pending [anchor]. */
    var scrollX: Float
        get() = _scrollX
        set(value) {
            _scrollX = value
            anchor = null
        }

    /** Vertical scroll offset in pixels (clamped to 0..maxScrollY); writing drops a pending [anchor]. */
    var scrollY: Float
        get() = _scrollY
        set(value) {
            _scrollY = value
            anchor = null
        }

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

    /** The centre the viewport still owes the reader, kept while a restore of it is being clamped. */
    private var anchor: Centre? = null

    /** A wanted viewport centre as zoom-invariant fractions of the content extent. */
    private data class Centre(val xFrac: Float, val yFrac: Float)

    /**
     * Adopt the current view size and content extent, then clamp the offsets into the new range.
     *
     * When the *view size* changes (rotation, multi-window, a split-view toggle) the document point
     * at the centre of the viewport is re-anchored instead, so the reader stays where they were.
     * A content-only change (same view size) just clamps — unless an earlier restore was clamped
     * away, in which case the centre it wanted is retried against the fresh extent.
     * @param viewWidth View width in pixels.
     * @param viewHeight View height in pixels.
     * @param contentWidthPx Content width in pixels (after zoom).
     * @param contentHeightPx Content height in pixels (after zoom).
     */
    fun setBounds(viewWidth: Float, viewHeight: Float, contentWidthPx: Float, contentHeightPx: Float) {
        val resized = (viewWidth != this.viewWidth || viewHeight != this.viewHeight) &&
            this.viewWidth > 0f && this.viewHeight > 0f &&
            this.contentWidthPx > 0f && this.contentHeightPx > 0f
        // A pending anchor is the older, un-clamped intent, so it outranks the centre of a viewport
        // that has already lost the position. Zoom-invariant fractions, exactly as zoomAbout uses.
        val target = anchor ?: if (resized) {
            Centre(
                (scrollX + this.viewWidth / 2f) / this.contentWidthPx,
                (scrollY + this.viewHeight / 2f) / this.contentHeightPx,
            )
        } else {
            null
        }
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.contentWidthPx = contentWidthPx
        this.contentHeightPx = contentHeightPx
        if (target != null) restoreCentre(target) else clamp()
    }

    /**
     * Put the document point at [target] back under the viewport centre, keeping it pending when the
     * viewport is too small to hold it — the next [setBounds] with room for it will land it properly.
     */
    private fun restoreCentre(target: Centre) {
        val wantX = target.xFrac * contentWidthPx - viewWidth / 2f
        val wantY = target.yFrac * contentHeightPx - viewHeight / 2f
        _scrollX = wantX.coerceIn(0f, maxScrollX())
        _scrollY = wantY.coerceIn(0f, maxScrollY())
        anchor = if (_scrollX != wantX || _scrollY != wantY) target else null
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
