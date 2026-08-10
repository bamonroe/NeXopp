package com.nexopp.io

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.render.ABSOLUTE_DOMAIN
import com.nexopp.render.ATTACH_DOMAIN
import com.nexopp.render.documentWithPdfReference
import java.io.File

/**
 * Resolves and rewrites document background references — both PDF and pixmap — for import and save.
 *
 * On **import**, it resolves the references a `.xopp` carries (absolute paths, `content://` URIs,
 * relative paths, attached siblings) into local cached copies the renderer can decode from while
 * the document is open. On **save**, it makes references as portable as possible by relativising
 * them to the document's own folder, so a file copied to another machine or filesystem keeps its
 * backgrounds intact.
 *
 * This class owns the Uri/path arithmetic shared between the two directions; [DocumentIo] owns the
 * higher-level policy (staging, stores, encode/decode).
 */
internal class DocumentReferenceResolver(
    private val resolver: ContentResolver,
    private val pdfStore: PdfStore,
    private val imageStore: ImageStore,
) {

    /**
     * Resolve the PDF background a gzip/XML document links to, so a saved project reopens with its
     * background intact. Only the first PDF-backed page carries the reference (import convention).
     */
    fun resolvePdfBackground(doc: Document, name: String, source: Uri?): File? {
        val bg = doc.pages.firstNotNullOfOrNull { it.background as? Background.Pdf } ?: return null
        val ref = bg.filename ?: return null
        return openReference(ref, bg.domain, name, source)?.let(::copyIntoStore)
    }

    /**
     * Copy the picture behind every `pixmap` background into [imageStore], keyed by the reference
     * the document names it under, so the renderer decodes from a stable local file for as long as
     * the document is open. References that resolve to the same string are copied once.
     *
     * The reference shapes are the same set [resolvePdfBackground] handles — a `content://` URI, an
     * absolute path, a relative path beside the `.xopp`, or `domain="attach"`'s
     * `<name>.xopp.<filename>` sibling — because desktop Xournal++ resolves both background kinds
     * through the same `getAbsoluteFilepath`. Unreachable pictures are simply absent from the map;
     * those pages come up with a blank background.
     */
    fun resolvePixmapBackgrounds(doc: Document, name: String, source: Uri?): Map<String, File> {
        val resolved = LinkedHashMap<String, File>()
        doc.pages.forEach { page ->
            val bg = page.background as? Background.Pixmap ?: return@forEach
            val ref = bg.filename.takeIf(String::isNotEmpty) ?: return@forEach
            if (ref in resolved) return@forEach
            openReference(ref, bg.domain, name, source)?.let { resolved[ref] = imageStore.copyIn(it) }
        }
        return resolved
    }

    /** Every distinct `filename` a `pixmap` background in [doc] names, in page order. */
    fun pixmapReferences(doc: Document): List<String> = doc.pages
        .mapNotNull { (it.background as? Background.Pixmap)?.filename?.takeIf(String::isNotEmpty) }
        .distinct()

    /**
     * Open the bytes a background reference names, in every shape a `.xopp` can carry one (the four
     * cases listed on [resolvePdfBackground]). Shared by the PDF and pixmap paths, because desktop
     * Xournal++ resolves both through one `getAbsoluteFilepath`. Null when it can't be reached.
     */
    private fun openReference(
        ref: String,
        domain: String?,
        name: String,
        source: Uri?,
    ): java.io.InputStream? = runCatching {
        when {
            domain == ATTACH_DOMAIN -> openSibling(source, PdfReference.attachSiblingName(name, ref))
            ref.startsWith("content://") -> resolver.openInputStream(Uri.parse(ref))
            PdfReference.isRelative(ref) -> openSibling(source, ref)
            else -> File(ref).takeIf(File::exists)?.inputStream()
        }
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

    // --- Portable references (save) ------------------------------------------------------------

    /**
     * Make the document's PDF and pixmap background references as portable as they can be for a save
     * to [target]: **relative to the document's own folder** whenever the background lives there,
     * since a relative reference is the only form that survives the file being copied to another
     * machine — desktop Xournal++ resolves it against the `.xopp`, while a `content://` URI means
     * nothing off-device and an absolute path means nothing off this filesystem.
     *
     * A reference that is already relative is left exactly as it was read, so a desktop-authored
     * document round-trips unchanged. Anything we can't relativise (different folder, opaque provider
     * id, unknown destination) keeps its absolute reference rather than becoming a broken relative
     * one. Returns a copy; the working document is untouched.
     */
    fun portableReference(document: Document, target: Uri?): Document =
        portablePixmapReferences(portablePdfReference(document, target), target)

    /**
     * Relativise the PDF background reference if it sits in the same folder as [target]. Already
     * relative or attached references are left unchanged.
     */
    private fun portablePdfReference(document: Document, target: Uri?): Document {
        val bg = document.pages.firstNotNullOfOrNull { it.background as? Background.Pdf } ?: return document
        val ref = bg.filename?.takeIf { it.isNotEmpty() } ?: return document
        if (bg.domain == ATTACH_DOMAIN || PdfReference.isRelative(ref)) return document
        val relative = relativeTo(target ?: return document, ref) ?: return document
        return documentWithPdfReference(document, relative, ABSOLUTE_DOMAIN)
    }

    /**
     * Relativise each pixmap background reference that sits in the same folder as [target]. Unlike
     * the PDF there can be one per page, so every one is considered; an unrelativisable reference
     * keeps its absolute form rather than becoming a relative path that resolves to nothing.
     */
    private fun portablePixmapReferences(document: Document, target: Uri?): Document {
        if (target == null) return document
        if (document.pages.none { it.background is Background.Pixmap }) return document
        val pages = document.pages.map { page ->
            val bg = page.background as? Background.Pixmap ?: return@map page
            val ref = bg.filename.takeIf(String::isNotEmpty) ?: return@map page
            if (bg.domain == ATTACH_DOMAIN || PdfReference.isRelative(ref)) return@map page
            val relative = relativeTo(target, ref) ?: return@map page
            page.copy(background = bg.copy(domain = ABSOLUTE_DOMAIN, filename = relative))
        }
        return document.copy(pages = pages)
    }

    /** The path of the file at [ref] relative to a document saved at [target], if it sits below it. */
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
}
