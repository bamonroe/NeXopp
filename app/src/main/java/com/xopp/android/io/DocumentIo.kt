package com.xopp.android.io

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.xopp.android.format.FileKind
import com.xopp.android.format.SaveFormat
import com.xopp.android.format.Xopp
import com.xopp.android.format.XoppZip
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.render.ATTACH_DOMAIN
import com.xopp.android.render.PdfMerger
import com.xopp.android.render.TextPdfGenerator
import com.xopp.android.render.documentWithPdfDomain
import java.io.File

/**
 * What a staged file turned out to hold. [DocumentIo.read] sniffs the container and returns one of
 * these; resolving it onto a canvas (rasterising the PDF, loading the document) is the caller's job,
 * so everything here stays free of Android view code and unit-testable.
 */
sealed interface LoadedFile {
    /**
     * A raw PDF, which becomes a fresh annotatable document over its pages. [generated] marks one we
     * typeset ourselves from a text file: it exists only in the cache, so the saved `.xopp` has to
     * carry the bytes with it ([SaveFormat.ZIPPED]) rather than link a path that will be swept.
     */
    data class Pdf(val file: File, val generated: Boolean = false) : LoadedFile

    /**
     * A parsed `.xopp`. [pdf] is the local copy of its background PDF, if it had one we could
     * reach; [missingPdf] says the document referenced a background we could *not* resolve, so
     * those pages will come up blank.
     */
    data class Doc(
        val document: Document,
        val pdf: File?,
        val format: SaveFormat,
        val missingPdf: Boolean = false,
    ) : LoadedFile
}

/**
 * Owns everything between a `content://` URI and a document the canvas can show: the staging area,
 * the two background-PDF stores, and the read/encode/merge steps in between.
 *
 * This is document **I/O policy**, deliberately kept out of the activity, which is left with intent
 * plumbing and the Compose host. Nothing here touches a view or the UI thread — callers run [read],
 * [encode] and [merge] on a worker (see `MainActivity.inBackground`) and apply the result themselves.
 */
