package com.libreplayer.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Benchmark-variant-only access to the real repository synchronization paths. */
class SynchronizationProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = runBlocking(Dispatchers.IO) {
        val repository = (requireNotNull(context).applicationContext as LibrePlayerApplication)
            .appContainer.libraryRepository
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        when (method) {
            METHOD_SYNC -> traced { repository.rescanLibrary() }
            METHOD_REBUILD -> traced { repository.rebuildLibrary() }
            METHOD_CATALOG -> Unit
            else -> error("Unsupported synchronization probe method: $method")
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedNanos
        if (method != METHOD_CATALOG) {
            Log.i(LOG_TAG, "method=$method elapsedNanos=$elapsedNanos")
        }
        catalogBundle(repository.getAllSongs(), elapsedNanos, arg)
    }

    private suspend fun traced(block: suspend () -> Unit) {
        Trace.beginSection(TRACE_SECTION)
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    private fun catalogBundle(allSongs: List<Song>, elapsedNanos: Long, inspectedIdentity: String?): Bundle {
        val fixtureSongs = allSongs.filter { song ->
            song.sourceType == SongSourceType.MEDIA_STORE &&
                song.fixtureIdentity() != null
        }
        val identities = fixtureSongs.mapNotNull { it.fixtureIdentity() }.sorted()
        val duplicates = identities.groupingBy { it }.eachCount().count { it.value > 1 }
        val inspected = inspectedIdentity?.let { identity ->
            fixtureSongs.singleOrNull { it.fixtureIdentity() == identity }
        }
        return Bundle().apply {
            putLong(KEY_ELAPSED_NANOS, elapsedNanos)
            putInt(KEY_FIXTURE_COUNT, fixtureSongs.size)
            putInt(KEY_UNIQUE_IDENTITIES, identities.distinct().size)
            putInt(KEY_DUPLICATE_IDENTITIES, duplicates)
            putString(KEY_IDENTITY_SHA256, identityFingerprint(identities))
            putBoolean(KEY_INSPECTED_PRESENT, inspected != null)
            putString(KEY_INSPECTED_TITLE, inspected?.title)
            putString(KEY_INSPECTED_ARTIST, inspected?.artist)
            putString(KEY_INSPECTED_ALBUM, inspected?.album)
        }
    }

    private fun Song.fixtureIdentity(): String? {
        val directory = relativePath?.replace('\\', '/') ?: return null
        if (!directory.startsWith(FIXTURE_RELATIVE_ROOT)) return null
        return directory.removePrefix(FIXTURE_RELATIVE_ROOT) + displayName
    }

    private fun identityFingerprint(identities: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        identities.forEach { identity ->
            digest.update(identity.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.libreplayer.synchronization-probe"
        const val TRACE_SECTION = "LibrePlayerSynchronization"
        const val METHOD_SYNC = "sync"
        const val METHOD_REBUILD = "rebuild"
        const val METHOD_CATALOG = "catalog"
        const val KEY_ELAPSED_NANOS = "elapsedNanos"
        const val KEY_FIXTURE_COUNT = "fixtureCount"
        const val KEY_UNIQUE_IDENTITIES = "uniqueIdentities"
        const val KEY_DUPLICATE_IDENTITIES = "duplicateIdentities"
        const val KEY_IDENTITY_SHA256 = "identitySha256"
        const val KEY_INSPECTED_PRESENT = "inspectedPresent"
        const val KEY_INSPECTED_TITLE = "inspectedTitle"
        const val KEY_INSPECTED_ARTIST = "inspectedArtist"
        const val KEY_INSPECTED_ALBUM = "inspectedAlbum"
        const val LOG_TAG = "LibrePlayerSyncProbe"
        private const val FIXTURE_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/MEDIUM/"
    }
}
