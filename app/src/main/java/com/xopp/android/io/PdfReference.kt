package com.xopp.android.io

/**
 * How a `.xopp` names its background PDF on disk, and how those names are turned into something we
 * can actually open — the string half of the problem, kept pure so it unit-tests without Android.
 *
 * Desktop Xournal++ has no `relative` domain: a relative reference rides under `domain="absolute"`
 * and is resolved against the directory of the `.xopp` itself (`LoadHandler::getAbsoluteFilepath`).
 * A relative reference is therefore the *portable* one — it survives being copied between machines,
 * where an absolute Linux path or an Android `content://` URI does not. That is why the writer side
 * ([relativeReference]) prefers it whenever the PDF and the document share a folder.
 */
object PdfReference {

    /**
     * True when [ref] is a relative reference — no URI scheme and no leading slash — and so has to be
     * resolved against the directory holding the `.xopp`.
     */
    fun isRelative(ref: String): Boolean =
        ref.isNotEmpty() && !ref.startsWith('/') && !ref.contains("://")

    /**
     * The sibling file a non-zip `domain="attach"` reference points at: desktop resolves it as
     * `<path to the .xopp> + "." + filename`, so `notes.xopp` pairs with `notes.xopp.bg.pdf`.
     */
    fun attachSiblingName(xoppName: String, filename: String): String = "$xoppName.$filename"

    /**
     * Resolve relative [ref] against the directory of [xoppPath], returning a path in the same space
     * as [xoppPath] (a filesystem path, or the path half of a SAF document id — both are `/`-joined).
     * `.` and `..` segments are folded away; a `..` that would climb above the root gives null rather
     * than an escaping path.
     */
    fun resolveRelative(xoppPath: String, ref: String): String? {
        val dir = xoppPath.substringBeforeLast('/', "")
        val segments = ArrayDeque<String>()
        if (dir.isNotEmpty()) segments.addAll(dir.split('/').filter(String::isNotEmpty))
        for (segment in ref.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * The portable reference to write for a PDF at [pdfPath] in a document being saved to
     * [xoppPath] — the PDF's path relative to the document's own directory, or null when it isn't
     * below that directory (a different volume, an opaque provider id, a Downloads-style URI).
     * Callers fall back to the absolute reference in that case: an unresolvable relative path would
     * be worse than an absolute one that at least works on this device.
     */
    fun relativeReference(xoppPath: String, pdfPath: String): String? {
        val dir = xoppPath.substringBeforeLast('/', "")
        if (dir.isEmpty()) return pdfPath.takeIf { it.isNotEmpty() && !it.contains('/') }
        if (!pdfPath.startsWith("$dir/")) return null
        return pdfPath.removePrefix("$dir/").takeIf(String::isNotEmpty)
    }

    /**
     * Split a SAF document id (`primary:Docs/notes.xopp`) into its volume root and its path, so the
     * path half can be compared or relativised like any other. An id with no `:` is all path; ids
     * from different roots never relativise against each other.
     */
    fun splitDocumentId(docId: String): Pair<String, String> {
        val colon = docId.indexOf(':')
        return if (colon < 0) "" to docId else docId.substring(0, colon) to docId.substring(colon + 1)
    }

    /** Re-join what [splitDocumentId] took apart. */
    fun joinDocumentId(root: String, path: String): String =
        if (root.isEmpty()) path else "$root:$path"

    /**
     * [relativeReference] over two SAF document ids: the PDF's id relative to the document's id, or
     * null when they don't share a volume root or the PDF isn't below the document's folder.
     */
    fun relativeDocumentId(xoppDocId: String, pdfDocId: String): String? {
        val (xoppRoot, xoppPath) = splitDocumentId(xoppDocId)
        val (pdfRoot, pdfPath) = splitDocumentId(pdfDocId)
        if (xoppRoot != pdfRoot) return null
        return relativeReference(xoppPath, pdfPath)
    }

    /**
     * [resolveRelative] over a SAF document id: the id of the sibling [ref] names, relative to the
     * `.xopp` at [xoppDocId]. Null when the reference climbs out of the volume.
     */
    fun resolveRelativeDocumentId(xoppDocId: String, ref: String): String? {
        val (root, path) = splitDocumentId(xoppDocId)
        val resolved = resolveRelative(path, ref) ?: return null
        return joinDocumentId(root, resolved)
    }
}
