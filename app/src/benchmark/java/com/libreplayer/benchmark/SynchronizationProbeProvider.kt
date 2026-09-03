package com.libreplayer.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.media3.common.MediaItem
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.asModel
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.albumBrowseSongs
import com.libreplayer.library.semantics.artistBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseSongs
import com.libreplayer.library.semantics.buildBrowseAlbums
import com.libreplayer.library.semantics.buildBrowseArtists
import com.libreplayer.navigation.AppRoute
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Benchmark-variant-only access to the real repository synchronization paths. */
class SynchronizationProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = runBlocking(Dispatchers.IO) {
        val container = (requireNotNull(context).applicationContext as LibrePlayerApplication).appContainer
        val repository = container.libraryRepository
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        when (method) {
            METHOD_SYNC -> traced { repository.rescanLibrary() }
            METHOD_REBUILD -> traced { repository.rebuildLibrary() }
            METHOD_Q31_SYNC -> traced { repository.rescanLibrary() }
            METHOD_Q32_SYNC -> traced { repository.rescanLibrary() }
            METHOD_CATALOG -> Unit
            else -> error("Unsupported synchronization probe method: $method")
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedNanos
        if (method != METHOD_CATALOG) {
            Log.i(LOG_TAG, "method=$method elapsedNanos=$elapsedNanos")
        }
        val songs = repository.getAllSongs()
        when (method) {
            METHOD_Q31_SYNC -> q31CatalogBundle(songs, elapsedNanos)
            METHOD_Q32_SYNC -> q32CatalogBundle(
                allSongs = songs,
                allAlbums = container.database.albumDao().getAllAlbums().map { it.asModel() },
                allArtists = container.database.artistDao().getAllArtists().map { it.asModel() },
                elapsedNanos = elapsedNanos,
            )
            else -> catalogBundle(songs, elapsedNanos, arg)
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

    private fun q32CatalogBundle(
        allSongs: List<Song>,
        allAlbums: List<Album>,
        allArtists: List<Artist>,
        elapsedNanos: Long,
    ): Bundle {
        val songs = allSongs
            .filter { song ->
                song.sourceType == SongSourceType.MEDIA_STORE &&
                    song.relativePath?.replace('\\', '/')?.startsWith(Q32_RELATIVE_ROOT) == true
            }
            .sortedBy(Song::displayName)
        val albumIds = songs.mapTo(LinkedHashSet(), Song::albumBrowseGroupId)
        val artistIds = songs.mapTo(LinkedHashSet(), Song::artistBrowseGroupId)
        val albums = buildBrowseAlbums(songs).sortedBy(Album::id)
        val artists = buildBrowseArtists(songs).sortedBy(Artist::id)
        val persistedAlbums = allAlbums.filter { it.id in albumIds }.sortedBy(Album::id)
        val persistedArtists = allArtists.filter { it.id in artistIds }.sortedBy(Artist::id)
        val selected = songs.singleOrNull { it.displayName == Q32_SELECTED_FILE }
        val selectedMediaItem = selected?.toProductionMediaItem()
        val specialAlbumKey = songs.singleOrNull { it.displayName == Q32_SPECIAL_FILE }
            ?.albumBrowseGroupId()
        val encodedSpecialArgument = specialAlbumKey
            ?.let(AppRoute.AlbumDetail::create)
            ?.substringAfter("album/")
        val decodedSpecialArgument = encodedSpecialArgument?.let(Uri::decode)

        return Bundle().apply {
            putLong(KEY_ELAPSED_NANOS, elapsedNanos)
            putInt(KEY_Q32_SONG_COUNT, songs.size)
            putStringArray(KEY_Q32_SONG_IDS, songs.map(Song::id).toTypedArray())
            putStringArray(KEY_Q32_SONG_URIS, songs.map(Song::contentUri).toTypedArray())
            putStringArray(KEY_Q32_SONG_FILES, songs.map(Song::displayName).toTypedArray())
            putInt(KEY_Q32_ALBUM_COUNT, albums.size)
            putStringArray(KEY_Q32_ALBUM_IDS, albums.map(Album::id).toTypedArray())
            putStringArray(KEY_Q32_ALBUM_TITLES, albums.map(Album::title).toTypedArray())
            putStringArray(KEY_Q32_ALBUM_ARTISTS, albums.map { it.artist.orEmpty() }.toTypedArray())
            putIntArray(KEY_Q32_ALBUM_SONG_COUNTS, albums.map(Album::songCount).toIntArray())
            putStringArray(
                KEY_Q32_ALBUM_MEMBER_FILES,
                albums.map { album ->
                    albumBrowseSongs(songs, album.id).map(Song::displayName).sorted().joinToString(FILE_SEPARATOR)
                }.toTypedArray(),
            )
            putInt(KEY_Q32_ARTIST_COUNT, artists.size)
            putStringArray(KEY_Q32_ARTIST_IDS, artists.map(Artist::id).toTypedArray())
            putStringArray(KEY_Q32_ARTIST_NAMES, artists.map(Artist::name).toTypedArray())
            putIntArray(KEY_Q32_ARTIST_SONG_COUNTS, artists.map(Artist::songCount).toIntArray())
            putStringArray(
                KEY_Q32_ARTIST_MEMBER_FILES,
                artists.map { artist ->
                    artistBrowseSongs(songs, artist.id).map(Song::displayName).sorted().joinToString(FILE_SEPARATOR)
                }.toTypedArray(),
            )
            putBoolean(
                KEY_Q32_ALBUM_COUNT_PARITY,
                albums.all { album -> album.songCount == albumBrowseSongs(songs, album.id).size },
            )
            putBoolean(
                KEY_Q32_ARTIST_COUNT_PARITY,
                artists.all { artist -> artist.songCount == artistBrowseSongs(songs, artist.id).size },
            )
            putBoolean(
                KEY_Q32_PERSISTED_AGGREGATE_PARITY,
                albums.map { it.id to it.songCount } == persistedAlbums.map { it.id to it.songCount } &&
                    artists.map { it.id to it.songCount } == persistedArtists.map { it.id to it.songCount },
            )
            putString(KEY_Q32_SELECTED_ID, selected?.id)
            putString(KEY_Q32_SELECTED_URI, selected?.contentUri)
            putString(KEY_Q32_SELECTED_MEDIA_ID, selectedMediaItem?.mediaId)
            putString(KEY_Q32_SELECTED_MEDIA_URI, selectedMediaItem?.localConfiguration?.uri?.toString())
            putString(KEY_Q32_SPECIAL_ALBUM_KEY, specialAlbumKey)
            putString(KEY_Q32_SPECIAL_ROUTE_ARGUMENT, decodedSpecialArgument)
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

    /** Benchmark-only access to the unchanged private production identity mapper. */
    private fun Song.toProductionMediaItem(): MediaItem {
        val method = Class.forName("com.libreplayer.media.playback.PlaybackConnectionKt")
            .getDeclaredMethod("toMediaItem", Song::class.java)
            .apply { isAccessible = true }
        return method.invoke(null, this) as MediaItem
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
        const val METHOD_Q32_SYNC = "q3.2-sync"
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
        const val KEY_Q32_SONG_COUNT = "q32SongCount"
        const val KEY_Q32_SONG_IDS = "q32SongIds"
        const val KEY_Q32_SONG_URIS = "q32SongUris"
        const val KEY_Q32_SONG_FILES = "q32SongFiles"
        const val KEY_Q32_ALBUM_COUNT = "q32AlbumCount"
        const val KEY_Q32_ALBUM_IDS = "q32AlbumIds"
        const val KEY_Q32_ALBUM_TITLES = "q32AlbumTitles"
        const val KEY_Q32_ALBUM_ARTISTS = "q32AlbumArtists"
        const val KEY_Q32_ALBUM_SONG_COUNTS = "q32AlbumSongCounts"
        const val KEY_Q32_ALBUM_MEMBER_FILES = "q32AlbumMemberFiles"
        const val KEY_Q32_ARTIST_COUNT = "q32ArtistCount"
        const val KEY_Q32_ARTIST_IDS = "q32ArtistIds"
        const val KEY_Q32_ARTIST_NAMES = "q32ArtistNames"
        const val KEY_Q32_ARTIST_SONG_COUNTS = "q32ArtistSongCounts"
        const val KEY_Q32_ARTIST_MEMBER_FILES = "q32ArtistMemberFiles"
        const val KEY_Q32_ALBUM_COUNT_PARITY = "q32AlbumCountParity"
        const val KEY_Q32_ARTIST_COUNT_PARITY = "q32ArtistCountParity"
        const val KEY_Q32_PERSISTED_AGGREGATE_PARITY = "q32PersistedAggregateParity"
        const val KEY_Q32_SELECTED_ID = "q32SelectedId"
        const val KEY_Q32_SELECTED_URI = "q32SelectedUri"
        const val KEY_Q32_SELECTED_MEDIA_ID = "q32SelectedMediaId"
        const val KEY_Q32_SELECTED_MEDIA_URI = "q32SelectedMediaUri"
        const val KEY_Q32_SPECIAL_ALBUM_KEY = "q32SpecialAlbumKey"
        const val KEY_Q32_SPECIAL_ROUTE_ARGUMENT = "q32SpecialRouteArgument"
        const val LOG_TAG = "LibrePlayerSyncProbe"
        private const val FIXTURE_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/MEDIUM/"
        private const val Q31_RELATIVE_ROOT = "Music/LibrePlayerQ31/"
        private const val Q32_RELATIVE_ROOT = "Music/LibrePlayerQ32/"
        private const val Q32_SELECTED_FILE = "AlphaB1.mp3"
        private const val Q32_SPECIAL_FILE = "Special.mp3"
        private const val FILE_SEPARATOR = "\u001F"
    }
}
