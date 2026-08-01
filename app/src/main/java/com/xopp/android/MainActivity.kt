package com.xopp.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.xopp.android.format.Xopp
import com.xopp.android.format.model.Background
import com.xopp.android.render.ATTACH_DOMAIN
import com.xopp.android.render.ATTACH_PDF_FILENAME
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.PdfImport
import com.xopp.android.render.documentWithPdfDomain
import com.xopp.android.render.PdfPageCache
import com.xopp.android.render.PdfTextExtractor
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

    /** The file name chosen in the Save As dialog, kept until the folder picker (attach) returns. */
    private var pendingSaveName: String = "document.xopp"

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

    // "Save As … / Attach" writes two files (the .xopp plus its bundled PDF) into one folder, so it
    // needs a tree grant the single-file CreateDocument picker can't give (no sibling access).
    private val saveAttachLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            treeUri?.let(::saveAttached)
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
        // PDFBox needs its font/resource loader primed once before any PDF export can run.
        PDFBoxResourceLoader.init(applicationContext)
        val store = SettingsStore(this)
        setContent {
            XoppTheme {
                var settings by remember { mutableStateOf(store.load()) }
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = { saveLauncher.launch("document.xopp") },
                    onSaveAs = ::beginSaveAs,
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
        // Reload the PDF a `pdf` background references, so a saved project reopens with its
        // background intact. Only the first PDF-backed page carries the reference (import convention).
        val pdfRef = doc.pages.firstNotNullOfOrNull { (it.background as? Background.Pdf)?.filename }
        val pdfFile = pdfRef?.let(::resolvePdfBackground)
        surface?.setPdfSource(pdfFile?.let(::PdfPageCache))
        surface?.setPdfTextIndex(null)
        surface?.load(doc)
        if (pdfFile != null) extractPdfTextInBackground(pdfFile)
        else if (pdfRef != null) toast("Background PDF not found; those pages will be blank")
    }.onFailure { toast("Open failed: ${it.message}") }

    /**
     * Resolve a `pdf` background reference back to a local file we can rasterise. The reference is
     * either a `content://` URI (what Android records for `domain="absolute"` — a picked PDF has no
     * filesystem path) or an on-disk path (what desktop Xournal++ records). Copies the bytes into the
     * cache; returns null when the source can't be reached (e.g. a Linux path on Android), so the
     * caller falls back to blank pages.
     */
    private fun resolvePdfBackground(ref: String): File? = runCatching {
        val stream = when {
            ref.startsWith("content://") -> contentResolver.openInputStream(Uri.parse(ref))
            else -> File(ref).takeIf(File::exists)?.inputStream()
        } ?: return@runCatching null
        val out = File(cacheDir, "background.pdf")
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrNull()

    /** Import a PDF as a fresh annotatable document: one page per PDF page, rendered as backgrounds. */
    private fun importPdf(uri: Uri) = runCatching {
        // Persist read access so the same content URI still resolves when the saved .xopp is reopened
        // later (this is the reference plain Save records for domain="absolute"). Best-effort: import
        // still works if the grant isn't persistable.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val file = File(cacheDir, "imported.pdf")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open $uri" }
            file.outputStream().use { input.copyTo(it) }
        }
        val cache = PdfPageCache(file)
        val doc = PdfImport.documentFor(cache, uri.toString())
        surface?.setPdfSource(cache)
        surface?.setPdfTextIndex(null) // cleared until extraction below finishes
        surface?.load(doc)
        extractPdfTextInBackground(file)
    }.onFailure { toast("PDF import failed: ${it.message}") }

    /** Extract a PDF's text layer off the UI thread (slow on big PDFs), then attach it for text-select. */
    private fun extractPdfTextInBackground(file: File) {
        Thread {
            val index = PdfTextExtractor().extract(file)
            surface?.post { surface?.setPdfTextIndex(index) }
        }.start()
    }

    /** Flatten the current document to a PDF at the chosen location (backgrounds + annotations). */
    private fun exportPdf(uri: Uri) = runCatching {
        contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "could not write $uri" }
            surface?.exportPdf(output)
        }
    }.onFailure { toast("PDF export failed: ${it.message}") }

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

    /**
     * Route a Save As choice to the right SAF flow. `absolute` (and any plain document) saves one
     * file through the normal picker; `attach` bundles the PDF, so it needs a folder to drop both
     * the `.xopp` and its sibling PDF into. Attach with no PDF background degrades to a plain save.
     */
    private fun beginSaveAs(filename: String, domain: String) {
        pendingSaveName = filename
        if (domain == ATTACH_DOMAIN && surface?.pdfSourceFile() != null) {
            saveAttachLauncher.launch(null)
        } else {
            saveLauncher.launch(filename)
        }
    }

    /**
     * Save a self-contained copy into the chosen folder: the `.xopp` (with `domain="attach"`) plus a
     * copy of its background PDF named `<xoppname>.bg.pdf`, so desktop Xournal++ resolves the pair as
     * `<xoppPath> + "." + "bg.pdf"` (see `docs/architecture.md`). The sibling name is derived from the
     * `.xopp`'s actual on-disk name in case the provider deduplicated it.
     */
    private fun saveAttached(treeUri: Uri) = runCatching {
        val view = surface ?: return@runCatching
        val pdfFile = view.pdfSourceFile() ?: error("no PDF background to attach")
        val doc = documentWithPdfDomain(view.toDocument(), ATTACH_DOMAIN)
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        val xoppUri = DocumentsContract.createDocument(contentResolver, dirUri, XOPP_MIME, pendingSaveName)
            ?: error("could not create ${pendingSaveName}")
        contentResolver.openOutputStream(xoppUri, "w").use { output ->
            requireNotNull(output) { "could not write ${pendingSaveName}" }
            Xopp.save(doc, output)
        }

        val siblingName = "${displayName(xoppUri) ?: pendingSaveName}.$ATTACH_PDF_FILENAME"
        val pdfUri = DocumentsContract.createDocument(contentResolver, dirUri, PDF_MIME, siblingName)
            ?: error("could not create $siblingName")
        contentResolver.openOutputStream(pdfUri, "w").use { output ->
            requireNotNull(output) { "could not write $siblingName" }
            pdfFile.inputStream().use { it.copyTo(output) }
        }
        toast("Saved ${pendingSaveName} with attached PDF")
    }.onFailure { toast("Save failed: ${it.message}") }

    /** The on-disk display name a SAF document ended up with (the provider may have deduplicated it). */
    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

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
