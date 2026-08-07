package com.xopp.android

import android.Manifest
import android.content.Context
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
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.xopp.android.audio.AudioSession
import com.xopp.android.format.SaveFormat
import com.xopp.android.io.DocumentIo
import com.xopp.android.io.IncomingDocument
import com.xopp.android.panes.EditorPane
import com.xopp.android.panes.MirrorSync
import com.xopp.android.render.BitmapBudget
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.ImportPdfMode
import com.xopp.android.render.PdfFonts
import com.xopp.android.render.Placement
import com.xopp.android.render.TextPdfGenerator
import com.xopp.android.render.cancelSpline
import com.xopp.android.render.finishSpline
import com.xopp.android.render.splineInProgress
import com.xopp.android.tabs.TabManager
import com.xopp.android.tabs.TabStore
import com.xopp.android.ui.AppSettings
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

    /**
     * The two editing panes. Only the first is shown until the user turns on split view, at which
     * point the second gets its own canvas and its own restored tab session (see [EditorPane]).
     */
    internal val panes: List<EditorPane> by lazy {
        TABS_DIRS.map { EditorPane(TabStore(File(filesDir, it))) }
    }

    /** Keeps two views of one mirrored document in step across the panes. */
    private val mirrors: MirrorSync by lazy { MirrorSync(panes) }

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

    /** The file name last chosen in the Save As dialog; reused by plain Save. Per pane. */
    internal var pendingSaveName: String
        get() = pane.pendingSaveName
        set(value) { pane.pendingSaveName = value }

    /**
     * The sticky save format. "Save As" sets it; every later plain Save reuses it, so once you save
     * ZIPPED once, Save keeps writing ZIPPED. Opening a document adopts the format it was stored in.
     */
    internal var saveFormat: SaveFormat
        get() = pane.saveFormat
        set(value) { pane.saveFormat = value }

    /** Recording, playback and sidecar transfer for audio-annotated strokes (`fn`/`ts`). */
    internal val audio: AudioSession by lazy { AudioSession(this) }

    /** Persists [AppSettings]; also the home of the nominated audio folder grant. */
    private val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    /**
     * The folder sidecar `.wav` files are kept in — a persisted `OpenDocumentTree` grant, normally
     * the folder the user's `.xopp` files live in. Null until they nominate one, in which case audio
     * still records and plays but never leaves the app (see [AudioSession]).
     */
    internal var audioFolder: Uri? = null

    /** Bumped whenever recording/playback state changes, so the Compose chrome re-reads it. */
    private var audioTick = mutableStateOf(0)

    /** The active pane's open documents and which one is showing (see `com.xopp.android.tabs`). */
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

    /** What long-running transfer is in flight, or null. Drives the editor's blocking progress note. */
    internal var busy = mutableStateOf<String?>(null)

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
            uri?.let { exportPdf(it) }
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
        // Size the one bitmap-cache budget from this device's heap, before any cache is built.
        BitmapBudget.configure(applicationContext)
        val store = settingsStore
        audioFolder = store.load().audioFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        audio.onStateChanged = { runOnUiThread { audioTick.value++ } }
        // A cold start from another app's "open with": stash it now, open it once the canvas is up.
        takeIncoming(intent)
        setContent {
            // Settings live above the theme so the Appearance choice re-colours the whole app.
            var settings by remember { mutableStateOf(store.load().also { applyStorageLimits(it) }) }
            XoppTheme(darkTheme = settings.themeMode.isDark()) {
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = { saveActiveTab() },
                    busy = busy.value,
                    // Nothing left for back to peel off in the editor: leave the app for real.
                    onExit = { finish() },
                    onSaveAs = { name, format -> beginSaveAs(name, format) },
                    currentSaveFormat = { saveFormat },
                    onImportPdf = { mode ->
                        pendingImportMode = mode
                        importPdfLauncher.launch(arrayOf(PDF_MIME))
                    },
                    onExportPdf = { exportPdfLauncher.launch("document.pdf") },
                    onPickImage = { placement ->
                        pendingImagePlacement = placement
                        pickImageLauncher.launch(arrayOf("image/*"))
                    },
                    onSurfaceCreated = { index, view ->
                        val p = panes[index]
                        p.surface = view
                        view.onDocumentEdited = { doc -> mirrors.propagate(p, doc) }
                        attachAudio(view)
                        // Restore is asynchronous now, so a file handed to us by another app is opened
                        // once the session is back — otherwise it would be shoved aside by the restore.
                        restoreTabs(p) { openIncoming() }
                    },
                    settings = settings,
                    onSettingsChange = { settings = it; store.save(it); applyStorageLimits(it) },
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
    internal fun pullAudioSidecars() {
        val folder = audioFolder ?: return
        val doc = surface?.toDocument() ?: return
        val pulled = audio.importSidecars(folder, doc)
        val missing = audio.missingFor(doc).size
        if (pulled > 0) toast("Loaded $pulled recording(s)")
        else if (missing > 0) toast("$missing recording(s) referenced but not in the audio folder")
    }

    /** Copy the sidecars the open document references into the nominated folder, beside the .xopp. */
    internal fun pushAudioSidecars() {
        val folder = audioFolder ?: return
        val doc = surface?.toDocument() ?: return
        audio.exportSidecars(folder, doc)
    }

    /** Cache both panes' open tabs on the way to the background — the app may not come back. */
    override fun onPause() {
        super.onPause()
        // The write itself runs on the pane's writer thread; we wait briefly for it here because the
        // process can be killed once we are in the background, and a lost snapshot is lost edits.
        panes.forEach { snapshotActiveTab(it); it.persist() }
        panes.forEach { it.awaitPersist(PERSIST_WAIT_MS) }
    }

    override fun onDestroy() {
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
