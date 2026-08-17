package com.nexopp

import android.content.Context
import android.content.Intent
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
import com.nexopp.audio.AudioSession
import com.nexopp.format.DEFAULT_EXPORT_DPI
import com.nexopp.format.ExportFormat
import com.nexopp.format.PageRange
import com.nexopp.format.SaveFormat
import com.nexopp.io.AutoSavePolicy
import com.nexopp.io.AutoSaveTimer
import com.nexopp.io.DocumentIo
import com.nexopp.io.IncomingDocument
import com.nexopp.io.UriStaging
import com.nexopp.panes.EditorPane
import com.nexopp.panes.MirrorSync
import com.nexopp.render.BitmapBudget
import com.nexopp.render.DrawingSurfaceView
import com.nexopp.render.ImportPdfMode
import com.nexopp.render.PdfFonts
import com.nexopp.render.Placement
import com.nexopp.render.TextPdfGenerator
import com.nexopp.render.cancelSpline
import com.nexopp.render.finishSpline
import com.nexopp.render.splineInProgress
import com.nexopp.render.undoLastSplineNode
import com.nexopp.tabs.TabManager
import com.nexopp.tabs.TabStore
import com.nexopp.ui.AppSettings
import com.nexopp.ui.ContentNotice
import com.nexopp.ui.EditorScreen
import com.nexopp.ui.SettingsStore
import com.nexopp.ui.theme.XoppTheme
import com.nexopp.ui.theme.isDark
import java.io.File

/**
 * Hosts the editor and bridges the Storage Access Framework to the `.xopp` I/O layer: open a
 * document in place, edit on the [DrawingSurfaceView], save back to the same format. The file on
 * disk is the only source of truth (see `CLAUDE.md` non-goals).
 */
class MainActivity : ComponentActivity() {

    /**
     * The two editing panes. Only the first is shown until the user turns on split view, at which
     * point the second gets its own canvas and its own restored tab session (see [EditorPane]).
     */
    internal val panes: List<EditorPane> by lazy {
        TABS_DIRS.map { EditorPane(TabStore(File(filesDir, it))) }
    }

    /** Keeps two views of one mirrored document in step across the panes. */
    internal val mirrors: MirrorSync by lazy { MirrorSync(panes) }

    /** Which pane every menu/toolbar action applies to — the one last touched. */
    internal var activePane = mutableStateOf(0)

    /** Whether the editor is showing both panes side by side. */
    internal var splitView = mutableStateOf(false)

    /** The pane in focus. Everything below is written against this one document. */
    internal val pane: EditorPane get() = panes[activePane.value.coerceIn(panes.indices)]

    internal val surface: DrawingSurfaceView? get() = pane.surface

    /** Where a pending image-insert tap landed, kept until the SAF picker returns the image bytes. */
    internal var pendingImagePlacement: Placement? = null

    /** The mode chosen in the Import PDF dialog, kept until the SAF picker returns the PDF. */
    private var pendingImportMode: ImportPdfMode = ImportPdfMode.REPLACE

    // What to export, kept between the Export dialog's confirm and the SAF picker returning a
    // destination. [beginExport] fills them in; the launchers below read them back.

    /** Format the pending export writes. */
    internal var pendingExportFormat: ExportFormat = ExportFormat.PDF

    /** Zero-based page indices the pending export covers, already resolved from the range spec. */
    internal var pendingExportPages: List<Int> = emptyList()

    /** Resolution of the pending raster export, in dots per inch. */
    internal var pendingExportDpi: Int = DEFAULT_EXPORT_DPI

    /** File-name stem the pending export's per-page names are built from. */
    internal var pendingExportBaseName: String = "document"

    /** The file name last chosen in the Save As dialog; reused by plain Save. Per pane. */
    internal var pendingSaveName: String
        get() = pane.pendingSaveName
        set(value) { pane.pendingSaveName = value }

