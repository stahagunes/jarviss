package com.jarvis.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Minimal provider so the system camera app can write a full-resolution photo for ZEUS. */
public class PhotoProvider extends ContentProvider {
    static final String AUTHORITY = "com.jarvis.app.photos";

    static File file(android.content.Context c) { return new File(c.getCacheDir(), "zeus_eye.jpg"); }

    static Uri uri() { return Uri.parse("content://" + AUTHORITY + "/zeus_eye.jpg"); }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(getContext());
        int m = ParcelFileDescriptor.parseMode(mode) | ParcelFileDescriptor.MODE_CREATE;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
