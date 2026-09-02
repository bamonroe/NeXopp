package com.nexopp.io

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

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
    fun stageOut(file: File, uri: Uri) = writeTo(uri) { output ->
        file.inputStream().use { it.copyTo(output) }
    }

    /**
     * Read the bytes at [uri] into a [ByteArray]. Use this for small documents (images, audio);
     * larger content should use [stageIn] to avoid holding it all in memory.
     */
    fun readBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open $uri" }
            input.readBytes()
        }

    /**
     * Write to [uri] by letting [writer] populate the stream. The descriptor is opened, checked and
     * closed here; [writer] must not close what it is handed (and needn't — see [Sink]).
     *
     * The mode dance in [openForWrite] is the whole point: `"wt"` is what we want, but a provider
     * that doesn't implement truncation answers it with a read-only descriptor (or refuses it
     * outright), and `ContentResolver.openOutputStream` reports neither — it hands back a stream
     * whose *first write* dies with `write failed: EBADF (Bad file descriptor)`, halfway through a
     * save the user thought was working. So the descriptor is vetted before a byte is written, the
     * plain `"w"` mode is tried when `"wt"` doesn't yield a writable one, and we do the truncation
     * ourselves in that case.
     */
    fun writeTo(uri: Uri, writer: (OutputStream) -> Unit) {
        val handle = openForWrite(uri)
        handle.descriptor.use { pfd ->
            val sink = Sink(FileOutputStream(pfd.fileDescriptor))
            writer(sink)
            sink.flush()
            // "w" without "t" leaves whatever the old, longer file had past our last byte; trim it,
            // or a shrinking document keeps a tail of stale bytes and reopens as garbage.
            if (!handle.truncated) truncate(pfd, sink.count)
        }
    }

    /** A descriptor we have checked is really open for writing, and whether it arrived truncated. */
    private class Handle(val descriptor: ParcelFileDescriptor, val truncated: Boolean)

    /**
     * Open [uri] for writing: `"wt"` first, then plain `"w"`, taking the first descriptor that is
     * genuinely writable. Throws with every mode's refusal in the message rather than leaving the
     * EBADF to surface later from somewhere with no context.
     */
    private fun openForWrite(uri: Uri): Handle {
        val refusals = mutableListOf<String>()
        for (mode in WRITE_MODES) {
            val pfd = try {
                resolver.openFileDescriptor(uri, mode)
            } catch (e: Exception) {
                refusals += "$mode: ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            if (pfd == null) {
                refusals += "$mode: the provider returned no descriptor"
                continue
            }
            if (isWritable(pfd)) return Handle(pfd, truncated = mode.contains('t'))
            runCatching { pfd.close() }
            refusals += "$mode: the provider returned a read-only descriptor"
        }
        throw IOException("$uri could not be opened for writing (${refusals.joinToString("; ")})")
    }

    /**
     * True when [pfd] is actually open for writing. A descriptor that isn't fails every `write(2)`
     * with `EBADF`, which is the errno for "this fd is not open for that", not for "the file is
     * broken" — so asking the fd up front turns a mid-save crash into a refusal we can report.
     * A descriptor that won't answer `F_GETFL` gets the benefit of the doubt: better to try the
     * write than to refuse a provider that would have worked.
     */
    private fun isWritable(pfd: ParcelFileDescriptor): Boolean = runCatching {
        val access = Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
        access == OsConstants.O_WRONLY || access == OsConstants.O_RDWR
    }.getOrDefault(true)

    /** Cut [pfd] down to [length]. A pipe or proxy descriptor can't seek, and needs no trimming. */
    private fun truncate(pfd: ParcelFileDescriptor, length: Long) {
        runCatching { Os.ftruncate(pfd.fileDescriptor, length) }
    }

    /**
     * Counts what went through, and — deliberately — does **not** close the stream underneath.
     * The [ParcelFileDescriptor] is the single owner of that fd: closing it here as well would be a
     * double close, and a second close of an fd number the process has since re-opened for something
     * else takes down an unrelated file. Callers that wrap the sink in a `Writer`/`GZIPOutputStream`
     * and close it therefore stay safe.
     */
    private class Sink(private val out: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        override fun close() = out.flush()
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

    companion object {
        /** Write + truncate first, then plain write for a provider that can't do the truncating half. */
        private val WRITE_MODES = listOf("wt", "w")

        /** Copy [stream] (closed here) into a fresh file in [dir] and return it. Shared by [ImageStore] and [DocumentIo]. */
        fun copyIn(dir: File, stream: java.io.InputStream): File {
            val scratch = ScratchDir(dir)
            val out = scratch.newFile("copy")
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            return out
        }
    }
}
