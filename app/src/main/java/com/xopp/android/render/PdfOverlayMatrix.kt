package com.xopp.android.render

/**
 * The affine map that places an annotation overlay onto a **rotated** source PDF page.
 *
 * Annotations are authored against the page as the user *sees* it — the on-screen renderer already
 * applies the page's `/Rotate`, so the `.xopp` coordinates live in the page's **visual** space (the
 * landscape view of a `/Rotate 90` page, etc.). A PDF viewer, however, applies `/Rotate` on top of
 * whatever we draw into the page's **unrotated** content space. So to make the overlay land where it
 * was drawn we pre-multiply the content stream by the *inverse* of the display rotation: this matrix
 * maps a point in visual space to the unrotated content space, and the viewer's `/Rotate` then
 * cancels it back to the visual position.
 *
 * The coefficients are PDF matrix form `[a b c d e f]` (`x' = a·x + c·y + e`, `y' = b·x + d·y + f`),
 * ready to hand to a `com.tom_roush.pdfbox.util.Matrix`. Rotation preserves lengths, so stroke widths
 * and font sizes carry over unchanged. [visualHeight] is the overlay's page height in visual space —
 * what [PdfPageTransform] must flip about, since after this matrix the crop origin is already folded
 * into `e`/`f`, so the point transform runs at the origin.
 *
 * Kept free of PDFBox types so the rotation math is unit-tested on the JVM; [PdfExporter] turns the
 * coefficients into a `Matrix`.
 */
data class PdfOverlayMatrix(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
    val visualHeight: Float,
) {
    companion object {
        /**
         * Build the overlay matrix for a source page whose crop box lower-left is ([cropX], [cropY])
         * with unrotated dimensions [cropW]×[cropH] and a `/Rotate` of [rotation] degrees (any
         * multiple of 90; normalised here). For `/Rotate 0` this is a pure crop-origin translation.
         */
        fun forPage(cropX: Float, cropY: Float, cropW: Float, cropH: Float, rotation: Int): PdfOverlayMatrix {
            return when (((rotation % 360) + 360) % 360) {
                // 90° CW display → undo with a 90° CCW content rotation; visual page is landscape (H×W).
                90 -> PdfOverlayMatrix(0f, 1f, -1f, 0f, cropX + cropW, cropY, cropW)
                // 180° display → 180° content rotation about the crop-box centre.
                180 -> PdfOverlayMatrix(-1f, 0f, 0f, -1f, cropX + cropW, cropY + cropH, cropH)
                // 270° CW display → undo with a 90° CW content rotation; visual page is landscape (H×W).
                270 -> PdfOverlayMatrix(0f, -1f, 1f, 0f, cropX, cropY + cropH, cropW)
                // Unrotated: just shift by the crop-box origin.
                else -> PdfOverlayMatrix(1f, 0f, 0f, 1f, cropX, cropY, cropH)
            }
        }
    }
}
