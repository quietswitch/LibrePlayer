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
            METHOD_Q31_SYNC -> traced { repository.rescanLibrary() }
            METHOD_CATALOG -> Unit
            else -> error("Unsupported synchronization probe method: $method")
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedNanos
        if (method != METHOD_CATALOG) {
            Log.i(LOG_TAG, "method=$method elapsedNanos=$elapsedNanos")
        }
        val songs = repository.getAllSongs()
        if (method == METHOD_Q31_SYNC) {
            q31CatalogBundle(songs, elapsedNanos)
        } else {
            catalogBundle(songs, elapsedNanos, arg)
        }
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

    private fun q31CatalogBundle(allSongs: List<Song>, elapsedNanos: Long): Bundle {
        val songs = allSongs
            .filter { song ->
                song.sourceType == SongSourceType.MEDIA_STORE &&
                    song.relativePath?.replace('\\', '/')?.startsWith(Q31_RELATIVE_ROOT) == true
            }
            .sortedWith(compareBy<Song>({ it.relativePath }, { it.displayName }))
        return Bundle().apply {
            putLong(KEY_ELAPSED_NANOS, elapsedNanos)
            putInt(KEY_Q31_COUNT, songs.size)
            putStringArray(KEY_Q31_IDS, songs.map(Song::id).toTypedArray())
            putStringArray(KEY_Q31_CONTENT_URIS, songs.map(Song::contentUri).toTypedArray())
            putStringArray(KEY_Q31_DISPLAY_NAMES, songs.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q31_TITLES, songs.map { it.title.orEmpty() }.toTypedArray())
            putStringArray(KEY_Q31_ARTISTS, songs.map { it.artist.orEmpty() }.toTypedArray())
            putStringArray(KEY_Q31_ALBUMS, songs.map { it.album.orEmpty() }.toTypedArray())
            putIntArray(KEY_Q31_TRACKS, songs.map { it.trackNumber ?: -1 }.toIntArray())
            putIntArray(KEY_Q31_DISCS, songs.map { it.discNumber ?: -1 }.toIntArray())
            putIntArray(KEY_Q31_YEARS, songs.map { it.year ?: -1 }.toIntArray())
            putStringArray(KEY_Q31_RELATIVE_PATHS, songs.map { it.relativePath.orEmpty() }.toTypedArray())
        }
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
        const val METHOD_Q31_SYNC = "q3.1-sync"
        const val KEY_ELAPSED_NANOS = "elapsedNanos"
        const val KEY_FIXTURE_COUNT = "fixtureCount"
        const val KEY_UNIQUE_IDENTITIES = "uniqueIdentities"
        const val KEY_DUPLICATE_IDENTITIES = "duplicateIdentities"
        const val KEY_IDENTITY_SHA256 = "identitySha256"
        const val KEY_INSPECTED_PRESENT = "inspectedPresent"
        const val KEY_INSPECTED_TITLE = "inspectedTitle"
        const val KEY_INSPECTED_ARTIST = "inspectedArtist"
        const val KEY_INSPECTED_ALBUM = "inspectedAlbum"
        const val KEY_Q31_COUNT = "q31Count"
        const val KEY_Q31_IDS = "q31Ids"
        const val KEY_Q31_CONTENT_URIS = "q31ContentUris"
        const val KEY_Q31_DISPLAY_NAMES = "q31DisplayNames"
        const val KEY_Q31_TITLES = "q31Titles"
        const val KEY_Q31_ARTISTS = "q31Artists"
        const val KEY_Q31_ALBUMS = "q31Albums"
        const val KEY_Q31_TRACKS = "q31Tracks"
        const val KEY_Q31_DISCS = "q31Discs"
        const val KEY_Q31_YEARS = "q31Years"
        const val KEY_Q31_RELATIVE_PATHS = "q31RelativePaths"
        const val LOG_TAG = "LibrePlayerSyncProbe"
        private const val FIXTURE_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/MEDIUM/"
        private const val Q31_RELATIVE_ROOT = "Music/LibrePlayerQ31/"
    }
}
