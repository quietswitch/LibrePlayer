package com.libreplayer.migration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Standalone test-APK provider; uses only framework classes in its separate process. */
public final class FailingSourceProvider extends ContentProvider {
    private String mode = "partial";
    private int childQueries;
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("configure".equals(method)) { mode = arg; childQueries = 0; }
        Bundle result = new Bundle();
        result.putInt("childQueries", childQueries);
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        childQueries++;
        if ("null".equals(mode)) return null;
        if (uri.getPath().contains("/document/fail/")) throw new IllegalStateException("Injected failure after partial root enumeration");
        MatrixCursor cursor = new MatrixCursor(projection);
        add(cursor, projection, "opaque:X", "audio/mpeg");
        if ("partial".equals(mode)) add(cursor, projection, "fail", DocumentsContract.Document.MIME_TYPE_DIR);
        if ("loading".equals(mode)) {
            Bundle extras = new Bundle();
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
            cursor.setExtras(extras);
        }
        return cursor;
    }
    private void add(MatrixCursor cursor, String[] projection, String id, String mime) {
        Object[] values = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            switch (projection[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: values[i] = "same.mp3"; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: values[i] = mime; break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: values[i] = 1000L; break;
            }
        }
        cursor.addRow(values);
    }
    @Override public String getType(Uri uri) { return "audio/mpeg"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
