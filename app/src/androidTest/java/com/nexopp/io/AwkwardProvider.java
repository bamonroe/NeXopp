package com.nexopp.io;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A stand-in for the {@code DocumentsProvider}s that break saving in the field — the cloud and
 * network ones whose {@code openDocument} doesn't honour every mode the framework hands it.
 *
 * <p>The emulator's own providers are all well-behaved, so the two failure modes behind
 * "Save failed: write failed: EBADF (Bad file descriptor)" can't be reproduced against them. This
 * one reproduces them on demand, keyed by the first path segment:
 *
 * <table>
 *   <tr><th>Path</th><th>Behaviour</th></tr>
 *   <tr><td>{@code /honest/<name>}</td><td>obeys the mode it is given, like a local provider</td></tr>
 *   <tr><td>{@code /no-truncate/<name>}</td><td>refuses any mode containing {@code t}, and never truncates</td></tr>
 *   <tr><td>{@code /read-only/<name>}</td><td>accepts every mode, answers with a <b>read-only</b> descriptor</td></tr>
 * </table>
 *
 * <p>The last one is the nasty one: the open succeeds, so nothing looks wrong until the first
 * {@code write(2)} comes back {@code EBADF}.
 *
 * <p>The backing files live in <b>this</b> provider's own storage, not the app's: the test package
 * has its own uid, so a path handed across the sandbox boundary would only fail on permissions and
 * prove nothing. The test seeds them and reads them back through the {@code /honest/} path.
 *
 * <p><b>Why Java, in a Kotlin project:</b> a provider declared by the test package is hosted in a
 * process of its own, which loads only the test APK's dex — not the app's, and therefore not the
 * Kotlin stdlib that lives there. A Kotlin version of this class dies on its first call with
 * {@code ClassNotFoundException: kotlin.jvm.internal.Intrinsics}.
 */
public class AwkwardProvider extends ContentProvider {

    public static final String AUTHORITY = "com.nexopp.test.awkward";

    /** A URI for document {@code name} served with the given {@code behaviour}. */
    public static Uri uri(String behaviour, String name) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(behaviour)
                .appendPath(name)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = backing(uri);
        String behaviour = uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0);
        switch (behaviour) {
            case "honest":
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
            case "no-truncate":
                if (mode.contains("t")) {
                    throw new UnsupportedOperationException("truncation not supported");
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
            case "read-only":
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            default:
                throw new FileNotFoundException("unknown behaviour in " + uri);
        }
    }

    /** The file behind {@code uri}, created empty if this is its first mention. */
    private File backing(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) {
            throw new FileNotFoundException("no document name in " + uri);
        }
        File dir = new File(getContext().getCacheDir(), "awkward");
        dir.mkdirs();
        File file = new File(dir, name);
        try {
            file.createNewFile();
        } catch (IOException e) {
            throw new FileNotFoundException("could not create " + file + ": " + e.getMessage());
        }
        return file;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String sel, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String sel, String[] args) {
        return 0;
    }
}
