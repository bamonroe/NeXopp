package com.xopp.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.xopp.android.format.Xopp
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.PdfImport
import com.xopp.android.render.PdfPageCache
import com.xopp.android.ui.EditorScreen
import com.xopp.android.ui.theme.XoppTheme
import java.io.File

/**
 * Hosts the editor and bridges the Storage Access Framework to the `.xopp` I/O layer: open a
 * document in place, edit on the [DrawingSurfaceView], save back to the same format. The file on
 * disk is the only source of truth (see `CLAUDE.md` non-goals).
 */
class MainActivity : ComponentActivity() {

    private var surface: DrawingSurfaceView? = null

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::openDocument)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XOPP_MIME)) { uri ->
            uri?.let(::saveDocument)
        }

    private val importPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importPdf)
        }

    private val exportPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
            uri?.let(::exportPdf)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XoppTheme {
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = { saveLauncher.launch("document.xopp") },
                    onImportPdf = { importPdfLauncher.launch(arrayOf(PDF_MIME)) },
                    onExportPdf = { exportPdfLauncher.launch("document.pdf") },
                    onSurfaceCreated = { surface = it },
                )
            }
        }
    }

    private fun openDocument(uri: Uri) = runCatching {
        val doc = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open $uri" }
            Xopp.open(input)
        }
        surface?.setPdfSource(null) // a plain .xopp brings no PDF of its own
        surface?.load(doc)
    }.onFailure { toast("Open failed: ${it.message}") }

    /** Import a PDF as a fresh annotatable document: one page per PDF page, rendered as backgrounds. */
    private fun importPdf(uri: Uri) = runCatching {
        val file = File(cacheDir, "imported.pdf")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open $uri" }
            file.outputStream().use { input.copyTo(it) }
        }
        val cache = PdfPageCache(file)
        val doc = PdfImport.documentFor(cache, displayName(uri) ?: file.name)
        surface?.setPdfSource(cache)
        surface?.load(doc)
    }.onFailure { toast("PDF import failed: ${it.message}") }

    /** Flatten the current document to a PDF at the chosen location (backgrounds + annotations). */
    private fun exportPdf(uri: Uri) = runCatching {
        contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "could not write $uri" }
            surface?.exportPdf(output)
        }
    }.onFailure { toast("PDF export failed: ${it.message}") }

    /** The user-visible file name behind a SAF [uri], for the `pdf` background's `filename`. */
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun saveDocument(uri: Uri) = runCatching {
        val doc = surface?.toDocument() ?: return@runCatching
        contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "could not write $uri" }
            Xopp.save(doc, output)
        }
    }.onFailure { toast("Save failed: ${it.message}") }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val XOPP_MIME = "application/gzip"
        const val PDF_MIME = "application/pdf"
    }
}