    /**
     * The sticky save format. "Save As" sets it; every later plain Save reuses it, so once you save
     * a ZIP package once, Save keeps writing one. Opening a document adopts the format it was stored
     * in.
     */
    internal var saveFormat: SaveFormat
        get() = pane.saveFormat
        set(value) { pane.saveFormat = value }

    /** Recording, playback and sidecar transfer for audio-annotated strokes (`fn`/`ts`). */
    internal val audio: AudioSession by lazy { AudioSession(this) }

    /** Persists [AppSettings]; also the home of the nominated audio folder grant. */
    internal val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    /**
     * The two user-configured autosave timers (see [AutoSavePolicy]). Fed edits by the canvas and
     * saves by [afterSaved]; when it fires, [autoSaveNow] decides whether the save can happen at all.
     */
    internal val autoSave: AutoSaveTimer by lazy { AutoSaveTimer(onDue = { autoSaveNow() }) }

    /**
     * The folder sidecar `.wav` files are kept in — a persisted `OpenDocumentTree` grant, normally
     * the folder the user's `.xopp` files live in. Null until they nominate one, in which case audio
     * still records and plays but never leaves the app (see [AudioSession]).
     */
    internal var audioFolder: Uri? = null

    /** Bumped whenever recording/playback state changes, so the Compose chrome re-reads it. */
    internal var audioTick = mutableStateOf(0)

    /** The active pane's open documents and which one is showing (see `com.nexopp.tabs`). */
    internal val tabs: TabManager get() = pane.tabs

    /** Bumped whenever a tab list or selection changes, so the tab strips re-read them. */
    internal var tabsTick = mutableStateOf(0)

    /**
     * The single worker every tab-overview preview is queued on, so a grid of tabs parses and
     * rasterises one document at a time instead of all of them at once (see [previewTabPage]).
     */
    internal val previewWorker: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    /**
     * All document I/O policy — staging, the background-PDF stores, and the read/encode/merge steps
     * (see [DocumentIo]). The activity keeps only the intent plumbing and the canvas wiring.
     */
    internal val io: DocumentIo by lazy {
        // Plain text defaults to monospace — the sensible face for the logs and source it usually
        // holds; markdown picks its own faces per run style (see MarkdownPdfWriter).
        val fonts = PdfFonts(assets)
        DocumentIo(contentResolver, cacheDir, filesDir, TextPdfGenerator(fonts::load))
    }

    /** Shared staging helper for URI byte transfers (export, image insert). */
    internal val staging: UriStaging by lazy { UriStaging(contentResolver, File(cacheDir, "staging")) }

    /** What long-running transfer is in flight, or null. Drives the editor's blocking progress note. */
    internal var busy = mutableStateOf<String?>(null)

    /**
     * What the format crossing that just happened could not carry, or null: strokes a `.rnote` open
     * could not convert, or content the save just written could not hold. On the save side it is set
     * only by a **plain** Save — a Save As has already shown the same lines in its confirmation
     * modal, so repeating them in a snackbar would be saying it twice.
     */
    internal val notice = mutableStateOf<ContentNotice?>(null)

