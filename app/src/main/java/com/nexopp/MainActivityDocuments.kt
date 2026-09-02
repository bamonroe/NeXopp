package com.nexopp

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.nexopp.audio.documentAudioFiles
import com.nexopp.format.ExportFormat
import com.nexopp.format.SaveFormat
import com.nexopp.format.rnote.exportWarnings
import com.nexopp.io.LoadedFile
import com.nexopp.io.StorageLimits
import com.nexopp.io.UriStaging
import com.nexopp.io.canAutoSave
import com.nexopp.io.saveNameFor
import com.nexopp.render.DrawingSurfaceView
import com.nexopp.render.ImageImport
import com.nexopp.render.ImportPdfMode
import com.nexopp.render.PdfImport
import com.nexopp.render.blankDocument
import com.nexopp.render.PdfPageCache
import com.nexopp.render.PdfTextExtractor
import com.nexopp.render.PdfTextIndexCache
import com.nexopp.render.SvgExporter
import com.nexopp.tabs.OpenTab
import com.nexopp.tabs.TabStore
import com.nexopp.ui.AppSettings
import com.nexopp.ui.ContentNotice
import com.nexopp.ui.NoticeOffer
import java.io.File

/** The snackbar line for content an opened file's conversion could not carry into our model. */
private const val OPEN_NOTICE = "Some content could not be opened"

/** The snackbar line for content the file just written could not hold. */
private const val SAVE_NOTICE = "Some content was not saved"

// --- document I/O: open, import, save --------------------------------------------------------
//
// [MainActivity]'s half of the document pipeline, kept out of the activity file itself: opening a
// file into a tab, adopting a PDF or an image as annotatable pages, and writing the document back
// out. The policy lives in [com.nexopp.io.DocumentIo]; these are the activity-side steps that
// stage the bytes off the UI thread, drive the canvas, and keep the tab strip in step.

/**
 * Open [uri] **in a new tab**: push the tab first so the load lands in it, then snapshot the
 * loaded document back into it. A failed open takes its half-built tab down with it rather than
 * leaving an empty stub in the strip.
 */
