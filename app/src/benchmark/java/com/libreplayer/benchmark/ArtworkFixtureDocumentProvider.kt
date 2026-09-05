package com.libreplayer.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/** Benchmark-only single-document surface for exercising the production SAF artwork path. */
class ArtworkFixtureDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fixtureFile(uri)
        val columns = projection ?: DEFAULT_PROJECTION
        return MatrixCursor(columns).apply {
            val row: Array<Any?> = columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> file.name
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> MIME_TYPE
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        DocumentsContract.Document.COLUMN_SIZE -> file.length()
                        else -> null
                    }
                }.toTypedArray()
            addRow(row)
        }
    }

    override fun getType(uri: Uri): String {
        fixtureFile(uri)
        return MIME_TYPE
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r") { "Artwork fixture provider is read-only" }
        return ParcelFileDescriptor.open(fixtureFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fixtureFile(uri: Uri): File {
        check(uri.authority == AUTHORITY)
        val name = uri.lastPathSegment.orEmpty()
        check(name == SAF_FILE && '/' !in name && '\\' !in name)
        val file = File(FIXTURE_ROOT, name)
        check(file.isFile) { "Missing Q3.5 fixture: $file" }
        return file
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.libreplayer.artwork-fixture-document"
        const val SAF_FILE = "01-normal-jpeg.mp3"
        const val FIXTURE_URI = "content://$AUTHORITY/$SAF_FILE"
        private const val FIXTURE_ROOT = "/sdcard/Music/LibrePlayerBenchmark/Q35_ARTWORK_AUTHORITY"
        private const val MIME_TYPE = "audio/mpeg"
        private val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