    /**
     * Whether the next completed save should fill [notice]. Set by [saveActiveTab] and cleared
     * by [beginSaveAs], which are the two ways a save starts; they share one launcher, so the flag
     * is how the completion tells them apart.
     */
    internal var reportLossesAfterSave = false

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { insertPickedImage(it) }
        }

    /**
     * The document picker, asking for a **persistable read+write** grant rather than the default
     * one-shot read. That grant is what lets a later plain Save write straight back to the file the
     * document came from — including one on a mounted network share — and lets a restored tab still
     * reach it after a restart.
     */
    private class OpenDocumentForEditing : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
    }

    private val openLauncher =
        registerForActivityResult(OpenDocumentForEditing()) { uri ->
            uri?.let { openDocument(it) }
        }

    internal val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XOPP_MIME)) { uri ->
            uri?.let { saveDocument(it) }
        }

    private val importPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importPdf(it, pendingImportMode) }
        }

    internal val audioFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(::adoptAudioFolder)
        }

    internal val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginRecording() else toast("Recording needs microphone permission")
        }

    private val exportPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
            uri?.let { exportPdf(it, pendingExportPages) }
        }

    /**
     * The destination for a multi-file export: a **folder**, not a file, because every format except
     * PDF writes one document per page (see [ExportFormat.isMultiFile]).
     */
    private val exportFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val pages = pendingExportPages
                val format = pendingExportFormat
                if (format.isRaster) {
                    exportRaster(it, format, pages, pendingExportDpi, pendingExportBaseName)
                } else {
                    exportSvg(it, pages, pendingExportBaseName)
                }
            }
        }

    /**
     * Act on the Export dialog: resolve its [PageRange] [spec] against the open document, then open
     * the picker the [format] needs — one destination file for PDF, a folder for everything else.
     */
    internal fun beginExport(format: ExportFormat, spec: String, dpi: Int, baseName: String) {
        val pageCount = surface?.doc?.pages?.size ?: 0
        pendingExportFormat = format
        pendingExportPages = PageRange.parse(spec, pageCount)
        pendingExportDpi = dpi
        pendingExportBaseName = baseName
        if (pendingExportPages.isEmpty()) {
            toast("Nothing to export")
            return
        }
        if (format.isMultiFile) exportFolderLauncher.launch(null)
        else exportPdfLauncher.launch(format.fileName(baseName, null))
    }

    /**
     * Hardware-keyboard shortcuts for the spline tool, which is the one tool whose gesture spans
     * several taps: Enter commits the open curve, Backspace drops its last control point, and Escape
     * throws it away. Handled here rather than in the surface so the canvas never has to take
     * keyboard focus away from the app's text fields.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = surface
        if (event.action == KeyEvent.ACTION_UP && view != null && view.splineInProgress()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { view.finishSpline(); return true }
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->
                    { view.undoLastSplineNode(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { view.cancelSpline(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PDFBox needs its font/resource loader primed once before any PDF export can run.
        PDFBoxResourceLoader.init(applicationContext)
        // Size the one bitmap-cache budget from this device's heap, before any cache is built.
        BitmapBudget.configure(applicationContext)
        val store = settingsStore
        audioFolder = store.load().audioFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        audio.onStateChanged = { runOnUiThread { audioTick.value++ } }
        // A cold start from another app's "open with": stash it now, open it once the canvas is up.
        takeIncoming(intent)
        setContent {
            // Settings live above the theme so the Appearance choice re-colours the whole app.
            var settings by remember {
                mutableStateOf(
                    store.load().also { applyStorageLimits(it); autoSave.configure(it.autoSavePolicy) },
                )
            }
            XoppTheme(darkTheme = settings.themeMode.isDark(), dynamicColor = settings.dynamicColor) {
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = { saveActiveTab() },
                    busy = busy.value,
                    // Nothing left for back to peel off in the editor: leave the app for real.
                    onExit = { finish() },
                    onSaveAs = { name, format -> beginSaveAs(name, format) },
                    currentSaveFormat = { saveFormat },
                    saveWarnings = { format -> saveWarningsFor(format) },
                    notice = notice.value,
                    onNoticeShown = { notice.value = null },
                    onImportPdf = { mode ->
                        pendingImportMode = mode
                        importPdfLauncher.launch(arrayOf(PDF_MIME))
                    },
                    onExport = { format, spec, dpi, baseName ->
                        beginExport(format, spec, dpi, baseName)
                    },
                    onPickImage = { placement ->
                        pendingImagePlacement = placement
                        pickImageLauncher.launch(arrayOf("image/*"))
                    },
                    onSurfaceCreated = { index, view ->
                        val p = panes[index]
                        p.surface = view
                        view.onDocumentEdited = { doc ->
                            mirrors.propagate(p, doc)
                            autoSave.noteEdit()
                        }
                        view.onStrokeActiveChanged = { active -> autoSave.setStrokeInProgress(active) }
                        attachAudio(view)
                        // Restore is asynchronous now, so a file handed to us by another app is opened
                        // once the session is back — otherwise it would be shoved aside by the restore.
                        restoreTabs(p) { openIncoming() }
                    },
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        store.save(it)
                        applyStorageLimits(it)
                        autoSave.configure(it.autoSavePolicy)
                    },
                    audio = audioUiState(),
                    tabs = panes.map(::tabsUiState),
                    splitView = splitView.value,
                    onToggleSplitView = ::toggleSplitView,
                    activePane = activePane.value,
                    onActivePane = { activePane.value = it },
                )
            }
        }
    }

    // --- handovers from other apps --------------------------------------------------------------

    /**
     * A document another app handed us that hasn't been opened yet. The intent lands before the
     * canvas exists, so the URI waits here until the surface is up and the restored tabs are back.
     */
    private var pendingIntentUri: Uri? = null

    /**
     * A second handover while we're already running (the app was picked from a share/open sheet
     * without having been killed). Same routing as the cold start; the tab strip grows by one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeIncoming(intent)
        // Already running, so the surface exists — no need to wait for onSurfaceCreated.
        openIncoming()
    }

    /** Stash the document URI [intent] is handing over, if it is handing one over at all. */
    private fun takeIncoming(intent: Intent?) {
        val stream = @Suppress("DEPRECATION") intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val chosen = IncomingDocument.uriString(
            action = intent?.action,
            data = intent?.data?.toString(),
            stream = stream?.toString(),
        ) ?: return
        pendingIntentUri = Uri.parse(chosen)
    }

    /**
     * Open the handed-over document, once. Called after the surface is live (cold start) or straight
     * away (already running); clearing the field first keeps a re-entrant call from double-opening.
     */
    private fun openIncoming() {
        val uri = pendingIntentUri ?: return
        pendingIntentUri = null
        openDocument(uri)
    }

    /**
     * Run [work] off the UI thread behind a blocking progress note, then hand its result to [done]
     * back on the UI thread. Every document transfer goes through here: a remote share can stall for
     * seconds, and doing that inline would freeze (and eventually kill) the app.
     */
    internal fun <T> inBackground(label: String, work: () -> T, done: (Result<T>) -> Unit) {
        busy.value = label
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                busy.value = null
                done(result)
            }
        }.start()
    }


    /** Cache both panes' open tabs on the way to the background — the app may not come back. */
    override fun onPause() {
        super.onPause()
        // The snapshot below already preserves the edits, and firing a file write as the app leaves
        // the foreground only races the process death it is meant to survive.
        autoSave.cancel()
        // The write itself runs on the pane's writer thread; we wait briefly for it here because the
        // process can be killed once we are in the background, and a lost snapshot is lost edits.
        panes.forEach { snapshotActiveTab(it); it.persist() }
        panes.forEach { it.awaitPersist(PERSIST_WAIT_MS) }
    }

    /** Pick the autosave timers back up, since [onPause] dropped the pending one. */
    override fun onResume() {
        super.onResume()
        autoSave.arm()
    }

    override fun onDestroy() {
        autoSave.cancel()
        audio.release()
        super.onDestroy()
    }

    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    internal companion object {
        // Save with a MIME the framework has no canonical extension for, so the Storage Access
        // Framework keeps the exact "document.xopp" name we ask for. Using "application/gzip" here
        // made SAF append its own ".gz" extension (document.xopp.gz), which desktop Xournal++ won't
        // open by name; the bytes are gzip either way (Xopp.save always gzips), only the on-disk name
        // is at stake.
        const val XOPP_MIME = "application/octet-stream"
        const val PDF_MIME = "application/pdf"

        /**
         * Folders under `filesDir` holding each pane's cached tab session (see [TabStore]), in pane
         * order. The first keeps its historical name so an existing session still restores.
         */
        val TABS_DIRS = listOf("tabs", "tabs-right")

        /** How long `onPause` waits for the queued session write before letting the app go. */
        const val PERSIST_WAIT_MS = 2_000L

        /** Tab label for a document that has never been opened from, or saved to, a file. */
        internal const val UNTITLED = "Untitled"
    }
}
