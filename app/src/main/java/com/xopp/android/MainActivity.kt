package com.xopp.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import androidx.core.content.ContextCompat
import com.xopp.android.audio.AudioSession
import com.xopp.android.audio.documentAudioFiles
import com.xopp.android.format.SaveFormat
import com.xopp.android.format.Xopp
import com.xopp.android.format.XoppZip
import com.xopp.android.format.model.Background
import com.xopp.android.render.ATTACH_DOMAIN
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.PdfImport
import com.xopp.android.render.documentWithPdfDomain
import com.xopp.android.render.PdfPageCache
import com.xopp.android.render.PdfTextExtractor
import com.xopp.android.render.Placement
import com.xopp.android.ui.AudioUiState
import com.xopp.android.ui.EditorScreen
import com.xopp.android.ui.SettingsStore
import com.xopp.android.ui.theme.XoppTheme
import com.xopp.android.ui.theme.isDark
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

    /** The file name last chosen in the Save As dialog; reused by plain Save. */
    private var pendingSaveName: String = "document.xopp"

    /**
     * The sticky save format. "Save As" sets it; every later plain Save reuses it, so once you save
     * ZIPPED once, Save keeps writing ZIPPED. Opening a document adopts the format it was stored in.
     */
    private var saveFormat: SaveFormat = SaveFormat.ORIGINAL

    /** Recording, playback and sidecar transfer for audio-annotated strokes (`fn`/`ts`). */
    private val audio: AudioSession by lazy { AudioSession(this) }

    /** Persists [AppSettings]; also the home of the nominated audio folder grant. */
    private val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    /**
     * The folder sidecar `.wav` files are kept in — a persisted `OpenDocumentTree` grant, normally
     * the folder the user's `.xopp` files live in. Null until they nominate one, in which case audio
     * still records and plays but never leaves the app (see [AudioSession]).
     */
    private var audioFolder: Uri? = null

    /** Bumped whenever recording/playback state changes, so the Compose chrome re-reads it. */
    private var audioTick = mutableStateOf(0)

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

    private val audioFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::adoptAudioFolder)
        }

    private val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginRecording() else toast("Recording needs microphone permission")
        }

    private val exportPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
            uri?.let(::exportPdf)
        }

    /**
     * Hardware-keyboard shortcuts for the spline tool, which is the one tool whose gesture spans
     * several taps: Enter commits the open curve, Escape throws it away. Handled here rather than in
     * the surface so the canvas never has to take keyboard focus away from the app's text fields.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = surface
        if (event.action == KeyEvent.ACTION_UP && view != null && view.splineInProgress()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { view.finishSpline(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { view.cancelSpline(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PDFBox needs its font/resource loader primed once before any PDF export can run.
        PDFBoxResourceLoader.init(applicationContext)
        val store = settingsStore
        audioFolder = store.load().audioFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        audio.onStateChanged = { runOnUiThread { audioTick.value++ } }
        setContent {
            // Settings live above the theme so the Appearance choice re-colours the whole app.
            var settings by remember { mutableStateOf(store.load()) }
            XoppTheme(darkTheme = settings.themeMode.isDark()) {
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = { saveLauncher.launch(pendingSaveName) },
                    onSaveAs = ::beginSaveAs,
                    currentSaveFormat = { saveFormat },
                    onImportPdf = { importPdfLauncher.launch(arrayOf(PDF_MIME)) },
                    onExportPdf = { exportPdfLauncher.launch("document.pdf") },
                    onPickImage = { placement ->
                        pendingImagePlacement = placement
                        pickImageLauncher.launch(arrayOf("image/*"))
                    },
                    onSurfaceCreated = { surface = it; attachAudio(it) },
                    settings = settings,
                    onSettingsChange = { settings = it; store.save(it) },
                    audio = audioUiState(),
                )
            }
        }
    }

    private fun openDocument(uri: Uri) = runCatching {
        contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "could not open $uri" }
            val input = raw.buffered()
            // Sniff the container: ZIP-package (PK…) vs the legacy gzip .xopp. This also fixes the
            // sticky save format so a reopened ZIP keeps saving ZIPPED (and gzip keeps saving gzip).
            input.mark(2)
            val zip = XoppZip.isZip(input.read(), input.read())
            input.reset()
            if (zip) openZip(input) else openGzip(input)
        }
        // The document may reference recordings made elsewhere; fetch them so its strokes replay.
        pullAudioSidecars()
    }.onFailure { toast("Open failed: ${it.message}") }

    /** Open a ZIP-package .xopp: the PDF travels inside, so the background rasterises without a sibling. */
    private fun openZip(input: java.io.InputStream) {
        val (doc, pdfFile) = XoppZip.open(input, cacheDir)
        saveFormat = SaveFormat.ZIPPED
        surface?.setPdfSource(pdfFile?.let(::PdfPageCache))
        surface?.setPdfTextIndex(null)
        surface?.load(doc)
        if (pdfFile != null) extractPdfTextInBackground(pdfFile)
    }

    /** Open a legacy gzip .xopp, resolving a PDF background from its linked path/URI (may be absent). */
    private fun openGzip(input: java.io.InputStream) {
        val doc = Xopp.open(input)
        saveFormat = SaveFormat.ORIGINAL
        // Reload the PDF a `pdf` background references, so a saved project reopens with its
        // background intact. Only the first PDF-backed page carries the reference (import convention).
        val pdfRef = doc.pages.firstNotNullOfOrNull { (it.background as? Background.Pdf)?.filename }
        val pdfFile = pdfRef?.let(::resolvePdfBackground)
        surface?.setPdfSource(pdfFile?.let(::PdfPageCache))
        surface?.setPdfTextIndex(null)
        surface?.load(doc)
        if (pdfFile != null) extractPdfTextInBackground(pdfFile)
        else if (pdfRef != null) toast("Background PDF not found; those pages will be blank")
    }

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

    /**
     * Write the current document to [uri] in the sticky [saveFormat]. Both formats are single files,
     * so both go through the plain CreateDocument picker:
     * - [SaveFormat.ORIGINAL] — gzip XML, PDF background left linked by path/URI (interchange-safe).
     * - [SaveFormat.ZIPPED] — a ZIP package with the PDF embedded (`domain="attach"`, `bg.pdf`).
     */
    private fun saveDocument(uri: Uri) = runCatching {
        val view = surface ?: return@runCatching
        contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "could not write $uri" }
            when (saveFormat) {
                SaveFormat.ORIGINAL -> Xopp.save(view.toDocument(), output)
                SaveFormat.ZIPPED -> {
                    val pdf = view.pdfSourceFile()
                    val doc = if (pdf != null) documentWithPdfDomain(view.toDocument(), ATTACH_DOMAIN)
                    else view.toDocument()
                    XoppZip.save(doc, pdf, output)
                }
            }
        }
        // A .xopp never carries its audio — the sidecars have to land beside it or playback breaks.
        pushAudioSidecars()
        if (audioFolder == null && audio.missingFor(view.toDocument()).isEmpty() &&
            documentHasAudio(view)
        ) {
            toast("Choose an audio folder so recordings are saved beside this file")
        }
    }.onFailure { toast("Save failed: ${it.message}") }

    /** True when the open document references any recording at all. */
    private fun documentHasAudio(view: DrawingSurfaceView): Boolean =
        documentAudioFiles(view.toDocument()).isNotEmpty()

    /**
     * Apply a Save As choice: make [format] the sticky format (so later plain Saves reuse it),
     * remember the file name, and open the single-file picker. Both formats write one file.
     */
    private fun beginSaveAs(filename: String, format: SaveFormat) {
        saveFormat = format
        pendingSaveName = filename
        saveLauncher.launch(filename)
    }

    // --- audio: record onto strokes, replay from them, keep sidecars beside the .xopp -----------

    /** Wire the canvas to the audio session: stamp new strokes while recording, replay on a tap. */
    private fun attachAudio(view: DrawingSurfaceView) {
        view.audioStamp = { audio.stamp() }
        view.onAudioTap = { ref ->
            when {
                ref == null -> toast("That stroke has no recording")
                !audio.play(ref) -> toast("Recording not found: ${ref.filename}")
                else -> Unit
            }
        }
    }

    /** The audio slot's current state, read fresh on every recomposition [audioTick] triggers. */
    private fun audioUiState(): AudioUiState {
        audioTick.value // read so Compose re-invokes this when the session changes
        return AudioUiState(
            recording = audio.isRecording,
            playing = audio.isPlaying,
            folderChosen = audioFolder != null,
            onToggleRecord = ::toggleRecording,
            onStopPlayback = { audio.stopPlayback() },
            onChooseFolder = { audioFolderLauncher.launch(null) },
        )
    }

    /** Stop a running recording, or ask for the microphone and start one. */
    private fun toggleRecording() {
        if (audio.isRecording) {
            val file = audio.stopRecording()
            toast(if (file != null) "Recording saved: ${file.name}" else "Nothing was recording")
            pushAudioSidecars()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording() else recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginRecording() {
        val name = audio.startRecording()
        toast(if (name != null) "Recording — strokes will replay from here" else "Could not open the microphone")
    }

    /**
     * Adopt a newly picked audio folder, holding onto the grant across restarts so sidecars keep
     * resolving, and immediately pull in whatever the open document references.
     */
    private fun adoptAudioFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        audioFolder = uri
        settingsStore.save(settingsStore.load().copy(audioFolderUri = uri.toString()))
        audioTick.value++
        pullAudioSidecars()
    }

    /** Copy the sidecars the open document references out of the nominated folder, so they replay. */
    private fun pullAudioSidecars() {
        val folder = audioFolder ?: return
        val doc = surface?.toDocument() ?: return
        val pulled = audio.importSidecars(folder, doc)
        val missing = audio.missingFor(doc).size
        if (pulled > 0) toast("Loaded $pulled recording(s)")
        else if (missing > 0) toast("$missing recording(s) referenced but not in the audio folder")
    }

    /** Copy the sidecars the open document references into the nominated folder, beside the .xopp. */
    private fun pushAudioSidecars() {
        val folder = audioFolder ?: return
        val doc = surface?.toDocument() ?: return
        audio.exportSidecars(folder, doc)
    }

    override fun onDestroy() {
        audio.release()
        super.onDestroy()
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
