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
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.asModel
import com.libreplayer.library.metadata.AudioMetadataReader
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.albumBrowseSongs
import com.libreplayer.library.semantics.artistBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseSongs
import com.libreplayer.library.semantics.buildBrowseAlbums
import com.libreplayer.library.semantics.buildBrowseArtists
import com.libreplayer.library.semantics.sortAlbumTracks
import com.libreplayer.library.semantics.sortSongs
import com.libreplayer.media.playback.PlaybackConnection
import com.libreplayer.navigation.AppRoute
import com.libreplayer.util.LibrarySearchEngine
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
            METHOD_Q33_SYNC -> traced { repository.rescanLibrary() }
            METHOD_Q34_SYNC -> traced { repository.rescanLibrary() }
            METHOD_CATALOG -> Unit
            METHOD_Q33_SELECTION -> Unit
            METHOD_Q34_PLAY -> Unit
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
            METHOD_Q33_SYNC -> q33CatalogBundle(songs, elapsedNanos)
            METHOD_Q33_SELECTION -> q33SelectionBundle(container.playbackConnection.uiState.value)
            METHOD_Q34_SYNC -> q34CatalogBundle(songs, elapsedNanos)
            METHOD_Q34_PLAY -> q34PlayBundle(container.playbackConnection, songs)
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

    /** Exact Q3.3 projections from the real synchronized repository snapshot. */
    private fun q33CatalogBundle(allSongs: List<Song>, elapsedNanos: Long): Bundle {
        val songs = allSongs.filter { song ->
            song.sourceType == SongSourceType.MEDIA_STORE &&
                song.relativePath?.replace('\\', '/')?.startsWith(Q32_RELATIVE_ROOT) == true
        }
        val titleSongs = sortSongs(songs, LibrarySortOption.TITLE)
        val albumSongs = sortSongs(songs, LibrarySortOption.ALBUM)
        val albums = buildBrowseAlbums(songs)
        val artists = buildBrowseArtists(songs)
        val alphaAlbum = albums.single { it.title == "Album Alpha" && it.artist == "Artist A" }
        val alphaTracks = sortAlbumTracks(albumBrowseSongs(songs, alphaAlbum.id))
        val titleSearch = LibrarySearchEngine.search("alpha a", titleSongs, albums, artists)
        val artistSearch = LibrarySearchEngine.search("artist b", titleSongs, albums, artists)
        val albumSearch = LibrarySearchEngine.search("album beta", titleSongs, albums, artists)
        val twinSearch = LibrarySearchEngine.search("twin", titleSongs, albums, artists)
        val noMatch = LibrarySearchEngine.search("q3-3-no-match", titleSongs, albums, artists)
        val blank = LibrarySearchEngine.search("   ", titleSongs, albums, artists)
        val selected = titleSongs.single { it.displayName == Q32_SELECTED_FILE }
        val selectedMediaItem = selected.toProductionMediaItem()

        return Bundle().apply {
            putLong(KEY_ELAPSED_NANOS, elapsedNanos)
            putInt(KEY_Q33_SONG_COUNT, songs.size)
            putStringArray(KEY_Q33_TITLE_FILES, titleSongs.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q33_TITLE_IDS, titleSongs.map(Song::id).toTypedArray())
            putStringArray(KEY_Q33_ALBUM_SORT_FILES, albumSongs.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q33_ALBUM_SORT_IDS, albumSongs.map(Song::id).toTypedArray())
            putStringArray(KEY_Q33_ALBUM_IDS, albums.map(Album::id).toTypedArray())
            putStringArray(KEY_Q33_ALBUM_TITLES, albums.map(Album::title).toTypedArray())
            putStringArray(KEY_Q33_ALBUM_ARTISTS, albums.map { it.artist.orEmpty() }.toTypedArray())
            putStringArray(KEY_Q33_ARTIST_IDS, artists.map(Artist::id).toTypedArray())
            putStringArray(KEY_Q33_ARTIST_NAMES, artists.map(Artist::name).toTypedArray())
            putStringArray(KEY_Q33_ALPHA_TRACK_FILES, alphaTracks.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q33_ALPHA_TRACK_IDS, alphaTracks.map(Song::id).toTypedArray())
            putStringArray(KEY_Q33_TITLE_SEARCH_FILES, titleSearch.songs.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q33_ARTIST_SEARCH_FILES, artistSearch.songs.map(Song::displayName).toTypedArray())
            putInt(KEY_Q33_ARTIST_SEARCH_ALBUMS, artistSearch.albums.size)
            putInt(KEY_Q33_ARTIST_SEARCH_ARTISTS, artistSearch.artists.size)
            putStringArray(KEY_Q33_ALBUM_SEARCH_FILES, albumSearch.songs.map(Song::displayName).toTypedArray())
            putInt(KEY_Q33_ALBUM_SEARCH_ALBUMS, albumSearch.albums.size)
            putStringArray(KEY_Q33_TWIN_FILES, twinSearch.songs.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q33_TWIN_IDS, twinSearch.songs.map(Song::id).toTypedArray())
            putStringArray(KEY_Q33_TWIN_URIS, twinSearch.songs.map(Song::contentUri).toTypedArray())
            putInt(KEY_Q33_NO_MATCH_TOTAL, noMatch.songs.size + noMatch.albums.size + noMatch.artists.size)
            putInt(KEY_Q33_BLANK_TOTAL, blank.songs.size + blank.albums.size + blank.artists.size)
            putString(KEY_Q33_SELECTED_ID, selected.id)
            putString(KEY_Q33_SELECTED_URI, selected.contentUri)
            putString(KEY_Q33_SELECTED_MEDIA_ID, selectedMediaItem.mediaId)
            putString(KEY_Q33_SELECTED_MEDIA_URI, selectedMediaItem.localConfiguration?.uri?.toString())
        }
    }

    private fun q33SelectionBundle(state: com.libreplayer.data.repository.PlaybackUiState): Bundle =
        Bundle().apply {
            putString(KEY_Q33_CURRENT_ID, state.currentSong?.id)
            putString(KEY_Q33_CURRENT_URI, state.currentSong?.contentUri)
        }

    /** Exact Q3.4 projections from the real API-level MediaStore and repository snapshot. */
    private fun q34CatalogBundle(allSongs: List<Song>, elapsedNanos: Long): Bundle {
        val songs = allSongs.filter { song ->
            song.sourceType == SongSourceType.MEDIA_STORE &&
                song.relativePath?.replace('\\', '/')?.startsWith(Q34_RELATIVE_ROOT) == true
        }.sortedBy(Song::displayName)
        val albums = buildBrowseAlbums(songs)
        val artists = buildBrowseArtists(songs)
        val titleSorted = sortSongs(songs, LibrarySortOption.TITLE)
        val longSong = songs.singleOrNull { it.displayName == Q34_LONG_FILE }
        val numericSong = songs.singleOrNull { it.displayName == Q34_NUMERIC_FILE }
        val malformedSong = songs.singleOrNull { it.displayName == Q34_PLAYABLE_MALFORMED_FILE }
        val metadataReader = AudioMetadataReader(requireNotNull(context))
        val numericRetriever = numericSong?.let { metadataReader.read(Uri.parse(it.contentUri)) }
        val malformedRetriever = malformedSong?.let { metadataReader.read(Uri.parse(it.contentUri)) }
        val selected = songs.singleOrNull { it.displayName == Q34_SELECTED_FILE }
        val selectedMediaItem = selected?.toProductionMediaItem()
        val longAlbumId = longSong?.albumBrowseGroupId()
        val longRouteArgument = longAlbumId
            ?.let(AppRoute.AlbumDetail::create)
            ?.substringAfter("album/")
            ?.let(Uri::decode)
        val fingerprintLines = songs.map { song ->
            listOf(
                song.id,
                song.contentUri,
                song.displayName,
                song.title ?: NULL_MARKER,
                song.artist ?: NULL_MARKER,
                song.album ?: NULL_MARKER,
                song.resolvedTitle,
                song.resolvedArtist,
                song.resolvedAlbum,
                song.trackNumber?.toString() ?: NULL_MARKER,
                song.discNumber?.toString() ?: NULL_MARKER,
                song.year?.toString() ?: NULL_MARKER,
                song.albumBrowseGroupId(),
                song.artistBrowseGroupId(),
            ).joinToString(FINGERPRINT_SEPARATOR)
        }
        val longSearch = LibrarySearchEngine.search("long-needle", songs, albums, artists)

        return Bundle().apply {
            putLong(KEY_ELAPSED_NANOS, elapsedNanos)
            putInt(KEY_Q34_COUNT, songs.size)
            putStringArray(KEY_Q34_IDS, songs.map(Song::id).toTypedArray())
            putStringArray(KEY_Q34_URIS, songs.map(Song::contentUri).toTypedArray())
            putStringArray(KEY_Q34_FILES, songs.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q34_TITLES, songs.map { it.title ?: NULL_MARKER }.toTypedArray())
            putStringArray(KEY_Q34_ARTISTS, songs.map { it.artist ?: NULL_MARKER }.toTypedArray())
            putStringArray(KEY_Q34_ALBUMS, songs.map { it.album ?: NULL_MARKER }.toTypedArray())
            putStringArray(KEY_Q34_RESOLVED_TITLES, songs.map(Song::resolvedTitle).toTypedArray())
            putStringArray(KEY_Q34_RESOLVED_ARTISTS, songs.map(Song::resolvedArtist).toTypedArray())
            putStringArray(KEY_Q34_RESOLVED_ALBUMS, songs.map(Song::resolvedAlbum).toTypedArray())
            putIntArray(KEY_Q34_TRACKS, songs.map { it.trackNumber ?: -1 }.toIntArray())
            putIntArray(KEY_Q34_DISCS, songs.map { it.discNumber ?: -1 }.toIntArray())
            putIntArray(KEY_Q34_YEARS, songs.map { it.year ?: -1 }.toIntArray())
            putStringArray(KEY_Q34_ALBUM_IDS, songs.map(Song::albumBrowseGroupId).toTypedArray())
            putStringArray(KEY_Q34_ARTIST_IDS, songs.map(Song::artistBrowseGroupId).toTypedArray())
            putStringArray(KEY_Q34_TITLE_SORT_FILES, titleSorted.map(Song::displayName).toTypedArray())
            putStringArray(KEY_Q34_LONG_SEARCH_FILES, longSearch.songs.map(Song::displayName).toTypedArray())
            putInt(KEY_Q34_LONG_TITLE_LENGTH, longSong?.title?.length ?: -1)
            putInt(KEY_Q34_LONG_ARTIST_LENGTH, longSong?.artist?.length ?: -1)
            putInt(KEY_Q34_LONG_ALBUM_LENGTH, longSong?.album?.length ?: -1)
            putString(KEY_Q34_LONG_ALBUM_ID, longAlbumId)
            putString(KEY_Q34_LONG_ROUTE_ARGUMENT, longRouteArgument)
            putString(KEY_Q34_SELECTED_ID, selected?.id)
            putString(KEY_Q34_SELECTED_URI, selected?.contentUri)
            putString(KEY_Q34_SELECTED_MEDIA_ID, selectedMediaItem?.mediaId)
            putString(KEY_Q34_SELECTED_MEDIA_URI, selectedMediaItem?.localConfiguration?.uri?.toString())
            putString(KEY_Q34_FINGERPRINT, identityFingerprint(fingerprintLines))
            putInt(KEY_Q34_RETRIEVER_NUMERIC_TRACK, numericRetriever?.trackNumber ?: -1)
            putInt(KEY_Q34_RETRIEVER_NUMERIC_DISC, numericRetriever?.discNumber ?: -1)
            putInt(KEY_Q34_RETRIEVER_NUMERIC_YEAR, numericRetriever?.year ?: -1)
            putBoolean(KEY_Q34_RETRIEVER_MALFORMED_READABLE, malformedRetriever != null)
            putLong(KEY_Q34_RETRIEVER_MALFORMED_DURATION, malformedRetriever?.durationMs ?: -1L)
            putString(KEY_Q34_RETRIEVER_MALFORMED_TITLE, malformedRetriever?.title)
        }
    }

    private suspend fun q34PlayBundle(
        connection: PlaybackConnection,
        allSongs: List<Song>,
    ): Bundle {
        val selected = allSongs.single { song ->
            song.sourceType == SongSourceType.MEDIA_STORE &&
                song.relativePath?.replace('\\', '/')?.startsWith(Q34_RELATIVE_ROOT) == true &&
                song.displayName == Q34_PLAYABLE_MALFORMED_FILE
        }
        withContext(Dispatchers.Main.immediate) {
            connection.playSong(listOf(selected), 0)
        }
        val deadline = SystemClock.elapsedRealtime() + Q34_PLAY_TIMEOUT_MS
        var state = connection.uiState.value
        while (
            SystemClock.elapsedRealtime() < deadline &&
            (state.currentSong?.id != selected.id || (!state.isPlaying && state.errorMessage == null))
        ) {
            delay(Q34_PLAY_POLL_MS)
            state = connection.uiState.value
        }
        return Bundle().apply {
            putString(KEY_Q34_PLAY_EXPECTED_ID, selected.id)
            putString(KEY_Q34_PLAY_EXPECTED_URI, selected.contentUri)
            putString(KEY_Q34_PLAY_CURRENT_ID, state.currentSong?.id)
            putString(KEY_Q34_PLAY_CURRENT_URI, state.currentSong?.contentUri)
            putBoolean(KEY_Q34_PLAY_IS_PLAYING, state.isPlaying)
            putString(KEY_Q34_PLAY_ERROR, state.errorMessage)
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
        const val METHOD_Q33_SYNC = "q3.3-sync"
        const val METHOD_Q33_SELECTION = "q3.3-selection"
        const val METHOD_Q34_SYNC = "q3.4-sync"
        const val METHOD_Q34_PLAY = "q3.4-play"
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
        const val KEY_Q33_SONG_COUNT = "q33SongCount"
        const val KEY_Q33_TITLE_FILES = "q33TitleFiles"
        const val KEY_Q33_TITLE_IDS = "q33TitleIds"
        const val KEY_Q33_ALBUM_SORT_FILES = "q33AlbumSortFiles"
        const val KEY_Q33_ALBUM_SORT_IDS = "q33AlbumSortIds"
        const val KEY_Q33_ALBUM_IDS = "q33AlbumIds"
        const val KEY_Q33_ALBUM_TITLES = "q33AlbumTitles"
        const val KEY_Q33_ALBUM_ARTISTS = "q33AlbumArtists"
        const val KEY_Q33_ARTIST_IDS = "q33ArtistIds"
        const val KEY_Q33_ARTIST_NAMES = "q33ArtistNames"
        const val KEY_Q33_ALPHA_TRACK_FILES = "q33AlphaTrackFiles"
        const val KEY_Q33_ALPHA_TRACK_IDS = "q33AlphaTrackIds"
        const val KEY_Q33_TITLE_SEARCH_FILES = "q33TitleSearchFiles"
        const val KEY_Q33_ARTIST_SEARCH_FILES = "q33ArtistSearchFiles"
        const val KEY_Q33_ARTIST_SEARCH_ALBUMS = "q33ArtistSearchAlbums"
        const val KEY_Q33_ARTIST_SEARCH_ARTISTS = "q33ArtistSearchArtists"
        const val KEY_Q33_ALBUM_SEARCH_FILES = "q33AlbumSearchFiles"
        const val KEY_Q33_ALBUM_SEARCH_ALBUMS = "q33AlbumSearchAlbums"
        const val KEY_Q33_TWIN_FILES = "q33TwinFiles"
        const val KEY_Q33_TWIN_IDS = "q33TwinIds"
        const val KEY_Q33_TWIN_URIS = "q33TwinUris"
        const val KEY_Q33_NO_MATCH_TOTAL = "q33NoMatchTotal"
        const val KEY_Q33_BLANK_TOTAL = "q33BlankTotal"
        const val KEY_Q33_SELECTED_ID = "q33SelectedId"
        const val KEY_Q33_SELECTED_URI = "q33SelectedUri"
        const val KEY_Q33_SELECTED_MEDIA_ID = "q33SelectedMediaId"
        const val KEY_Q33_SELECTED_MEDIA_URI = "q33SelectedMediaUri"
        const val KEY_Q33_CURRENT_ID = "q33CurrentId"
        const val KEY_Q33_CURRENT_URI = "q33CurrentUri"
        const val KEY_Q34_COUNT = "q34Count"
        const val KEY_Q34_IDS = "q34Ids"
        const val KEY_Q34_URIS = "q34Uris"
        const val KEY_Q34_FILES = "q34Files"
        const val KEY_Q34_TITLES = "q34Titles"
        const val KEY_Q34_ARTISTS = "q34Artists"
        const val KEY_Q34_ALBUMS = "q34Albums"
        const val KEY_Q34_RESOLVED_TITLES = "q34ResolvedTitles"
        const val KEY_Q34_RESOLVED_ARTISTS = "q34ResolvedArtists"
        const val KEY_Q34_RESOLVED_ALBUMS = "q34ResolvedAlbums"
        const val KEY_Q34_TRACKS = "q34Tracks"
        const val KEY_Q34_DISCS = "q34Discs"
        const val KEY_Q34_YEARS = "q34Years"
        const val KEY_Q34_ALBUM_IDS = "q34AlbumIds"
        const val KEY_Q34_ARTIST_IDS = "q34ArtistIds"
        const val KEY_Q34_TITLE_SORT_FILES = "q34TitleSortFiles"
        const val KEY_Q34_LONG_SEARCH_FILES = "q34LongSearchFiles"
        const val KEY_Q34_LONG_TITLE_LENGTH = "q34LongTitleLength"
        const val KEY_Q34_LONG_ARTIST_LENGTH = "q34LongArtistLength"
        const val KEY_Q34_LONG_ALBUM_LENGTH = "q34LongAlbumLength"
        const val KEY_Q34_LONG_ALBUM_ID = "q34LongAlbumId"
        const val KEY_Q34_LONG_ROUTE_ARGUMENT = "q34LongRouteArgument"
        const val KEY_Q34_SELECTED_ID = "q34SelectedId"
        const val KEY_Q34_SELECTED_URI = "q34SelectedUri"
        const val KEY_Q34_SELECTED_MEDIA_ID = "q34SelectedMediaId"
        const val KEY_Q34_SELECTED_MEDIA_URI = "q34SelectedMediaUri"
        const val KEY_Q34_FINGERPRINT = "q34Fingerprint"
        const val KEY_Q34_RETRIEVER_NUMERIC_TRACK = "q34RetrieverNumericTrack"
        const val KEY_Q34_RETRIEVER_NUMERIC_DISC = "q34RetrieverNumericDisc"
        const val KEY_Q34_RETRIEVER_NUMERIC_YEAR = "q34RetrieverNumericYear"
        const val KEY_Q34_RETRIEVER_MALFORMED_READABLE = "q34RetrieverMalformedReadable"
        const val KEY_Q34_RETRIEVER_MALFORMED_DURATION = "q34RetrieverMalformedDuration"
        const val KEY_Q34_RETRIEVER_MALFORMED_TITLE = "q34RetrieverMalformedTitle"
        const val KEY_Q34_PLAY_EXPECTED_ID = "q34PlayExpectedId"
        const val KEY_Q34_PLAY_EXPECTED_URI = "q34PlayExpectedUri"
        const val KEY_Q34_PLAY_CURRENT_ID = "q34PlayCurrentId"
        const val KEY_Q34_PLAY_CURRENT_URI = "q34PlayCurrentUri"
        const val KEY_Q34_PLAY_IS_PLAYING = "q34PlayIsPlaying"
        const val KEY_Q34_PLAY_ERROR = "q34PlayError"
        const val LOG_TAG = "LibrePlayerSyncProbe"
        private const val FIXTURE_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/MEDIUM/"
        private const val Q31_RELATIVE_ROOT = "Music/LibrePlayerQ31/"
        private const val Q32_RELATIVE_ROOT = "Music/LibrePlayerQ32/"
        private const val Q34_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/Q34_METADATA_PATHOLOGY/"
        private const val Q32_SELECTED_FILE = "AlphaB1.mp3"
        private const val Q32_SPECIAL_FILE = "Special.mp3"
        private const val Q34_SELECTED_FILE = "05-unicode-precomposed.mp3"
        private const val Q34_LONG_FILE = "08-long-needle.mp3"
        private const val Q34_NUMERIC_FILE = "09-numeric.mp3"
        private const val Q34_PLAYABLE_MALFORMED_FILE = "11-malformed-playable.mp3"
        private const val Q34_PLAY_TIMEOUT_MS = 10_000L
        private const val Q34_PLAY_POLL_MS = 50L
        private const val NULL_MARKER = "<null>"
        private const val FINGERPRINT_SEPARATOR = "\u001E"
        private const val FILE_SEPARATOR = "\u001F"
    }
}
