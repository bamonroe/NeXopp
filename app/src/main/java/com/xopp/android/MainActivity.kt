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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import androidx.core.content.ContextCompat
import com.xopp.android.audio.AudioSession
import com.xopp.android.audio.documentAudioFiles
import com.xopp.android.format.SaveFormat
import com.xopp.android.io.DocumentIo
import com.xopp.android.io.IncomingDocument
import com.xopp.android.io.LoadedFile
import com.xopp.android.io.xoppNameFor
import com.xopp.android.render.BitmapBudget
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.ImportPdfMode
import com.xopp.android.render.PdfFonts
import com.xopp.android.render.PdfImport
import com.xopp.android.render.PdfPageCache
import com.xopp.android.render.PdfTextExtractor
import com.xopp.android.render.TextPdfGenerator
import com.xopp.android.panes.EditorPane
import com.xopp.android.panes.MirrorSync
import com.xopp.android.tabs.DocColors
import com.xopp.android.tabs.OpenTab
import com.xopp.android.tabs.TabManager
import com.xopp.android.tabs.TabStore
import com.xopp.android.render.Placement
import com.xopp.android.ui.AudioUiState
import com.xopp.android.ui.EditorScreen
import com.xopp.android.ui.SettingsStore
import com.xopp.android.ui.TabsUiState
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
    private val panes: List<EditorPane> by lazy {
        TABS_DIRS.map { EditorPane(TabStore(File(filesDir, it))) }
    }

    /** Keeps two views of one mirrored document in step across the panes. */
    private val mirrors: MirrorSync by lazy { MirrorSync(panes) }

    /** Which pane every menu/toolbar action applies to — the one last touched. */
    private var activePane = mutableStateOf(0)

    /** Whether the editor is showing both panes side by side. */
    private var splitView = mutableStateOf(false)

    /** The pane in focus. Everything below is written against this one document. */
    private val pane: EditorPane get() = panes[activePane.value.coerceIn(panes.indices)]

    private val surface: DrawingSurfaceView? get() = pane.surface

    /** Where a pending image-insert tap landed, kept until the SAF picker returns the image bytes. */
    private var pendingImagePlacement: Placement? = null

    /** The mode chosen in the Import PDF dialog, kept until the SAF picker returns the PDF. */
    private var pendingImportMode: ImportPdfMode = ImportPdfMode.REPLACE

    /** The file name last chosen in the Save As dialog; reused by plain Save. Per pane. */
    private var pendingSaveName: String
        get() = pane.pendingSaveName
        set(value) { pane.pendingSaveName = value }

    /**
     * The sticky save format. "Save As" sets it; every later plain Save reuses it, so once you save
     * ZIPPED once, Save keeps writing ZIPPED. Opening a document adopts the format it was stored in.
     */
    private var saveFormat: SaveFormat
        get() = pane.saveFormat
        set(value) { pane.saveFormat = value }

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

    /** The active pane's open documents and which one is showing (see `com.xopp.android.tabs`). */
    private val tabs: TabManager get() = pane.tabs

    /** Bumped whenever a tab list or selection changes, so the tab strips re-read them. */
    private var tabsTick = mutableStateOf(0)

    /**
     * All document I/O policy — staging, the background-PDF stores, and the read/encode/merge steps
     * (see [DocumentIo]). The activity keeps only the intent plumbing and the canvas wiring.
     */
    private val io: DocumentIo by lazy {
        // Monospace is the sensible default for the logs and source a text import usually holds.
        val fonts = PdfFonts(assets)
        DocumentIo(contentResolver, cacheDir, filesDir, TextPdfGenerator { doc ->
            fonts.load(doc, PdfFonts.Face.MONOSPACE)
        })
    }

    /** What long-running transfer is in flight, or null. Drives the editor's blocking progress note. */
    private var busy = mutableStateOf<String?>(null)

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::insertPickedImage)
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
            uri?.let(::openDocument)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XOPP_MIME)) { uri ->
            uri?.let(::saveDocument)
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
        // Size the one bitmap-cache budget from this device's heap, before any cache is built.
        BitmapBudget.configure(applicationContext)
        val store = settingsStore
        audioFolder = store.load().audioFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        audio.onStateChanged = { runOnUiThread { audioTick.value++ } }
        // A cold start from another app's "open with": stash it now, open it once the canvas is up.
        takeIncoming(intent)
        setContent {
            // Settings live above the theme so the Appearance choice re-colours the whole app.
            var settings by remember { mutableStateOf(store.load()) }
            XoppTheme(darkTheme = settings.themeMode.isDark()) {
                EditorScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    onSave = ::saveActiveTab,
                    busy = busy.value,
                    onSaveAs = ::beginSaveAs,
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
                        restoreTabs(p)
                        openIncoming()
                    },
                    settings = settings,
                    onSettingsChange = { settings = it; store.save(it) },
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
     * Open [uri] **in a new tab**: push the tab first so the load lands in it, then snapshot the
     * loaded document back into it. A failed open takes its half-built tab down with it rather than
     * leaving an empty stub in the strip.
     */
    private fun openDocument(uri: Uri) {
        snapshotActiveTab()
        val created = tabs.open(
            OpenTab(TabStore.newId(), displayName(uri), DrawingSurfaceView.blankDocument(), uri.toString()),
        )
        pendingSaveName = displayName(uri)
        tabsTick.value++
        // Keep the grant so plain Save can write back here later, even after a restart.
        io.persist(uri)
        // The bytes may be coming off a network share, so fetch them on a worker and only touch the
        // canvas once they have landed. A failure takes the half-built tab back down with it.
        inBackground("Opening ${displayName(uri)}…", { io.stageIn(uri, "open") }) { result ->
            // Staging names are unique per open, so the copy has to be swept once it has been read
            // or the cache would grow a file per document opened.
            result.mapCatching { staged -> try { loadDocument(staged, uri) } finally { staged.delete() } }
                .onSuccess { snapshotActiveTab() }
                .onFailure {
                    toast("Open failed: ${it.message}")
                    tabs.close(created)
                    tabs.active?.let(::showTab)
                }
            tabsTick.value++
            persistTabs()
        }
    }

    /**
     * Run [work] off the UI thread behind a blocking progress note, then hand its result to [done]
     * back on the UI thread. Every document transfer goes through here: a remote share can stall for
     * seconds, and doing that inline would freeze (and eventually kill) the app.
     */
    private fun <T> inBackground(label: String, work: () -> T, done: (Result<T>) -> Unit) {
        busy.value = label
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                busy.value = null
                done(result)
            }
        }.start()
    }

    /** Put a staged local copy of the document onto the canvas ([DocumentIo] decided what it is). */
    private fun loadDocument(staged: File, source: Uri) {
        when (val loaded = io.read(staged, displayName(source))) {
            // A raw PDF isn't a document to parse — it becomes a fresh annotatable one over its pages.
            is LoadedFile.Pdf -> {
                // A PDF we typeset from a text file lives only in the prunable cache, so there is no
                // stable path to link: save it as a ZIP package with the bytes embedded instead.
                saveFormat = if (loaded.generated) SaveFormat.ZIPPED else SaveFormat.ORIGINAL
                adoptPdf(loaded.file, ImportPdfMode.REPLACE, source.toString(), inStore = loaded.generated)
                // The tab came from a PDF, so it has no `.xopp` on disk yet: drop the source URI so
                // plain Save asks for a destination instead of writing document bytes over the PDF,
                // and suggest the PDF's own name with the extension swapped. The PDF itself stays on
                // as the page background, so the saved file still opens over it in desktop Xournal++.
                pendingSaveName = xoppNameFor(displayName(source))
                tabs.updateActive { it.copy(title = pendingSaveName, uri = null) }
                tabsTick.value++
                return
            }
            is LoadedFile.Doc -> {
                saveFormat = loaded.format
                surface?.setPdfSource(loaded.pdf?.let(::PdfPageCache))
                surface?.setPdfTextIndex(null) // cleared until extraction below finishes
                surface?.load(loaded.document)
                if (loaded.pdf != null) extractPdfTextInBackground(loaded.pdf)
                else if (loaded.missingPdf) toast("Background PDF not found; those pages will be blank")
            }
        }
        // The document may reference recordings made elsewhere; fetch them so its strokes replay.
        pullAudioSidecars()
    }

    /**
     * Import a PDF as annotatable pages — one `.xopp` page per PDF page, rendered as backgrounds.
     *
     * [ImportPdfMode.REPLACE] makes the PDF the whole document; [ImportPdfMode.APPEND] keeps the open
     * document and adds the PDF's pages after it (one undoable edit).
     *
     * A `.xopp` can reference only **one** background PDF, so appending onto a document that already
     * has one goes through [appendMergedPdf]: the two PDFs are merged into a single joined PDF that
     * becomes the document's one background source.
     */
    private fun importPdf(uri: Uri, mode: ImportPdfMode = ImportPdfMode.REPLACE) {
        // Persist read access so the same content URI still resolves when the saved .xopp is reopened
        // later (this is the reference plain Save records for domain="absolute"). Best-effort: import
        // still works if the grant isn't persistable.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // The PDF may live on a network share: fetch it on a worker, then build pages from the copy.
        inBackground("Importing ${displayName(uri)}…", { io.stageIn(uri, "import") }) { result ->
            // adoptPdf copies the bytes into the PdfStore, so the staged copy is scratch either way.
            result.mapCatching { staged -> try { adoptPdf(staged, mode, uri.toString()) } finally { staged.delete() } }
                .onFailure { toast("PDF import failed: ${it.message}") }
        }
    }

    /**
     * Turn an already-local [source] PDF into annotatable pages, per [mode]. [inStore] says [source]
     * *is* already the store's own never-rewritten copy (a text import's generated PDF), so it is
     * used in place rather than duplicated — which also keeps its cache entry alive with the tab.
     */
    private fun adoptPdf(source: File, mode: ImportPdfMode, reference: String, inStore: Boolean = false) {
        val file = if (inStore) source else io.adoptPdf(source)
        val view = surface
        val existing = view?.pdfSourceFile()
        if (mode == ImportPdfMode.APPEND && existing != null && view.hasPdfBackground()) {
            appendMergedPdf(view, existing, file)
            return
        }
        val cache = PdfPageCache(file)
        view?.setPdfSource(cache)
        view?.setPdfTextIndex(null) // cleared until extraction below finishes
        when (mode) {
            ImportPdfMode.REPLACE -> view?.load(PdfImport.documentFor(cache, reference))
            ImportPdfMode.APPEND -> view?.appendPages(PdfImport.pagesFor(cache, reference))
        }
        extractPdfTextInBackground(file)
    }

    /**
     * Append [incoming]'s pages to a document that already has a background PDF, by **merging** the
     * two into one joined PDF ([PdfMerger]) — the only shape a `.xopp` can represent, since it holds
     * a single background reference.
     *
     * The joined file lands in `filesDir` (not the cache) so the link a plain Save records survives;
     * successive appends ping-pong between two names so a merge never writes the file it's reading.
     * The document's one reference is re-pointed at the joined PDF and the new pages' `pageno` values
     * are renumbered against it, both in a single undoable edit.
     */
    private fun appendMergedPdf(view: DrawingSurfaceView, existing: File, incoming: File) {
        val offset = view.pdfSourcePageCount()
        // Size the new pages from the incoming PDF before anything is closed or replaced.
        val added = PdfPageCache(incoming).use { PdfImport.pagesFor(it, reference = null, pageNoOffset = offset) }
        val joined = io.merge(existing, incoming)
        view.setPdfSource(PdfPageCache(joined)) // closes the old rasteriser, releasing the old file
        view.setPdfTextIndex(null) // cleared until extraction below finishes
        view.appendPdfPages(added, joined.absolutePath)
        extractPdfTextInBackground(joined)
    }

    /** Extract a PDF's text layer off the UI thread (slow on big PDFs), then attach it for text-select. */
    private fun extractPdfTextInBackground(file: File, into: DrawingSurfaceView? = surface) {
        // Bind the destination canvas up front: with two panes open, the focus may well have moved
        // by the time a big PDF finishes extracting, and the index belongs to the pane that asked.
        val view = into ?: return
        Thread {
            val index = PdfTextExtractor().extract(file)
            view.post { view.setPdfTextIndex(index) }
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
    private fun saveDocument(uri: Uri) {
        val view = surface ?: return
        // Encode locally first, then push the finished bytes across in one pass: on a slow or flaky
        // remote share, serialising straight down the wire risks leaving a half-written .xopp behind.
        val staged = runCatching { io.encode(view.toDocument(), view.pdfSourceFile(), saveFormat) }
            .getOrElse { toast("Save failed: ${it.message}"); return }

        inBackground("Saving ${displayName(uri)}…", { io.stageOut(staged, uri) }) { result ->
            staged.delete()
            result.onFailure { toast("Save failed: ${it.message}") }
                .onSuccess { afterSaved(view, uri) }
        }
    }

    /** Book-keeping for a document that has just landed on disk at [uri]. */
    private fun afterSaved(view: DrawingSurfaceView, uri: Uri) {
        // Hold the grant so the next plain Save can write back here without asking again.
        io.persist(uri)
        // The tab now belongs to the file it was just written to: relabel it and remember where it
        // lives, so the strip shows the real name and a restored session points at the same document.
        tabs.updateActive { it.copy(title = displayName(uri), uri = uri.toString()) }
        pendingSaveName = displayName(uri)
        snapshotActiveTab()
        tabsTick.value++
        persistTabs()
        // A .xopp never carries its audio — the sidecars have to land beside it or playback breaks.
        pushAudioSidecars()
        if (audioFolder == null && audio.missingFor(view.toDocument()).isEmpty() &&
            documentHasAudio(view)
        ) {
            toast("Choose an audio folder so recordings are saved beside this file")
        }
    }

    /**
     * Plain Save: write straight back to the file this tab came from when we still hold a write
     * grant on it (the normal case for a document opened from local storage *or* a mounted remote
     * share), and only fall back to asking for a location when we don't.
     */
    private fun saveActiveTab() {
        val target = tabs.active?.uri?.let(Uri::parse)?.takeIf(io::isWritable)
        if (target != null) saveDocument(target) else saveLauncher.launch(pendingSaveName)
    }

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

    // --- tabs: several documents open at once, restored on the next launch ----------------------
    //
    // A pane has exactly one canvas, so a tab switch is a swap: snapshot the outgoing document out of
    // the surface ([snapshotActiveTab]), then load the incoming one into it ([showTab]). Everything a
    // tab needs to come back — its document, source URI, save format, background PDF and page — lives
    // in its [OpenTab] record, which is also what [TabStore] writes to disk.
    //
    // Every function here takes the [EditorPane] it applies to, defaulting to the pane in focus, so
    // split view is just "the same thing, twice" — each pane keeps its own session and its own file.
    //
    // Undo history belongs to the surface, not the document, so it does not follow a tab switch: the
    // incoming tab starts with a clean history. Its content, including unsaved edits, is intact.

    /** A pane's tab strip view of its session, re-read whenever [tabsTick] changes. */
    private fun tabsUiState(p: EditorPane): TabsUiState {
        tabsTick.value // read so Compose re-invokes this when the session changes
        val dots = DocColors.assign(panes.flatMap { pane -> pane.tabs.tabs.map(OpenTab::docKey) })
        return TabsUiState(
            titles = p.tabs.tabs.map { it.title },
            dotColors = p.tabs.tabs.map { dots[it.docKey] },
            activeIndex = p.tabs.activeIndex,
            onSelect = { selectTab(it, p) },
            onClose = { closeTab(it, p) },
            onNew = { newTab(p) },
            onMove = { sendTabToOtherPane(it, p, keepHere = false) },
            onMirror = { sendTabToOtherPane(it, p, keepHere = true) },
            onReorder = { from, to -> reorderTab(from, to, p) },
        )
    }

    /**
     * Hand [p]'s tab at [index] to the other pane — the long-press actions on a tab.
     *
     * With [keepHere] false this is a *move*: the tab leaves this pane (which falls back to a blank
     * document if it was the last one) and opens in the other. With [keepHere] true it is a *mirror*:
     * a second **view** of the same document opens in the other pane. The two views share one
     * document — the new tab keeps the original's [OpenTab.docKey], so [MirrorSync] pushes every edit
     * from either side straight into the other — while keeping their own scroll, zoom and page, and
     * both write back to the same file. Split view is opened if it was closed, since otherwise there
     * is nowhere to put it.
     */
    private fun sendTabToOtherPane(index: Int, from: EditorPane, keepHere: Boolean) {
        val other = panes.firstOrNull { it !== from } ?: return
        snapshotActiveTab(from)
        val source = from.tabs.tabs.getOrNull(index) ?: return
        hydrate(other)
        other.tabs.open(source.copy(id = TabStore.newId()))
        if (!keepHere) {
            val showing = from.tabs.active?.id
            from.tabs.close(index)
            if (from.tabs.isEmpty) from.tabs.open(blankTab())
            from.tabs.active?.takeIf { it.id != showing }?.let { showTab(it, from) }
        }
        // Only paints now if that pane already has a canvas; otherwise [restoreTabs] shows it when
        // split view builds one.
        other.tabs.active?.let { showTab(it, other) }
        splitView.value = true
        from.persist()
        other.persist()
        tabsTick.value++
    }

    /** Load a pane's stored session into its manager without touching a canvas, if it has none yet. */
    private fun hydrate(p: EditorPane) {
        if (!p.tabs.isEmpty) return
        val session = p.store.load() ?: return
        session.tabs.forEach(p.tabs::open)
        p.tabs.select(session.activeIndex)
    }

    /** Copy the live canvas back into the showing tab's record, so switching away doesn't lose it. */
    private fun snapshotActiveTab(p: EditorPane = pane) {
        val view = p.surface ?: return
        p.tabs.updateActive {
            it.copy(
                document = view.toDocument(),
                format = p.saveFormat,
                pdfPath = view.pdfSourceFile()?.absolutePath,
                page = view.visiblePageIndex(),
            )
        }
    }

    /** Load [tab] onto [p]'s canvas: its document, background PDF, save format and page. */
    private fun showTab(tab: OpenTab, p: EditorPane = pane) {
        val view = p.surface ?: return
        p.saveFormat = tab.format
        p.pendingSaveName = tab.title
        val pdf = tab.pdfPath?.let(::File)?.takeIf(File::exists)
        view.setPdfSource(pdf?.let(::PdfPageCache))
        view.setPdfTextIndex(null) // cleared until extraction below finishes
        view.load(tab.document)
        if (tab.page > 0) view.goToPage(tab.page)
        if (pdf != null) extractPdfTextInBackground(pdf, view)
    }

    /** Switch [p] to the tab at [index], snapshotting the one being left. */
    private fun selectTab(index: Int, p: EditorPane = pane) {
        snapshotActiveTab(p)
        if (!p.tabs.select(index)) return
        p.tabs.active?.let { showTab(it, p) }
        tabsTick.value++
        p.persist()
    }

    /**
     * Reorder [p]'s strip: the tab at [from] moves to [to] — the drag gesture on a tab. No document
     * is loaded or unloaded, so the canvas is left alone; only the strip order and the saved session
     * change.
     */
    private fun reorderTab(from: Int, to: Int, p: EditorPane = pane) {
        if (!p.tabs.move(from, to)) return
        tabsTick.value++
        p.persist()
    }

    /**
     * Close [p]'s tab at [index]. Closing the last one leaves a fresh blank document rather than an
     * empty pane, and the canvas is only reloaded when the *showing* tab actually changed.
     */
    private fun closeTab(index: Int, p: EditorPane = pane) {
        snapshotActiveTab(p)
        val showing = p.tabs.active?.id
        p.tabs.close(index) ?: return
        if (p.tabs.isEmpty) p.tabs.open(blankTab())
        p.tabs.active?.takeIf { it.id != showing }?.let { showTab(it, p) }
        tabsTick.value++
        p.persist()
        prunePdfCache() // the closed tab's background PDF is now unreferenced
    }

    /** Open a fresh blank document in a new tab of [p] and switch to it. */
    private fun newTab(p: EditorPane = pane) {
        snapshotActiveTab(p)
        p.tabs.open(blankTab())
        p.tabs.active?.let { showTab(it, p) }
        tabsTick.value++
        p.persist()
    }

    private fun blankTab() =
        OpenTab(TabStore.newId(), UNTITLED, DrawingSurfaceView.blankDocument())

    /**
     * Bring back the tabs [p] was last closed with, or start it on a single blank one. Called once
     * that pane's canvas exists; guarded so a surface rebuilt within this activity doesn't restore
     * twice (and so the right-hand pane only restores when split view actually opens it).
     */
    private fun restoreTabs(p: EditorPane) {
        // A pane that already has a session is being handed a *replacement* canvas (split view was
        // switched off and on again): put its showing document straight back onto the new surface.
        if (!p.tabs.isEmpty) {
            p.tabs.active?.let { showTab(it, p) }
            return
        }
        val session = p.store.load()
        if (session == null) {
            p.tabs.open(blankTab())
            p.tabs.active?.let { showTab(it, p) }
        } else {
            session.tabs.forEach(p.tabs::open)
            p.tabs.select(session.activeIndex)
            p.tabs.active?.let { showTab(it, p) }
        }
        tabsTick.value++
    }

    /** Write the focused pane's session out, on every tab change and on the way to the background. */
    private fun persistTabs() {
        pane.persist()
        prunePdfCache()
    }

    /**
     * Drop the background PDFs no open tab refers to any more. Each open allocates its own file
     * ([PdfStore]), so without this sweep the folders would grow with every document opened.
     *
     * The live surfaces are counted alongside the tab records: a document whose PDF was just
     * imported has not been snapshotted back into its tab yet, and deleting the file out from under
     * the canvas is precisely the bug this store exists to prevent.
     */
    private fun prunePdfCache() {
        val live = panes.flatMap { p ->
            p.tabs.tabs.map(OpenTab::pdfPath) + listOf(p.surface?.pdfSourceFile()?.absolutePath)
        }
        io.prune(live)
    }

    /**
     * Turn split view on or off. Leaving it snapshots the right-hand pane and hands focus back to the
     * left one, so a menu action never targets a canvas that is no longer on screen. The pane's tabs
     * are kept (and stay on disk), so turning split view back on brings the same documents back.
     */
    private fun toggleSplitView() {
        val on = !splitView.value
        if (!on) {
            panes.forEach { snapshotActiveTab(it); it.persist() }
            activePane.value = 0
        }
        splitView.value = on
    }

    /** The file name behind a `content://` URI, for the tab's label. Falls back to [UNTITLED]. */
    private fun displayName(uri: Uri): String = io.displayName(uri, UNTITLED)

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

    /** Cache both panes' open tabs on the way to the background — the app may not come back. */
    override fun onPause() {
        super.onPause()
        panes.forEach { snapshotActiveTab(it); it.persist() }
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

        /**
         * Folders under `filesDir` holding each pane's cached tab session (see [TabStore]), in pane
         * order. The first keeps its historical name so an existing session still restores.
         */
        val TABS_DIRS = listOf("tabs", "tabs-right")

        /** Tab label for a document that has never been opened from, or saved to, a file. */
        const val UNTITLED = "Untitled"
    }
}
