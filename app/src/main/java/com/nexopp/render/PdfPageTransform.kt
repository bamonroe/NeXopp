package com.nexopp.render

/**
 * Maps a `.xopp` page-local point (origin top-left, y-down, in points) into PDF user space (origin
 * bottom-left, y-up). [originX]/[originY] are the target page's crop-box lower-left (0 for a fresh
 * page); [height] is the page height in points, so `y` flips about it. The `.xopp` unit and the PDF
 * unit are both 1/72", so no scaling is needed — widths and coordinates carry across 1:1.
 */
class PdfPageTransform(
    private val originX: Float,
    private val originY: Float,
    private val height: Float,
) {
    fun x(mx: Double): Float = originX + mx.toFloat()
    fun y(my: Double): Float = originY + (height - my.toFloat())
}
