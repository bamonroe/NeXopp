package com.xopp.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.xopp.android.format.FileKind
import com.xopp.android.format.SaveFormat
import com.xopp.android.format.Xopp
import com.xopp.android.format.XoppZip
import com.xopp.android.format.model.Background
import com.xopp.android.io.UriStaging
import com.xopp.android.render.ATTACH_DOMAIN
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.ImportPdfMode
import com.xopp.android.render.PdfImport
import com.xopp.android.render.PdfMerger
import com.xopp.android.render.documentWithPdfDomain
import com.xopp.android.render.PdfPageCache
import com.xopp.android.render.PdfTextExtractor
import com.xopp.android.panes.EditorPane
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

    /** Staging for document bytes, so slow remote (SSHFS/FTP/cloud) URIs never block the canvas. */
    private val staging: UriStaging by lazy { UriStaging(contentResolver, File(cacheDir, STAGING_DIR)) }

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
        val store = settingsStore
        audioFolder = store.load().audioFolderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        audio.onStateChanged = { runOnUiThread { audioTick.value++ } }
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
                        attachAudio(view)
                        restoreTabs(p)
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
        staging.persist(uri)
        // The bytes may be coming off a network share, so fetch them on a worker and only touch the
        // canvas once they have landed. A failure takes the half-built tab back down with it.
        inBackground("Opening ${displayName(uri)}…", { staging.stageIn(uri, "open") }) { result ->
            result.mapCatching { staged -> loadDocument(staged, uri) }
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

    /** Read a staged local copy of the document into the canvas, sniffing its container. */
    private fun loadDocument(staged: File, source: Uri) {
        // Sniff the container by its leading bytes, never by extension: the picker is unfiltered and
        // SAF gives us content:// URIs with no reliable suffix. The verdict also fixes the sticky save
        // format, so a reopened ZIP keeps saving ZIPPED and a gzip .xopp keeps saving gzip.
        val kind = staged.inputStream().use { FileKind.sniff(it.buffered()) }
        if (kind == FileKind.PDF) {
            // A raw PDF isn't a document to parse — it becomes a fresh annotatable one over its pages.
            saveFormat = SaveFormat.ORIGINAL
            adoptPdf(staged, ImportPdfMode.REPLACE, reference = source.toString())
            return
        }
        staged.inputStream().use { raw ->
            val input = raw.buffered()
            when (kind) {
                FileKind.ZIP -> openZip(input)
                FileKind.GZIP -> openGzip(input)
                // Desktop Xournal++ can write plain XML; accept it and save it back compressed.
                FileKind.XML -> openParsed(Xopp.parseXml(input.reader(Charsets.UTF_8).readText()))
                else -> error("unrecognised file type")
            }
        }
        // The document may reference recordings made elsewhere; fetch them so its strokes replay.
        pullAudioSidecars()
    }

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
    private fun openGzip(input: java.io.InputStream) = openParsed(Xopp.open(input))

    /** Load an already-parsed document, resolving any linked PDF background. Saves back as gzip. */
    private fun openParsed(doc: com.xopp.android.format.model.Document) {
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
        inBackground("Importing ${displayName(uri)}…", { staging.stageIn(uri, "import") }) { result ->
            result.mapCatching { adoptPdf(it, mode, uri.toString()) }
                .onFailure { toast("PDF import failed: ${it.message}") }
        }
    }

    /** Turn an already-local [source] PDF into annotatable pages, per [mode]. */
    private fun adoptPdf(source: File, mode: ImportPdfMode, reference: String) {
        val file = File(cacheDir, "imported.pdf")
        source.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
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
        val joined = PdfMerger.join(existing, incoming, PdfMerger.nextJoinedFile(filesDir, existing))
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
        val staged = runCatching {
            val out = staging.newFile("save")
            out.outputStream().use { output ->
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
            out
        }.getOrElse { toast("Save failed: ${it.message}"); return }

        inBackground("Saving ${displayName(uri)}…", { staging.stageOut(staged, uri) }) { result ->
            result.onFailure { toast("Save failed: ${it.message}") }
                .onSuccess { afterSaved(view, uri) }
        }
    }

    /** Book-keeping for a document that has just landed on disk at [uri]. */
    private fun afterSaved(view: DrawingSurfaceView, uri: Uri) {
        // Hold the grant so the next plain Save can write back here without asking again.
        staging.persist(uri)
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
        val target = tabs.active?.uri?.let(Uri::parse)?.takeIf(staging::isWritable)
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
        return TabsUiState(
            titles = p.tabs.tabs.map { it.title },
            activeIndex = p.tabs.activeIndex,
            onSelect = { selectTab(it, p) },
            onClose = { closeTab(it, p) },
            onNew = { newTab(p) },
            onMove = { sendTabToOtherPane(it, p, keepHere = false) },
            onMirror = { sendTabToOtherPane(it, p, keepHere = true) },
        )
    }

    /**
     * Hand [p]'s tab at [index] to the other pane — the long-press actions on a tab.
     *
     * With [keepHere] false this is a *move*: the tab leaves this pane (which falls back to a blank
     * document if it was the last one) and opens in the other. With [keepHere] true it is a *mirror*:
     * the document is copied into the other pane so the same content shows on both sides. The copy is
     * a snapshot, not a live link — the two sides then edit independently, and each saves to its own
     * tab's file. Split view is opened if it was closed, since otherwise there is nowhere to put it.
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
    private fun persistTabs() = pane.persist()

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
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: UNTITLED

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

        /** Cache subfolder holding the local staging copies of documents in transit (see `UriStaging`). */
        const val STAGING_DIR = "staging"

        /** Tab label for a document that has never been opened from, or saved to, a file. */
        const val UNTITLED = "Untitled"
    }
}
