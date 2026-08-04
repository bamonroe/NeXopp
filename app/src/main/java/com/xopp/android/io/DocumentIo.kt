package com.xopp.android.io

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xopp.android.format.FileKind
import com.xopp.android.format.SaveFormat
import com.xopp.android.format.Xopp
import com.xopp.android.format.XoppZip
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.render.ABSOLUTE_DOMAIN
import com.xopp.android.render.ATTACH_DOMAIN
import com.xopp.android.render.PdfMerger
import com.xopp.android.render.TextPdfGenerator
import com.xopp.android.render.documentWithPdfDomain
import com.xopp.android.render.documentWithPdfReference
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
     * A raster image ([FileKind.IMAGE]), staged locally. It becomes a one-page document with the
     * picture as that page's pixmap background — the only shape the `.xopp` format has for an image
     * — so, like a generated PDF, the caller resolves it rather than parsing anything here.
     */
    data class Image(val file: File, val name: String) : LoadedFile

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
 * The user-set bounds on what document I/O may spend on disk and on a single import (Settings →
 * Storage). Kept as a plain value here rather than reaching for `AppSettings`, so the I/O layer stays
 * independent of the UI one.
 */
data class StorageLimits(
    /** Largest plain-text file that may be typeset into a background PDF (see [TextImport]). */
    val textImportBytes: Long = Long.MAX_VALUE,
    /** Byte budget for the generated/background PDF cache (see [PdfStore.prune]). */
    val pdfCacheBytes: Long = PdfStore.UNLIMITED,
)

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

    /**
     * The two storage caps the user sets in Settings → Storage, pushed in by the activity whenever
     * settings change. Held as a field rather than a constructor argument because this object
     * outlives any one settings value; unset, both are effectively unlimited.
     */
    var limits: StorageLimits = StorageLimits()

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
    fun read(staged: File, name: String = staged.name, source: Uri? = null): LoadedFile {
        val kind = staged.inputStream().use { FileKind.sniff(it.buffered()) }
        if (kind == FileKind.PDF) return LoadedFile.Pdf(staged)
        // An image is likewise not a document to parse: it is handed back for the caller to hang on
        // a page as a pixmap background (see [LoadedFile.Image]).
        if (kind == FileKind.IMAGE) return LoadedFile.Image(staged, name)
        // A text file has no `.xopp` representation of its own, so it is typeset into a PDF and then
        // opened as one — every PDF path downstream (backgrounds, page building, text selection)
        // works unchanged from here (see [TextImport]).
        if (kind == FileKind.TEXT) {
            val generator = textImport ?: error("no text generator configured")
            return LoadedFile.Pdf(
                generator.pdfFor(staged, name, limits.textImportBytes),
                generated = true,
            )
        }
        return staged.inputStream().use { raw ->
            val input = raw.buffered()
            when (kind) {
                // A ZIP package carries its PDF inside, so the background needs no sibling file.
                FileKind.ZIP -> XoppZip.open(input, pdfStore::newFile)
                    .let { (doc, pdf) -> LoadedFile.Doc(doc, pdf, SaveFormat.ZIPPED) }
                FileKind.GZIP -> linked(Xopp.open(input), name, source)
                // Desktop Xournal++ can write plain XML; accept it and save it back compressed.
                FileKind.XML -> linked(Xopp.parseXml(input.reader(Charsets.UTF_8).readText()), name, source)
                else -> error("unrecognised file type")
            }
        }
    }

    /**
     * Resolve the PDF background a gzip/XML document links to, so a saved project reopens with its
     * background intact. Only the first PDF-backed page carries the reference (import convention).
     */
    private fun linked(doc: Document, name: String, source: Uri?): LoadedFile.Doc {
        val bg = doc.pages.firstNotNullOfOrNull { it.background as? Background.Pdf }
        val ref = bg?.filename
        val pdf = ref?.let { resolvePdfBackground(it, bg.domain, name, source) }
        return LoadedFile.Doc(doc, pdf, SaveFormat.ORIGINAL, missingPdf = ref != null && pdf == null)
    }

    /**
     * Resolve a `pdf` background reference back to a local file we can rasterise, covering every
     * shape desktop Xournal++ and this app can write (see [PdfReference]):
     *
     * - a `content://` URI — what Android records for `domain="absolute"` when a picked PDF has no
     *   filesystem path of its own;
     * - an absolute on-disk path — what desktop Xournal++ records for a PDF outside the folder;
     * - a **relative** path under `domain="absolute"` — resolved against the folder holding the
     *   `.xopp` being opened, which is the portable form and so the one we prefer to write;
     * - `domain="attach"` on a non-zip document — the `<name>.xopp.<filename>` sibling.
     *
     * Copies the bytes into the store; returns null when the source can't be reached (a Linux path
     * on Android, a provider we hold no grant on), so the caller falls back to blank pages.
     */
    private fun resolvePdfBackground(ref: String, domain: String?, name: String, source: Uri?): File? =
        runCatching {
            val stream = when {
                domain == ATTACH_DOMAIN ->
                    openSibling(source, PdfReference.attachSiblingName(name, ref))
                ref.startsWith("content://") -> resolver.openInputStream(Uri.parse(ref))
                PdfReference.isRelative(ref) -> openSibling(source, ref)
                else -> File(ref).takeIf(File::exists)?.inputStream()
            } ?: return@runCatching null
            copyIntoStore(stream)
        }.getOrNull()

    /**
     * Open the file [ref] names *relative to the document at [source]* — the one lookup a relative or
     * attached reference needs. A `file://` source relativises on the filesystem; a `content://` one
     * relativises on its SAF document id, which only works when the provider's ids are decomposable
     * paths (`primary:Docs/notes.xopp`). Anything else — an opaque Downloads id, no source at all —
     * gives null, and the caller falls back to blank pages with the usual "not found" note.
     */
    private fun openSibling(source: Uri?, ref: String): java.io.InputStream? {
        val uri = source ?: return null
        return when (uri.scheme) {
            "file" -> uri.path
                ?.let { PdfReference.resolveRelative(it, ref) }
                ?.let { File("/$it") }
                ?.takeIf(File::exists)
                ?.inputStream()
            "content" -> siblingUri(uri, ref)?.let { runCatching { resolver.openInputStream(it) }.getOrNull() }
            else -> null
        }
    }

    /** The SAF URI of [ref] resolved beside the document at [source], or null if its ids aren't paths. */
    private fun siblingUri(source: Uri, ref: String): Uri? = runCatching {
        val docId = DocumentsContract.getDocumentId(source)
        val siblingId = PdfReference.resolveRelativeDocumentId(docId, ref) ?: return null
        if (DocumentsContract.isTreeUri(source)) DocumentsContract.buildDocumentUriUsingTree(source, siblingId)
        else DocumentsContract.buildDocumentUri(source.authority, siblingId)
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
     *
     * The cache store is additionally held to [StorageLimits.pdfCacheBytes], so a session that opens
     * many large PDFs can't sit on hundreds of megabytes of cache between sweeps. The *joined* store
     * is not: a saved `.xopp` links those by path, so deleting one would break a document on disk.
     */
    fun prune(live: Collection<String?>) {
        pdfStore.prune(live, limits.pdfCacheBytes)
        joinedPdfStore.prune(live)
    }

    // --- writing --------------------------------------------------------------------------------

    /**
     * Serialise [document] into a local scratch file in [format], returning it for [stageOut]. On a
     * slow or flaky remote share, serialising straight down the wire risks leaving a half-written
     * `.xopp` behind, so the finished bytes are always pushed across in one later pass.
     *
     * - [SaveFormat.ORIGINAL] — gzip XML, PDF background linked by path/URI, made **relative** to
     *   [target] whenever the PDF sits in the same folder ([portableReference]).
     * - [SaveFormat.ZIPPED] — a ZIP package with [pdf] embedded (`domain="attach"`, `bg.pdf`).
     */
    fun encode(document: Document, pdf: File?, format: SaveFormat, target: Uri? = null): File {
        val out = staging.newFile("save")
        out.outputStream().use { output ->
            when (format) {
                SaveFormat.ORIGINAL -> Xopp.save(portableReference(document, target), output)
                SaveFormat.ZIPPED -> XoppZip.save(
                    if (pdf != null) documentWithPdfDomain(document, ATTACH_DOMAIN) else document,
                    pdf,
                    output,
                )
            }
        }
        return out
    }

    /**
     * Make the document's PDF background reference as portable as it can be for a save to [target]:
     * **relative to the document's own folder** whenever the PDF lives there, since a relative
     * reference is the only form that survives the file being copied to another machine — desktop
     * Xournal++ resolves it against the `.xopp`, while a `content://` URI means nothing off-device
     * and an absolute path means nothing off this filesystem.
     *
     * A reference that is already relative is left exactly as it was read, so a desktop-authored
     * document round-trips unchanged. Anything we can't relativise (different folder, opaque provider
     * id, unknown destination) keeps its absolute reference rather than becoming a broken relative
     * one. Returns a copy; the working document is untouched.
     */
    private fun portableReference(document: Document, target: Uri?): Document {
        val bg = document.pages.firstNotNullOfOrNull { it.background as? Background.Pdf } ?: return document
        val ref = bg.filename?.takeIf { it.isNotEmpty() } ?: return document
        if (bg.domain == ATTACH_DOMAIN || PdfReference.isRelative(ref)) return document
        val relative = relativeTo(target ?: return document, ref) ?: return document
        return documentWithPdfReference(document, relative, ABSOLUTE_DOMAIN)
    }

    /** The path of the PDF at [ref] relative to a document saved at [target], if it sits below it. */
    private fun relativeTo(target: Uri, ref: String): String? = runCatching {
        when {
            // Both sides SAF: compare document ids, which for path-shaped providers relativise.
            ref.startsWith("content://") && target.scheme == "content" -> PdfReference.relativeDocumentId(
                DocumentsContract.getDocumentId(target),
                DocumentsContract.getDocumentId(Uri.parse(ref)),
            )
            // Both sides plain filesystem paths (a `file://` destination, a desktop-style reference).
            !ref.contains("://") && target.scheme == "file" ->
                target.path?.let { PdfReference.relativeReference(it, ref) }
            else -> null
        }
    }.getOrNull()

    private companion object {
        /** Cache subfolder holding the local staging copies of documents in transit (see [UriStaging]). */
        const val STAGING_DIR = "staging"

        /** Cache subfolder holding one background PDF per open document (see [PdfStore]). */
        const val PDF_DIR = "pdf"

        /** `filesDir` subfolder holding merged background PDFs, which saved documents link to. */
        const val JOINED_PDF_DIR = "pdf-joined"
    }
}
