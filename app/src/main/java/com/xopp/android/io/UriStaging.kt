package com.xopp.android.io

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Moves document bytes between a `content://` URI and a local **staging file**.
 *
 * The picker happily lists files on mounted network shares (SSHFS, FTP, WebDAV, cloud providers),
 * and those arrive as ordinary `content://` URIs served by a remote `DocumentsProvider`. The only
 * thing that makes them special is *time*: a read or write can take seconds and can fail halfway.
 *
 * So every document read and write is staged through a local file rather than parsed or serialised
 * straight off the network stream:
 * - reading: [stageIn] copies the remote bytes down once (cancellable, reportable), and the sniff /
 *   parse / PDF-rasterise chain then runs against a local file that can be re-read at will;
 * - writing: the document is serialised locally first, then [stageOut] pushes the finished bytes up
 *   in one pass, so a slow or broken link can never leave a half-written `.xopp` behind mid-encode.
 *
 * Local files pay one extra copy for this, which is cheap and keeps a single code path.
 */
class UriStaging(private val resolver: ContentResolver, private val dir: File) {

    /** Copy the document at [uri] into a fresh local staging file and return it. */
    fun stageIn(uri: Uri, name: String = "staged-in"): File {
        val out = newFile(name)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open $uri" }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** Push a finished local [file] out to [uri], truncating whatever was there. */
    fun stageOut(file: File, uri: Uri) {
        resolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "could not write $uri" }
            file.inputStream().use { it.copyTo(output) }
        }
    }

    /**
     * A private scratch file for a caller that wants to serialise before [stageOut]. Unique per
     * call — see [ScratchDir] for why a shared `<name>.tmp` made overlapping opens clobber each
     * other. The caller deletes it when done.
     */
    fun newFile(name: String): File = scratch.newFile(name)

    private val scratch = ScratchDir(dir)

    /**
     * Hold onto [uri] across restarts, so a restored tab can still write back to the file it came
     * from. Best-effort: providers may refuse the grant (or offer read only), which just means a
     * later Save falls back to asking for a location.
     */
    fun persist(uri: Uri) {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, both) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** True when we still hold a persisted *write* grant on [uri] — i.e. Save can write it in place. */
    fun isWritable(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
}
