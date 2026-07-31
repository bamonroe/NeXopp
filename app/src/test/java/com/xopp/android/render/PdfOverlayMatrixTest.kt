package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rotation-aware overlay mapping: a `.xopp` point authored in a page's *visual* space must land in
 * the source PDF's *unrotated* content space so the viewer's `/Rotate` cancels back to the drawn spot.
 * Each case maps the four visual corners (composing [PdfPageTransform]'s y-flip with the matrix, exactly
 * as [PdfExporter] does) and asserts the resulting unrotated PDF corner.
 */
class PdfOverlayMatrixTest {

    // A landscape-in-content 200×100 crop box at the origin; visual dims depend on /Rotate.
    private val cropW = 200f
    private val cropH = 100f

    /** Map a visual-space top-left point through the exporter's transform + matrix, as PdfExporter does. */
    private fun place(m: PdfOverlayMatrix, mx: Double, my: Double): Pair<Float, Float> {
        val t = PdfPageTransform(0f, 0f, m.visualHeight)
        val x = t.x(mx)
        val y = t.y(my)
        return (m.a * x + m.c * y + m.e) to (m.b * x + m.d * y + m.f)
    }

    private fun assertPoint(expX: Float, expY: Float, actual: Pair<Float, Float>) {
        assertEquals(expX, actual.first, 1e-3f)
        assertEquals(expY, actual.second, 1e-3f)
    }

    @Test fun unrotatedIsCropOriginShiftAndYFlip() {
        val m = PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 0)
        assertEquals(cropH, m.visualHeight, 1e-4f)              // visual page is portrait 200×100
        assertPoint(0f, cropH, place(m, 0.0, 0.0))             // visual top-left → unrotated top-left
        assertPoint(cropW, 0f, place(m, 200.0, 100.0))         // visual bottom-right → unrotated bottom-right
    }

    @Test fun rotate90MapsVisualLandscapeToUnrotatedPortrait() {
        val m = PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 90)
        assertEquals(cropW, m.visualHeight, 1e-4f)             // visual page is landscape 100×200
        assertPoint(0f, 0f, place(m, 0.0, 0.0))                // visual top-left → unrotated bottom-left
        assertPoint(0f, cropH, place(m, 100.0, 0.0))           // visual top-right → unrotated top-left
        assertPoint(cropW, 0f, place(m, 0.0, 200.0))           // visual bottom-left → unrotated bottom-right
        assertPoint(cropW, cropH, place(m, 100.0, 200.0))      // visual bottom-right → unrotated top-right
    }

    @Test fun rotate180FlipsBothAxes() {
        val m = PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 180)
        assertEquals(cropH, m.visualHeight, 1e-4f)
        assertPoint(cropW, 0f, place(m, 0.0, 0.0))             // visual top-left → unrotated bottom-right
        assertPoint(0f, cropH, place(m, 200.0, 100.0))         // visual bottom-right → unrotated top-left
    }

    @Test fun rotate270MapsVisualLandscapeToUnrotatedPortrait() {
        val m = PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 270)
        assertEquals(cropW, m.visualHeight, 1e-4f)
        assertPoint(cropW, cropH, place(m, 0.0, 0.0))          // visual top-left → unrotated top-right
        assertPoint(0f, 0f, place(m, 100.0, 200.0))            // visual bottom-right → unrotated bottom-left
    }

    @Test fun cropOriginOffsetsEveryMappedPoint() {
        val m = PdfOverlayMatrix.forPage(10f, 20f, cropW, cropH, 0)
        assertPoint(10f, 20f + cropH, place(m, 0.0, 0.0))      // origin shifts the whole overlay
    }

    @Test fun rotationIsNormalisedModulo360() {
        val plus450 = PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 450)   // ≡ 90
        val minus90 = PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, -90)   // ≡ 270
        assertEquals(PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 90), plus450)
        assertEquals(PdfOverlayMatrix.forPage(0f, 0f, cropW, cropH, 270), minus90)
    }
}