class DocumentIo(
    private val resolver: ContentResolver,
    cacheDir: File,
    filesDir: File,
    /**
     * Typesets a plain-text file into a background PDF ([TextImport]). Injected because it needs the
     * bundled fonts (and so an `AssetManager`); when absent, text files are simply unreadable.
     */
    textPdf: TextPdfGenerator? = null,
) {

    /** Staging for document bytes, so slow remote (SSHFS/FTP/cloud) URIs never block the canvas. */
    private val staging = UriStaging(resolver, File(cacheDir, STAGING_DIR))

    /**
     * Where a document's background PDF is kept while it is open — one file per document, never
     * rewritten (see [PdfStore]). Lives in the cache: a tab whose copy the OS reclaims falls back to
     * blank backgrounds, which the caller already handles.
     */
    private val pdfStore = PdfStore(File(cacheDir, PDF_DIR))

    /**
     * Where a *merged* background PDF is kept ([merge]). In `filesDir` rather than the cache, because
     * a plain Save records this path in the document and it has to still resolve on reopen.
     */
    private val joinedPdfStore = PdfStore(File(filesDir, JOINED_PDF_DIR))

    /** Text → PDF, cached in [pdfStore] by content. Null when no font-backed generator was supplied. */
    private val textImport = textPdf?.let { TextImport(pdfStore, it) }

    // --- transfers ------------------------------------------------------------------------------

    /** Copy the bytes at [uri] down into a local scratch file. Slow; run it off the UI thread. */
    fun stageIn(uri: Uri, name: String): File = staging.stageIn(uri, name)

    /** Push a finished local [file] out to [uri]. Slow; run it off the UI thread. */
    fun stageOut(file: File, uri: Uri) = staging.stageOut(file, uri)

    /** Hold onto [uri] across restarts, so a later plain Save can write back to it. */
    fun persist(uri: Uri) = staging.persist(uri)

    /** True when we still hold a persisted *write* grant on [uri]. */
    fun isWritable(uri: Uri): Boolean = staging.isWritable(uri)

    /** The file name behind a `content://` URI, for a tab's label. Falls back to [fallback]. */
    fun displayName(uri: Uri, fallback: String): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: fallback

    // --- reading --------------------------------------------------------------------------------

    /**
     * Read a staged local copy into a [LoadedFile], sniffing the container by its leading bytes and
     * never by extension: the picker is unfiltered and SAF gives us `content://` URIs with no
     * reliable suffix. The verdict also fixes the sticky save format, so a reopened ZIP keeps saving
     * ZIPPED and a gzip `.xopp` keeps saving gzip.
     */
    fun read(staged: File, name: String = staged.name): LoadedFile {
        val kind = staged.inputStream().use { FileKind.sniff(it.buffered()) }
        if (kind == FileKind.PDF) return LoadedFile.Pdf(staged)
        // A text file has no `.xopp` representation of its own, so it is typeset into a PDF and then
        // opened as one — every PDF path downstream (backgrounds, page building, text selection)
        // works unchanged from here (see [TextImport]).
        if (kind == FileKind.TEXT) {
            val generator = textImport ?: error("no text generator configured")
            return LoadedFile.Pdf(generator.pdfFor(staged, name), generated = true)
        }
        return staged.inputStream().use { raw ->
            val input = raw.buffered()
            when (kind) {
                // A ZIP package carries its PDF inside, so the background needs no sibling file.
                FileKind.ZIP -> XoppZip.open(input, pdfStore::newFile)
                    .let { (doc, pdf) -> LoadedFile.Doc(doc, pdf, SaveFormat.ZIPPED) }
                FileKind.GZIP -> linked(Xopp.open(input))
                // Desktop Xournal++ can write plain XML; accept it and save it back compressed.
                FileKind.XML -> linked(Xopp.parseXml(input.reader(Charsets.UTF_8).readText()))
                else -> error("unrecognised file type")
            }
        }
    }

    /**
     * Resolve the PDF background a gzip/XML document links to, so a saved project reopens with its
     * background intact. Only the first PDF-backed page carries the reference (import convention).
     */
    private fun linked(doc: Document): LoadedFile.Doc {
        val ref = doc.pages.firstNotNullOfOrNull { (it.background as? Background.Pdf)?.filename }
        val pdf = ref?.let(::resolvePdfBackground)
        return LoadedFile.Doc(doc, pdf, SaveFormat.ORIGINAL, missingPdf = ref != null && pdf == null)
    }

    /**
     * Resolve a `pdf` background reference back to a local file we can rasterise. The reference is
     * either a `content://` URI (what Android records for `domain="absolute"` — a picked PDF has no
     * filesystem path) or an on-disk path (what desktop Xournal++ records). Copies the bytes into the
     * store; returns null when the source can't be reached (e.g. a Linux path on Android), so the
     * caller falls back to blank pages.
     */
    private fun resolvePdfBackground(ref: String): File? = runCatching {
        val stream = when {
            ref.startsWith("content://") -> resolver.openInputStream(Uri.parse(ref))
            else -> File(ref).takeIf(File::exists)?.inputStream()
        } ?: return@runCatching null
        copyIntoStore(stream)
    }.getOrNull()

    private fun copyIntoStore(stream: java.io.InputStream): File {
        val out = pdfStore.newFile()
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    // --- PDFs -----------------------------------------------------------------------------------

    /** Take a local PDF into the store as this document's own never-rewritten copy. */
    fun adoptPdf(source: File): File = copyIntoStore(source.inputStream())

    /**
     * Merge [existing] and [incoming] into one joined background PDF — the only shape a `.xopp` can
     * represent, since it holds a single background reference. The joined file lands in `filesDir`
     * (not the cache) so the link a plain Save records survives; successive appends get fresh names,
     * so a merge never writes the file it is reading.
     */
    fun merge(existing: File, incoming: File): File =
        PdfMerger.join(existing, incoming, joinedPdfStore.newFile())

    /**
     * Drop the background PDFs nothing refers to any more. Each open allocates its own file, so
     * without this sweep the folders would grow with every document opened.
     */
    fun prune(live: Collection<String?>) {
        pdfStore.prune(live)
        joinedPdfStore.prune(live)
    }

    // --- writing --------------------------------------------------------------------------------

    /**
     * Serialise [document] into a local scratch file in [format], returning it for [stageOut]. On a
     * slow or flaky remote share, serialising straight down the wire risks leaving a half-written
     * `.xopp` behind, so the finished bytes are always pushed across in one later pass.
     *
     * - [SaveFormat.ORIGINAL] — gzip XML, PDF background left linked by path/URI (interchange-safe).
     * - [SaveFormat.ZIPPED] — a ZIP package with [pdf] embedded (`domain="attach"`, `bg.pdf`).
     */
    fun encode(document: Document, pdf: File?, format: SaveFormat): File {
        val out = staging.newFile("save")
        out.outputStream().use { output ->
            when (format) {
                SaveFormat.ORIGINAL -> Xopp.save(document, output)
                SaveFormat.ZIPPED -> XoppZip.save(
                    if (pdf != null) documentWithPdfDomain(document, ATTACH_DOMAIN) else document,
                    pdf,
                    output,
                )
            }
        }
        return out
    }

    private companion object {
        /** Cache subfolder holding the local staging copies of documents in transit (see [UriStaging]). */
        const val STAGING_DIR = "staging"

        /** Cache subfolder holding one background PDF per open document (see [PdfStore]). */
        const val PDF_DIR = "pdf"

        /** `filesDir` subfolder holding merged background PDFs, which saved documents link to. */
        const val JOINED_PDF_DIR = "pdf-joined"
    }
}
