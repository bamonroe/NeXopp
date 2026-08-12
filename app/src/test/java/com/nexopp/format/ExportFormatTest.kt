package com.nexopp.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatTest {

    @Test fun mimeTypesMatchTheFormat() {
        assertEquals("application/pdf", ExportFormat.PDF.mime)
        assertEquals("image/svg+xml", ExportFormat.SVG.mime)
        assertEquals("image/png", ExportFormat.PNG.mime)
        assertEquals("image/jpeg", ExportFormat.JPEG.mime)
        assertEquals("image/webp", ExportFormat.WEBP.mime)
    }

    @Test fun extensionsMatchTheFormat() {
        assertEquals("pdf", ExportFormat.PDF.extension)
        assertEquals("svg", ExportFormat.SVG.extension)
        assertEquals("png", ExportFormat.PNG.extension)
        assertEquals("jpg", ExportFormat.JPEG.extension)
        assertEquals("webp", ExportFormat.WEBP.extension)
    }

    @Test fun labelsAreTheDialogNames() {
        assertEquals("PDF", ExportFormat.PDF.label)
        assertEquals("SVG", ExportFormat.SVG.label)
        assertEquals("PNG", ExportFormat.PNG.label)
        assertEquals("JPEG", ExportFormat.JPEG.label)
        assertEquals("WebP", ExportFormat.WEBP.label)
    }

    @Test fun onlyPixelFormatsAreRaster() {
        assertFalse(ExportFormat.PDF.isRaster)
        assertFalse(ExportFormat.SVG.isRaster)
        assertTrue(ExportFormat.PNG.isRaster)
        assertTrue(ExportFormat.JPEG.isRaster)
        assertTrue(ExportFormat.WEBP.isRaster)
    }

    @Test fun everythingButPdfIsOneFilePerPage() {
        assertFalse(ExportFormat.PDF.isMultiFile)
        assertTrue(ExportFormat.SVG.isMultiFile)
        assertTrue(ExportFormat.PNG.isMultiFile)
        assertTrue(ExportFormat.JPEG.isMultiFile)
        assertTrue(ExportFormat.WEBP.isMultiFile)
    }

    @Test fun nullPageIndexNamesASingleFile() {
        assertEquals("notes.pdf", ExportFormat.PDF.fileName("notes", null))
        assertEquals("notes.svg", ExportFormat.SVG.fileName("notes", null))
    }

    @Test fun pageIndexIsOneBasedAndZeroPadded() {
        assertEquals("notes-001.png", ExportFormat.PNG.fileName("notes", 0))
        assertEquals("notes-100.jpg", ExportFormat.JPEG.fileName("notes", 99))
    }

    @Test fun paddingGrowsPastAThousandPages() {
        assertEquals("notes-1000.webp", ExportFormat.WEBP.fileName("notes", 999))
    }
}
