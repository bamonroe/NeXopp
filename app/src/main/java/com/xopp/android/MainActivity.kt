package com.xopp.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xopp.android.format.Xopp
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.PdfImport
import com.xopp.android.render.PdfPageCache
import com.xopp.android.render.Placement
import com.xopp.android.ui.EditorScreen
import com.xopp.android.ui.SettingsStore
import com.xopp.android.ui.theme.XoppTheme
import java.io.File

/**
 * Hosts the editor and bridges the Storage Access Framework to the `.xopp` I/O layer: open a
 * document in place, edit on the [DrawingSurfaceView], save back to the same format. The file on
 * disk is the only source of truth (see `CLAUDE.md` non-goals).
 */
class MainActivity : ComponentActivity() {

    private var surface: DrawingSurfaceView? = null

    /** Where a pending image-insert tap landed, kept until the SAF picker returns the image bytes. */
    private var pendingImagePlacement: Placement? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::insertPickedImage)
        }

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
        val store = SettingsStore(this)
        setContent {
            XoppTheme {
                var settings by remember { mutableStateOf(store.load()) }
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = { saveLauncher.launch("document.xopp") },
                    onImportPdf = { importPdfLauncher.launch(arrayOf(PDF_MIME)) },
                    onExportPdf = { exportPdfLauncher.launch("document.pdf") },
                    onPickImage = { placement ->
                        pendingImagePlacement = placement
                        pickImageLauncher.launch(arrayOf("image/*"))
                    },
                    onSurfaceCreated = { surface = it },
                    settings = settings,
                    onSettingsChange = { settings = it; store.save(it) },
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

    /** Read the picked image's bytes and place it at the tap that started the pick. */
    private fun insertPickedImage(uri: Uri) = runCatching {
        val placement = pendingImagePlacement ?: return@runCatching
        pendingImagePlacement = null
        val bytes = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open $uri" }
            input.readBytes()
        }
        surface?.insertImage(placement, bytes)
    }.onFailure { toast("Image insert failed: ${it.message}") }

    private fun saveDocument(uri: Uri) = runCatching {
        val doc = surface?.toDocument() ?: return@runCatching
        contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "could not write $uri" }
            Xopp.save(doc, output)
        }
    }.onFailure { toast("Save failed: ${it.message}") }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        // Save with a MIME the framework has no canonical extension for, so the Storage Access
        // Framework keeps the exact "document.xopp" name we ask for. Using "application/gzip" here
        // made SAF append its own ".gz" extension (document.xopp.gz), which desktop Xournal++ won't
        // open by name; the bytes are gzip either way (Xopp.save always gzips), only the on-disk name
        // is at stake.
        const val XOPP_MIME = "application/octet-stream"
        const val PDF_MIME = "application/pdf"
    }
}
