package com.xopp.android.render

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

    var scrollX = 0f
    var scrollY = 0f
    var zoom = 1f
        private set

    private var viewWidth = 0f
    private var viewHeight = 0f
    private var contentWidthPx = 0f
    private var contentHeightPx = 0f

    /** Adopt the current view size and content extent, then clamp the offsets into the new range. */
    fun setBounds(viewWidth: Float, viewHeight: Float, contentWidthPx: Float, contentHeightPx: Float) {
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.contentWidthPx = contentWidthPx
        this.contentHeightPx = contentHeightPx
        clamp()
    }

    fun maxScrollX(): Float = (contentWidthPx - viewWidth).coerceAtLeast(0f)
    fun maxScrollY(): Float = (contentHeightPx - viewHeight).coerceAtLeast(0f)

    /** True when there is anywhere left to scroll in either axis. */
    fun canScroll(): Boolean = maxScrollX() > 0f || maxScrollY() > 0f

    /** Pull both offsets back inside the scrollable range. */
    fun clamp() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = scrollY.coerceIn(0f, maxScrollY())
    }

    /** Set the vertical offset (clamped). */
    fun scrollToY(y: Float) {
        scrollY = y.coerceIn(0f, maxScrollY())
    }

    /** Scroll by ([dx], [dy]) px, clamped; false when nothing moved (pinned at a bound). */
    fun scrollBy(dx: Float, dy: Float): Boolean {
        val prevX = scrollX
        val prevY = scrollY
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY())
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX())
        return scrollX != prevX || scrollY != prevY
    }

    /**
     * Zoom to [target] (clamped), keeping the viewport centre roughly fixed. [relayout] must
     * re-stack the pages at the new zoom and push the fresh extent back in via [setBounds].
     * False when the clamp bit and nothing changed.
     */
    fun zoomTo(target: Float, relayout: () -> Unit): Boolean =
        zoomAbout(viewWidth / 2f, viewHeight / 2f, target.coerceIn(MIN_ZOOM, MAX_ZOOM) / zoom, relayout)

    /**
     * Multiply the zoom by [factor] (clamped) while keeping the content point under the viewport
     * pixel ([focusVx], [focusVy]) fixed — the anchor for pinch-zoom. False when the clamp bit.
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

    /** Reset to the top-left at 100%, for adopting a fresh document. */
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
