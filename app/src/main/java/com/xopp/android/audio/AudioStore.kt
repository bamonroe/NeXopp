package com.xopp.android.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xopp.android.io.UriStaging
import java.io.File

/**
 * Where recordings live while the app is running, and how they get next to the `.xopp` on disk.
 *
 * A `.xopp` never carries its audio: `fn` names a **sidecar** file that has to sit beside the
 * document for playback to work. Android's Storage Access Framework grants access to the single
 * document the user picked, not to its folder, so we can't write a sibling from a `CreateDocument`
 * URI alone. Instead:
 *
 *  - recordings are captured into an app-private [dir], which always works and needs no permission;
 *  - the user nominates an **audio folder** once (a persisted `OpenDocumentTree` grant) — normally
 *    the folder their `.xopp` files live in — and that folder is the sidecars' home on disk;
 *  - [exportTo] pushes new recordings out to it after a save, and [importFrom] pulls referenced
 *    sidecars in when a document is opened, so a file authored on the desktop replays here.
 *
 * With no folder nominated, recording and playback still work for the current session — only the
 * hand-off to and from the desktop is missing, and the caller surfaces that.
 */
class AudioStore(context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val staging = UriStaging(resolver, File(context.cacheDir, "audio-staging"))

    /** The app-private folder every recording is captured into and played back from. */
    val dir: File = File(context.filesDir, "audio").apply { mkdirs() }

    /** The local file a `fn` reference resolves to (whether or not it exists yet). */
    fun local(filename: String): File = File(dir, sanitize(filename))

    /** Local sidecars whose names appear in [names] — the subset that can actually be played. */
    fun present(names: Set<String>): Set<String> = names.filterTo(HashSet()) { local(it).isFile }

    /**
     * Copy every sidecar in [names] that the nominated [folder] holds into [dir], overwriting stale
     * local copies. Returns the names successfully pulled in. Best-effort throughout: a revoked
     * grant or a missing file just means that recording won't play.
     */
    fun importFrom(folder: Uri, names: Set<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val children = childrenOf(folder)
        val pulled = LinkedHashSet<String>()
        for (name in names) {
            val src = children[sanitize(name)] ?: continue
            val ok = runCatching {
                staging.readBytes(src).let { local(name).writeBytes(it) }
            }.isSuccess
            if (ok) pulled += name
        }
        return pulled
    }

    /**
     * Copy every local sidecar in [names] out to [folder], replacing an existing file of the same
     * name. Returns the names successfully written.
     */
    fun exportTo(folder: Uri, names: Set<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val children = childrenOf(folder)
        val pushed = LinkedHashSet<String>()
        for (name in names) {
            val source = local(name)
            if (!source.isFile) continue
            val clean = sanitize(name)
            val target = children[clean] ?: createChild(folder, clean) ?: continue
            val ok = runCatching {
                staging.writeTo(target) { output -> source.inputStream().use { it.copyTo(output) } }
            }.isSuccess
            if (ok) pushed += name
        }
        return pushed
    }

    /** The files directly inside a tree, by display name. Empty when the grant no longer resolves. */
    private fun childrenOf(folder: Uri): Map<String, Uri> = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(folder)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val out = HashMap<String, Uri>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[name] = DocumentsContract.buildDocumentUriUsingTree(folder, id)
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** Create an empty WAV document called [name] inside the tree, or null if the provider refuses. */
    private fun createChild(folder: Uri, name: String): Uri? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            folder,
            DocumentsContract.getTreeDocumentId(folder),
        )
        DocumentsContract.createDocument(resolver, parent, WAV_MIME, name)
    }.getOrNull()

    private companion object {
        /** MIME type for a WAV file, used when creating sidecar documents in the SAF tree. */
        const val WAV_MIME = "audio/x-wav"

        /**
         * Reduce a `fn` to a bare file name. Desktop Xournal++ writes a plain name, but a
         * hand-edited (or hostile) document could put a path there, and we must never let it escape
         * the audio folder.
         */
        fun sanitize(filename: String): String =
            filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "audio.wav" }
    }
}
