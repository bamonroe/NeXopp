package com.nexopp.io

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.nexopp.format.FileKind
import com.nexopp.format.SaveFormat
import com.nexopp.format.Xopp
import com.nexopp.format.XoppZip
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.rnote.RnoteContainer
import com.nexopp.format.rnote.RnoteSnapshot
import com.nexopp.format.rnote.readDocument
import com.nexopp.render.ATTACH_DOMAIN
import com.nexopp.render.PdfMerger
import com.nexopp.render.TextPdfGenerator
import com.nexopp.render.documentWithPdfDomain
import com.nexopp.render.documentWithPixmapAttachments
import java.io.File
import java.io.InputStream

/**
 * What a staged file turned out to hold. [DocumentIo.read] sniffs the container and returns one of
 * these; resolving it onto a canvas (rasterising the PDF, loading the document) is the caller's job,
 * so everything here stays free of Android view code and unit-testable.
 */
sealed interface LoadedFile {
    /**
     * A raw PDF, which becomes a fresh annotatable document over its pages. [generated] marks one we
     * typeset ourselves from a text file: it exists only in the cache, so the saved `.xopp` has to
     * carry the bytes with it ([SaveFormat.XOPP_ZIP]) rather than link a path that will be swept.
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
        /**
         * The local copy of each `pixmap` background's picture, keyed by the reference the document
         * carries. The document's own references are left as they were read (so a plain Save writes
         * them back unchanged) and the renderer decodes through this side table instead.
         */
        val images: Map<String, File> = emptyMap(),
        /** True when some pixmap background named a picture we could not reach. */
        val missingImage: Boolean = false,
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
    /** Byte budget for the pixmap-background copies (see [ImageStore.prune]). */
    val imageCacheBytes: Long = ImageStore.UNLIMITED,
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

    /**
     * Where the pictures behind `pixmap` backgrounds are kept while a document is open (see
     * [ImageStore]). In the cache like [pdfStore]: a copy the OS reclaims costs a blank page
     * background, which the renderer already handles, and the document's own reference is untouched.
     */
    private val imageStore = ImageStore(File(cacheDir, IMAGE_DIR))

    /** Text → PDF, cached in [pdfStore] by content. Null when no font-backed generator was supplied. */
    private val textImport = textPdf?.let { TextImport(pdfStore, it) }

