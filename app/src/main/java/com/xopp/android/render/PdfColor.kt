package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.xopp.android.format.XoppColor.alpha
import com.xopp.android.format.XoppColor.red
import com.xopp.android.format.XoppColor.green
import com.xopp.android.format.XoppColor.blue

/**
 * ARGB colour helpers for [PDPageContentStream]. Converts Android's `0xAARRGGBB` format to
 * PDFBox's separate RGB parameters.
 */

/** Set the stroking colour from an ARGB int (`0xAARRGGBB`). */
fun PDPageContentStream.setStrokingArgb(argb: Int) {
    setStrokingColor(argb.red, argb.green, argb.blue)
}

/** Set the non-stroking (fill) colour from an ARGB int (`0xAARRGGBB`). */
fun PDPageContentStream.setNonStrokingArgb(argb: Int) {
    setNonStrokingColor(argb.red, argb.green, argb.blue)
}