internal fun MainActivity.openDocument(uri: Uri) {
    snapshotActiveTab()
    val created = tabs.open(
        OpenTab(TabStore.newId(), displayName(uri), blankDocument(), uri.toString()),
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
 * Re-read the active tab's document from its own file, **discarding every unsaved edit** in it.
 *
 * This is destructive by design — it is the "throw my changes away and show me what's on disk"
 * button, so the caller is responsible for confirming with the user first. The reloaded document
 * replaces the one on the canvas through [loadDocument], which goes through `surface.load(…)` and
 * therefore drops the undo history along with the edits.
 *
 * Unlike [openDocument] this reuses the current tab (no new tab is pushed), and a failed read
 * leaves that tab exactly as it was: the in-memory document stays on the canvas rather than the
 * tab being taken down. A tab with no file behind it (`uri == null`) has nothing to re-read, so
 * this does nothing — the toolbar disables the button in that case.
 */
internal fun MainActivity.reloadActiveTab() {
    val uri = tabs.active?.uri?.let(Uri::parse) ?: return
    inBackground("Reloading ${displayName(uri)}…", { io.stageIn(uri, "reload") }) { result ->
        // Staging names are unique per read, so the copy is swept once it has been parsed.
        result.mapCatching { staged -> try { loadDocument(staged, uri) } finally { staged.delete() } }
            .onSuccess {
                // The canvas now matches the file: stand the dirty flag and the interval baseline
                // down, exactly as showTab does when a different document lands on the canvas.
                autoSave.reset()
                snapshotActiveTab()
                tabsTick.value++
                persistTabs()
            }
            .onFailure { toast("Open failed: ${it.message}") }
    }
}

/**
 * Push the Storage preferences down into [io], which enforces them (refusing an oversized text
 * import, and trimming the PDF cache on the next prune). Called on load and on every edit, since
 * [DocumentIo] outlives any one settings value.
 */
internal fun MainActivity.applyStorageLimits(settings: AppSettings) {
    io.limits = StorageLimits(
        textImportBytes = settings.textImportLimitBytes,
        pdfCacheBytes = settings.pdfCacheLimitBytes,
        // The pixmap copies share the one user-facing cache budget rather than adding a second
        // number to Settings; both stores hold background pictures for open documents.
        imageCacheBytes = settings.pdfCacheLimitBytes,
    )
}

/** Put a staged local copy of the document onto the canvas ([DocumentIo] decided what it is). */
internal fun MainActivity.loadDocument(staged: File, source: Uri) {
    when (val loaded = io.read(staged, displayName(source), source)) {
        // A raw PDF isn't a document to parse — it becomes a fresh annotatable one over its pages.
        is LoadedFile.Pdf -> {
            // A PDF we typeset from a text file lives only in the prunable cache, so there is no
            // stable path to link: save it as a ZIP package with the bytes embedded instead.
            saveFormat = if (loaded.generated) SaveFormat.XOPP_ZIP else SaveFormat.XOPP_GZIP
            adoptPdf(loaded.file, ImportPdfMode.REPLACE, source.toString(), inStore = loaded.generated)
            // The tab came from a PDF, so it has no `.xopp` on disk yet: drop the source URI so
            // plain Save asks for a destination instead of writing document bytes over the PDF,
            // and suggest the PDF's own name with the extension swapped. The PDF itself stays on
            // as the page background, so the saved file still opens over it in desktop Xournal++.
            pendingSaveName = saveNameFor(displayName(source), saveFormat)
            tabs.updateActive { it.copy(title = pendingSaveName, uri = null) }
            tabsTick.value++
            return
        }
        // An image likewise isn't a document to parse — it becomes a one-page document with the
        // picture as that page's pixmap background.
        is LoadedFile.Image -> {
            saveFormat = SaveFormat.XOPP_GZIP
            if (!adoptImage(loaded.file, source.toString())) {
                toast("Couldn't read that image")
                return
            }
            // Like a PDF import, the tab has no `.xopp` behind it yet: drop the source URI so a
            // plain Save asks where to put one instead of writing document bytes over the image.
            pendingSaveName = saveNameFor(loaded.name, saveFormat)
            tabs.updateActive { it.copy(title = pendingSaveName, uri = null) }
            tabsTick.value++
            return
        }
        is LoadedFile.Doc -> {
            saveFormat = loaded.format
            surface?.setPdfSource(loaded.pdf?.let(PdfPageCache::shared))
            surface?.setPdfTextIndex(null) // cleared until extraction below finishes
            // The pictures behind pixmap backgrounds were copied out to local files on load;
            // hand them over before the document, so the first frame already has them.
            surface?.setImageSources(loaded.images)
            surface?.load(loaded.document)
            // What the conversion into our model could not carry (a `.rnote` only). The
            // lossy-mapping policy forbids dropping this silently, so it goes straight to the
            // same snackbar the save side reports through.
            notice.value =
                if (loaded.notices.isEmpty()) null else ContentNotice(OPEN_NOTICE, loaded.notices)
            if (loaded.pdf != null) extractPdfTextInBackground(loaded.pdf)
            else if (loaded.missingPdf) toast("Background PDF not found; those pages will be blank")
            else if (loaded.missingImage) toast("Background image not found; those pages will be blank")
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
internal fun MainActivity.importPdf(uri: Uri, mode: ImportPdfMode = ImportPdfMode.REPLACE) {
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
internal fun MainActivity.adoptPdf(source: File, mode: ImportPdfMode, reference: String, inStore: Boolean = false) {
    val file = if (inStore) source else io.adoptPdf(source)
    val view = surface
    val existing = view?.pdfSourceFile()
    if (mode == ImportPdfMode.APPEND && existing != null && view.hasPdfBackground()) {
        appendMergedPdf(view, existing, file)
        return
    }
    val cache = PdfPageCache.shared(file)
    view?.setPdfSource(cache)
    view?.setPdfTextIndex(null) // cleared until extraction below finishes
    when (mode) {
        // A replaced document keeps none of the old one's pixmap pictures.
        ImportPdfMode.REPLACE -> {
            view?.setImageSources(emptyMap())
            view?.load(PdfImport.documentFor(cache, reference))
        }
        ImportPdfMode.APPEND -> view?.appendPages(PdfImport.pagesFor(cache, reference))
    }
    if (view == null) cache.close() // nobody took the claim; don't hold the renderer open
    // Freshly imported bytes: whatever was extracted for this path before doesn't describe them.
    PdfTextIndexCache.forget(file)
    extractPdfTextInBackground(file)
}

/**
 * Turn an already-local [source] image into a one-page document whose background is the picture
 * ([ImageImport]). [reference] is what the background records as its `filename` — the source
 * `content://` URI, whose read grant the open path has already persisted, so a saved `.xopp`
 * still resolves the image when reopened.
 *
 * The document links the picture by location — a pixmap background is a reference, and the
 * picture stays where the user keeps it — but a copy is kept in the image store anyway, so the
 * page still renders once the staged file is swept and bundles into a ZIP save. Returns false
 * when the file doesn't decode, leaving the canvas untouched.
 */
internal fun MainActivity.adoptImage(source: File, reference: String): Boolean {
    val (widthPx, heightPx) = ImageImport.pixelSize(source) ?: return false
    surface?.setPdfSource(null)
    surface?.setPdfTextIndex(null)
    // The document links the picture by its source URI, but the staged copy is swept and the
    // grant expires — so keep our own copy and render (and bundle) from that.
    surface?.setImageSources(mapOf(reference to io.adoptImage(source)))
    surface?.load(ImageImport.documentFor(widthPx, heightPx, reference))
    return true
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
internal fun MainActivity.appendMergedPdf(view: DrawingSurfaceView, existing: File, incoming: File) {
    val offset = view.pdfSourcePageCount()
    // Size the new pages from the incoming PDF before anything is closed or replaced.
    val added = PdfPageCache(incoming).use { PdfImport.pagesFor(it, reference = null, pageNoOffset = offset) }
    val joined = io.merge(existing, incoming)
    PdfTextIndexCache.forget(joined) // the merge rewrote these bytes; any cached index is stale
    view.setPdfSource(PdfPageCache.shared(joined)) // releases the old rasteriser, freeing the old file
    view.setPdfTextIndex(null) // cleared until extraction below finishes
    view.appendPdfPages(added, joined.absolutePath)
    extractPdfTextInBackground(joined)
}

/** Extract a PDF's text layer off the UI thread (slow on big PDFs), then attach it for text-select. */
internal fun MainActivity.extractPdfTextInBackground(file: File, into: DrawingSurfaceView? = surface) {
    // Bind the destination canvas up front: with two panes open, the focus may well have moved
    // by the time a big PDF finishes extracting, and the index belongs to the pane that asked.
    val view = into ?: return
    // The other pane may already have extracted this very file (a mirrored document); reuse its
    // index rather than paying for the walk — and for a second copy of the words — again.
    PdfTextIndexCache.get(file)?.let { return view.setPdfTextIndex(it) }
    Thread {
        val index = PdfTextExtractor().extract(file)
        PdfTextIndexCache.put(file, index)
        view.post { view.setPdfTextIndex(index) }
    }.start()
}

/**
 * Flatten the current document to a PDF at the chosen location (backgrounds + annotations).
 *
 * [pages] is a zero-based page selection; `null` means the whole document.
 */
internal fun MainActivity.exportPdf(uri: Uri, pages: List<Int>? = null) = runCatching {
    staging.writeTo(uri) { output: java.io.OutputStream -> surface?.exportPdf(output, pages) }
}.onFailure { toast("PDF export failed: ${it.message}") }

/**
 * Flatten [pages] to one image file each inside the picked folder, named `base-001.png` and so on
 * (see [ExportFormat.fileName]).
 *
 * Raster export is inherently multi-file — SAF's `CreateDocument` can only ever hand back a single
 * destination — so this takes a tree grant from `OpenDocumentTree` and creates a child document per
 * page. A page that fails to rasterise is skipped rather than aborting the run; the toast reports
 * how many files actually landed.
 */
internal fun MainActivity.exportRaster(
    treeUri: Uri,
    format: ExportFormat,
    pages: List<Int>,
    dpi: Int,
    baseName: String,
) = runCatching {
    val view = surface ?: return@runCatching
    val parent = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    var written = 0
    for (index in pages) {
        val bitmap = view.rasterizePage(index, dpi) ?: continue
        try {
            val child = DocumentsContract.createDocument(
                contentResolver, parent, format.mime, format.fileName(baseName, index),
            ) ?: continue
            staging.writeTo(child) { out -> bitmap.compress(format.compressFormat(), format.quality(), out) }
            written++
        } finally {
            bitmap.recycle()
        }
    }
    toast(if (written == 1) "Exported 1 page" else "Exported $written pages")
}.onFailure { toast("Export failed: ${it.message}") }

/**
 * Write [pages] to one SVG file each inside the picked folder, named like the raster export's files
 * (see [ExportFormat.fileName]).
 *
 * The vector twin of [exportRaster], and multi-file for the same reason: SVG holds a single page, so
 * a document becomes a folder of them. A page that fails to serialise is skipped rather than
 * aborting the run, and the toast reports how many files actually landed.
 */
internal fun MainActivity.exportSvg(
    treeUri: Uri,
    pages: List<Int>,
    baseName: String,
) = runCatching {
    val doc = surface?.doc ?: return@runCatching
    val parent = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val exporter = SvgExporter()
    var written = 0
    for (index in pages) {
        val page = doc.pages.getOrNull(index) ?: continue
        val child = DocumentsContract.createDocument(
            contentResolver, parent, ExportFormat.SVG.mime, ExportFormat.SVG.fileName(baseName, index),
        ) ?: continue
        staging.writeTo(child) { out -> out.writer().use { exporter.export(page, it) } }
        written++
    }
    toast(if (written == 1) "Exported 1 page" else "Exported $written pages")
}.onFailure { toast("Export failed: ${it.message}") }

/** The Android encoder for a raster [ExportFormat]; PDF and SVG never reach here. */
private fun ExportFormat.compressFormat(): Bitmap.CompressFormat = when (this) {
    ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
    // WEBP_LOSSY rather than the deprecated WEBP: same output, and it keeps the quality argument
    // meaningful on API 30+.
    ExportFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
    else -> Bitmap.CompressFormat.PNG
}

/** Encoder quality: lossless for PNG, and a high-but-not-wasteful 92 for the lossy formats. */
private fun ExportFormat.quality(): Int = if (this == ExportFormat.PNG) 100 else 92

/** Read the picked image's bytes and place it at the tap that started the pick. */
internal fun MainActivity.insertPickedImage(uri: Uri) = runCatching {
    val placement = pendingImagePlacement ?: return@runCatching
    pendingImagePlacement = null
    val bytes = staging.readBytes(uri)
    surface?.insertImage(placement, bytes)
}.onFailure { toast("Image insert failed: ${it.message}") }

/**
 * Write the current document to [uri] in the sticky [saveFormat]. All three formats are single
 * files, so all three go through the plain CreateDocument picker:
 * - [SaveFormat.XOPP_GZIP] — gzip XML, PDF background left linked by path/URI (interchange-safe).
 * - [SaveFormat.XOPP_ZIP] — a ZIP package with the PDF embedded (`domain="attach"`, `bg.pdf`).
 * - [SaveFormat.RNOTE] — gzip JSON in Rnote's own format, with no PDF background of any kind.
 */
internal fun MainActivity.saveDocument(uri: Uri, quiet: Boolean = false) {
    val view = surface ?: return
    if (quiet) {
        // The autosave path. Read the document on the UI thread (the canvas owns it), then do both
        // the encode — the expensive half — and the write on the worker, behind the non-blocking
        // spinner, so a stroke in progress never stalls.
        val document = view.toDocument()
        val pdf = view.pdfSourceFile()
        val images = view.imageSources()
        inBackgroundQuiet({
            val staged = io.encode(document, pdf, saveFormat, uri, images)
            try {
                io.stageOut(staged, uri)
            } finally {
                staged.delete()
            }
        }) { result ->
            // An autosave stays silent on failure: AutoSaveTimer already backs off and retries.
            result.onSuccess { afterSaved(view, uri) }
        }
        return
    }
    // Encode locally first, then push the finished bytes across in one pass: on a slow or flaky
    // remote share, serialising straight down the wire risks leaving a half-written .xopp behind.
    val staged = runCatching {
        io.encode(view.toDocument(), view.pdfSourceFile(), saveFormat, uri, view.imageSources())
    }
        .getOrElse { toast("Save failed: ${it.message}"); return }

    inBackground("Saving ${displayName(uri)}…", { io.stageOut(staged, uri) }) { result ->
        staged.delete()
        result.onFailure { reportSaveFailure(uri, it) }
            .onSuccess { afterSaved(view, uri) }
    }
}

/**
 * The write the save target refused — a provider that won't hand over a writable descriptor, a
 * share that dropped mid-push, a revoked grant.
 *
 * Nothing is lost: the document is still open and still dirty, so it can simply be written
 * somewhere else. But plain Save will keep hitting the same wall (it writes back to the file the
 * tab came from), and finding *Save As* in the menu is the user's problem to solve under a message
 * that just told them their work didn't land. So the report carries the way out with it: the offer
 * opens the very same picker the menu item does.
 */
internal fun MainActivity.reportSaveFailure(uri: Uri, cause: Throwable) {
    Log.w("MainActivity", "save to $uri failed", cause)
    notice.value = ContentNotice(
        message = "Save failed: ${cause.message}",
        lines = emptyList(),
        offer = NoticeOffer("Save As…") { saveLauncher.launch(pendingSaveName) },
    )
}

/** Book-keeping for a document that has just landed on disk at [uri]. */
internal fun MainActivity.afterSaved(view: DrawingSurfaceView, uri: Uri) {
    // Hold the grant so the next plain Save can write back here without asking again.
    io.persist(uri)
    // The document is clean again: stand both autosave timers down and restart the interval.
    autoSave.noteSaved()
    // The file is already written, so this is a report, not a question: the snackbar says it once.
    val lost = if (reportLossesAfterSave) saveWarningsFor(saveFormat) else emptyList()
    notice.value = if (lost.isEmpty()) null else ContentNotice(SAVE_NOTICE, lost)
    reportLossesAfterSave = false
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
internal fun MainActivity.saveActiveTab() {
    // No format was chosen this time, so nothing has warned yet: whatever this save drops is
    // reported once it lands.
    reportLossesAfterSave = true
    val target = tabs.active?.uri?.let(Uri::parse)?.takeIf(io::isWritable)
    if (target != null) saveDocument(target) else saveLauncher.launch(pendingSaveName)
}

/**
 * An autosave has come due (see [com.nexopp.io.AutoSaveTimer]): write the active tab back to the
 * file it came from.
 *
 * A timer must never throw a file picker at someone mid-sentence, so unlike [saveActiveTab] this
 * never writes a file the user didn't choose. For a tab with no writable target — a scratch
 * document that has never been saved — it instead refreshes that tab's **session snapshot** under
 * `filesDir/tabs`, which is the scratch document's only durable copy. Without that, a crash or
 * low-memory kill between tab events loses every stroke since the last one. It is a silent cache
 * refresh: no picker, no toast, and no spinner ([autoSaving] stays false, since nothing is being
 * written to a file the user can see).
 *
 * It also stays quiet about losses: a plain Save reports what the format dropped, but a background
 * save the user didn't ask for repeating that every interval would be nagging, and the same lines
 * still appear the moment they save deliberately.
 */
internal fun MainActivity.autoSaveNow() {
    val target = tabs.active?.uri?.let(Uri::parse)?.takeIf(io::isWritable)
    if (!canAutoSave(
            hasSurface = surface != null,
            blocking = busy.value != null,
            alreadySaving = autoSaving.value,
            hasWritableTarget = true,
        )
    ) {
        return
    }
    if (target == null) {
        // Nowhere to write, but the session copy is worth keeping current. `persist()` queues the
        // gzip write on the pane's writer thread, so the main thread only pays for `toDocument()`.
        snapshotActiveTab()
        persistTabs()
        // Count it as a save so the idle timer stands down and the interval timer re-baselines,
        // instead of the document staying dirty forever and re-firing on the retry-backoff floor.
        autoSave.noteSaved()
        return
    }
    reportLossesAfterSave = false
    saveDocument(target, quiet = true)
}

/** True when the open document references any recording at all. */
internal fun MainActivity.documentHasAudio(view: DrawingSurfaceView): Boolean =
    documentAudioFiles(view.toDocument()).isNotEmpty()

/**
 * Apply a Save As choice: make [format] the sticky format (so later plain Saves reuse it),
 * remember the file name, and open the single-file picker. All three formats write one file.
 *
 * Anything the chosen format would drop has **already** been shown and confirmed by the Save As
 * flow, so the completed save says nothing more (see `docs/architecture.md`, "no per-tab document
 * mode — the editor stays uniform, the save path warns").
 */
internal fun MainActivity.beginSaveAs(filename: String, format: SaveFormat) {
    saveFormat = format
    pendingSaveName = filename
    reportLossesAfterSave = false
    saveLauncher.launch(filename)
}

/**
 * What the open document would lose if it were written as [format] — the pure `exportWarnings`
 * lines, or empty when nothing is lost. Only `.rnote` can drop anything: both `.xopp` containers
 * hold the whole model.
 *
 * @param format The format the user is considering.
 * @return One plain sentence per loss, in a stable order; empty means "say nothing at all".
 */
internal fun MainActivity.saveWarningsFor(format: SaveFormat): List<String> {
    if (format != SaveFormat.RNOTE) return emptyList()
    val view = surface ?: return emptyList()
    return exportWarnings(view.toDocument())
}