    /** Reference resolution for import and save — the Uri/path arithmetic extracted into its own class. */
    private val refs = DocumentReferenceResolver(resolver, pdfStore, imageStore)

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
     * as a ZIP package and a gzip `.xopp` keeps saving gzip.
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
                // A ZIP package's pictures ride inside it too, keyed by the entry name an attached
                // `pixmap` background points at, so they resolve without touching the filesystem.
                FileKind.ZIP -> XoppZip.open(input, pdfStore::newFile, imageStore::newFile)
                    .let { (doc, pdf, images) ->
                        LoadedFile.Doc(
                            doc,
                            pdf,
                            SaveFormat.XOPP_ZIP,
                            images = images,
                            missingImage = refs.pixmapReferences(doc).any { it !in images },
                        )
                    }
                FileKind.GZIP -> linked(Xopp.open(input), name, source)
                // Desktop Xournal++ can write plain XML; accept it and save it back compressed.
                FileKind.XML -> linked(Xopp.parseXml(input.reader(Charsets.UTF_8).readText()), name, source)
                // An Rnote file: gzip + JSON, converted into our document model. It has no linked
                // PDF and no `pixmap` backgrounds — a `bitmapimage` becomes an `<image>` element
                // carrying its own bytes — so there is nothing to resolve on the side.
                FileKind.RNOTE -> readRnote(input)
                else -> error("unrecognised file type")
            }
        }
    }

    /**
     * Resolve the PDF background a gzip/XML document links to, so a saved project reopens with its
     * background intact. Only the first PDF-backed page carries the reference (import convention).
     */
    private fun linked(doc: Document, name: String, source: Uri?): LoadedFile.Doc {
        val pdf = refs.resolvePdfBackground(doc, name, source)
        val references = refs.pixmapReferences(doc)
        val images = refs.resolvePixmapBackgrounds(doc, name, source)
        val bg = doc.pages.firstNotNullOfOrNull { it.background as? Background.Pdf }
        val ref = bg?.filename
        return LoadedFile.Doc(
            doc,
            pdf,
            SaveFormat.XOPP_GZIP,
            missingPdf = ref != null && pdf == null,
            images = images,
            missingImage = references.any { it !in images },
        )
    }

    // --- PDFs -----------------------------------------------------------------------------------

    /** Take a local PDF into the store as this document's own never-rewritten copy. */
    fun adoptPdf(source: File): File {
        val out = pdfStore.newFile()
        source.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /**
     * Take a local picture into the image store, so a freshly imported image keeps rendering (and
     * can be bundled into a ZIP save) after the staging copy is swept and whatever grant we opened
     * it under expires. The document still references the original.
     */
    fun adoptImage(source: File): File = imageStore.copyIn(source.inputStream())

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
     * The cache store is additionally held to [StorageLimits.pdfCacheBytes], and the image store to
     * [StorageLimits.imageCacheBytes], so a session that opens many large PDFs or pictures can't sit
     * on hundreds of megabytes of cache between sweeps. The *joined* store is not: a saved `.xopp`
     * links those by path, so deleting one would break a document on disk.
     */
    fun prune(live: Collection<String?>, liveImages: Collection<String?> = emptyList()) {
        pdfStore.prune(live, limits.pdfCacheBytes)
        joinedPdfStore.prune(live)
        imageStore.prune(liveImages, limits.imageCacheBytes)
    }

    // --- writing --------------------------------------------------------------------------------

    /**
     * Serialise [document] into a local scratch file in [format], returning it for [stageOut]. On a
     * slow or flaky remote share, serialising straight down the wire risks leaving a half-written
     * `.xopp` behind, so the finished bytes are always pushed across in one later pass.
     *
     * - [SaveFormat.XOPP_GZIP] — gzip XML, PDF background linked by path/URI, made **relative** to
     *   [target] whenever the PDF sits in the same folder ([portableReference]).
     * - [SaveFormat.XOPP_ZIP] — a ZIP package with [pdf] embedded (`domain="attach"`, `bg.pdf`).
     */
    fun encode(
        document: Document,
        pdf: File?,
        format: SaveFormat,
        target: Uri? = null,
        images: Map<String, File> = emptyMap(),
    ): File {
        val out = staging.newFile("save")
        out.outputStream().use { output ->
            when (format) {
                SaveFormat.XOPP_GZIP -> Xopp.save(refs.portableReference(document, target), output)
                SaveFormat.XOPP_ZIP -> {
                    // Pictures ride inside the package as numbered entries, so a ZIP save is
                    // self-contained for pixmap backgrounds exactly as it already is for the PDF.
                    val bundled = documentWithPixmapAttachments(document) { images[it] }
                    XoppZip.save(
                        if (pdf != null) documentWithPdfDomain(bundled.document, ATTACH_DOMAIN)
                        else bundled.document,
                        pdf,
                        output,
                        bundled.entries,
                    )
                }
                // The writer exists but nothing routes to it yet — that's its own task.
                SaveFormat.RNOTE -> throw UnsupportedOperationException("Rnote save not wired yet")
            }
        }
        return out
    }

    private companion object {
        /** Cache subfolder holding the local staging copies of documents in transit (see [UriStaging]). */
        const val STAGING_DIR = "staging"

        /** Cache subfolder holding one background PDF per open document (see [PdfStore]). */
        const val PDF_DIR = "pdf"

        /** Cache subfolder holding the local copies of pixmap background pictures (see [ImageStore]). */
        const val IMAGE_DIR = "images"

        /** `filesDir` subfolder holding merged background PDFs, which saved documents link to. */
        const val JOINED_PDF_DIR = "pdf-joined"
    }
}

/**
 * Read a `.rnote` stream into a document, sticky at [SaveFormat.RNOTE] so an opened Rnote file
 * keeps saving as one. Split out of [DocumentIo.read] so it is reachable without a
 * `ContentResolver`: an Rnote file resolves nothing off the filesystem, unlike a `.xopp`.
 *
 * There is no PDF background in the format and no `pixmap` background either — a `bitmapimage`
 * becomes an `<image>` element carrying its own bytes — so both side tables stay empty.
 *
 * @param input A stream positioned at the start of a `.rnote` file. Not closed.
 * @return The converted document.
 */
internal fun readRnote(input: InputStream): LoadedFile.Doc = LoadedFile.Doc(
    document = readDocument(RnoteSnapshot.parse(RnoteContainer.open(input).snapshot)).document,
    pdf = null,
    format = SaveFormat.RNOTE,
)
