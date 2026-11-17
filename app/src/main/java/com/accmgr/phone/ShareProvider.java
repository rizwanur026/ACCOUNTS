package com.accmgr.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Hands files from cache/share to the system share sheet (no extra libraries needed). */
public class ShareProvider extends ContentProvider {
    static final String AUTH = "com.accmgr.phone.share";

    static File dir(android.content.Context c) {
        File d = new File(c.getCacheDir(), "share");
        d.mkdirs();
        return d;
    }

    static Uri uri(String name) {
        return new Uri.Builder().scheme("content").authority(AUTH).appendPath(name).build();
    }

    private File resolve(Uri u) throws FileNotFoundException {
        String n = u.getLastPathSegment();
        if (n == null || n.contains("/") || n.contains("..")) throw new FileNotFoundException();
        File f = new File(dir(getContext()), n);
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        try {
            File f = resolve(u);
            MatrixCursor mc = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            mc.addRow(new Object[]{f.getName(), f.length()});
            return mc;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri u) {
        String p = u.getLastPathSegment();
        if (p != null && p.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }

    @Override public int delete(Uri u, String s, String[] a) { return 0; }

    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
