package com.libreplayer.data.repository

import androidx.room.withTransaction
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.ImportedRootEntity
import com.libreplayer.data.database.entity.RecentlyPlayedEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.library.scanner.DeviceLibraryScanner
import com.libreplayer.library.scanner.LibraryScanResult
import com.libreplayer.library.scanner.ScannedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

data class LibrarySyncState(
    val isLoading: Boolean = false,
    val permissionRequired: Boolean = false,
    val errorMessage: String? = null,
)

interface LibraryRepository {
    val syncState: StateFlow<LibrarySyncState>
    fun observeSongs(): Flow<List<Song>>
    fun observeAlbums(): Flow<List<Album>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeFavorites(): Flow<List<Song>>
    fun observeRecentlyPlayed(limit: Int = 25): Flow<List<Song>>
    fun observeImportedRoots(): Flow<List<ImportedRoot>>
    suspend fun refreshLibraryIfNeeded()
    suspend fun rescanLibrary()
    suspend fun getSongsByIds(ids: List<String>): List<Song>
    suspend fun getSongById(id: String): Song?
    suspend fun toggleFavorite(songId: String)
    suspend fun markSongPlayed(songId: String)
    suspend fun addImportedRoot(uri: String, displayName: String)
    suspend fun removeImportedRoot(uri: String)
}

class DefaultLibraryRepository(
    private val database: AppDatabase,
    private val scanner: DeviceLibraryScanner,
    private val refreshStore: LibraryRefreshStore,
) : LibraryRepository {
    private val songDao = database.songDao()
    private val albumDao = database.albumDao()
    private val artistDao = database.artistDao()
    private val importedRootDao = database.importedRootDao()
    private val recentlyPlayedDao = database.recentlyPlayedDao()
    private val refreshMutex = Mutex()

    private val _syncState = MutableStateFlow(LibrarySyncState())
    override val syncState: StateFlow<LibrarySyncState> = _syncState.asStateFlow()

    override fun observeSongs(): Flow<List<Song>> =
        songDao.observeSongs().map { songs -> songs.map(SongEntity::asModel) }

    override fun observeAlbums(): Flow<List<Album>> =
        albumDao.observeAlbums().map { albums -> albums.map(AlbumEntity::asModel) }

    override fun observeArtists(): Flow<List<Artist>> =
        artistDao.observeArtists().map { artists -> artists.map(ArtistEntity::asModel) }

    override fun observeFavorites(): Flow<List<Song>> =
        songDao.observeFavoriteSongs().map { songs -> songs.map(SongEntity::asModel) }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<Song>> =
        combine(
            recentlyPlayedDao.observeRecentlyPlayed(limit),
            songDao.observeSongs(),
        ) { recentlyPlayed, songs ->
            val songMap = songs.associateBy { it.id }
            recentlyPlayed.mapNotNull { item -> songMap[item.songId]?.asModel() }
        }

    override fun observeImportedRoots(): Flow<List<ImportedRoot>> =
        importedRootDao.observeRoots().map { roots -> roots.map(ImportedRootEntity::asModel) }

    override suspend fun refreshLibraryIfNeeded() {
        refreshLibrary(forceRefresh = false)
    }

    override suspend fun rescanLibrary() {
        refreshLibrary(forceRefresh = true)
    }

    override suspend fun getSongsByIds(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val songs = songDao.getSongsByIds(ids).associateBy { it.id }
        return ids.mapNotNull { id -> songs[id]?.asModel() }
    }

    override suspend fun getSongById(id: String): Song? = songDao.getSongById(id)?.asModel()

    override suspend fun toggleFavorite(songId: String) {
        val song = songDao.getSongById(songId) ?: return
        songDao.updateFavorite(songId, !song.isFavorite)
    }

    override suspend fun markSongPlayed(songId: String) {
        recentlyPlayedDao.upsert(
            RecentlyPlayedEntity(
                songId = songId,
                playedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun addImportedRoot(uri: String, displayName: String) {
        importedRootDao.upsertRoot(
            ImportedRootEntity(
                uri = uri,
                displayName = displayName,
                addedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        rescanLibrary()
    }

    override suspend fun removeImportedRoot(uri: String) {
        importedRootDao.deleteRoot(uri)
        rescanLibrary()
    }

    private suspend fun refreshLibrary(forceRefresh: Boolean) {
        refreshMutex.withLock {
            val cachedSongCount = songDao.countSongs()
            val importedRoots = importedRootDao.getRoots().map { it.uri }
            val lastSuccessfulRefreshAt = refreshStore.lastSuccessfulRefreshAtMillis()
            if (
                !shouldRefreshLibrary(
                    forceRefresh = forceRefresh,
                    cachedSongCount = cachedSongCount,
                    lastSuccessfulRefreshAtMillis = lastSuccessfulRefreshAt,
                    nowMillis = System.currentTimeMillis(),
                )
            ) {
                _syncState.value = LibrarySyncState()
                return
            }

            _syncState.value = LibrarySyncState(isLoading = true)
            when (val result = scanner.scan(importedRoots)) {
                is LibraryScanResult.PermissionRequired -> {
                    _syncState.value = LibrarySyncState(permissionRequired = true)
                }

                is LibraryScanResult.Error -> {
                    _syncState.value = LibrarySyncState(errorMessage = result.message)
                }

                is LibraryScanResult.Success -> {
                    applyScanResult(
                        songs = result.songs,
                        isMediaStoreComplete = result.isMediaStoreComplete,
                    )
                    if (result.isMediaStoreComplete) {
                        refreshStore.markSuccessfulRefresh()
                    }
                    _syncState.value = LibrarySyncState(
                        permissionRequired = !result.isMediaStoreComplete,
                    )
                }
            }
        }
    }

    private suspend fun applyScanResult(
        songs: List<ScannedSong>,
        isMediaStoreComplete: Boolean,
    ) {
        val favoriteIds = songDao.getFavoriteIds().toSet()
        val cachedMediaStoreSongs = if (isMediaStoreComplete) {
            emptyList()
        } else {
            songDao.getSongsBySourceType(SongSourceType.MEDIA_STORE.name)
        }
        val snapshot = withContext(Dispatchers.Default) {
            prepareLibrarySnapshot(
                songs = songs,
                favoriteIds = favoriteIds,
                cachedMediaStoreSongs = cachedMediaStoreSongs,
                isMediaStoreComplete = isMediaStoreComplete,
            )
        }
        database.withTransaction {
            songDao.clearSongs()
            if (snapshot.songEntities.isNotEmpty()) {
                songDao.upsertSongs(snapshot.songEntities)
            }
            albumDao.clearAlbums()
            if (snapshot.albumEntities.isNotEmpty()) {
                albumDao.upsertAlbums(snapshot.albumEntities)
            }
            artistDao.clearArtists()
            if (snapshot.artistEntities.isNotEmpty()) {
                artistDao.upsertArtists(snapshot.artistEntities)
            }
        }
    }

    private fun prepareLibrarySnapshot(
        songs: List<ScannedSong>,
        favoriteIds: Set<String>,
        cachedMediaStoreSongs: List<SongEntity>,
        isMediaStoreComplete: Boolean,
    ): PreparedLibrarySnapshot {
        val scannedSongEntities = songs.map { song -> song.asEntity(song.id in favoriteIds) }
        val songEntities = mergeScanSongEntities(
            scannedSongs = scannedSongEntities,
            cachedMediaStoreSongs = cachedMediaStoreSongs,
            isMediaStoreComplete = isMediaStoreComplete,
        )
        return PreparedLibrarySnapshot(
            songEntities = songEntities,
            albumEntities = buildAlbums(songEntities),
            artistEntities = buildArtists(songEntities),
        )
    }

    private fun buildAlbums(songs: List<SongEntity>): List<AlbumEntity> =
        songs.groupBy { "${it.albumSortKey}|${it.artistSortKey}" }
            .map { (key, groupedSongs) ->
                val first = groupedSongs.first()
                AlbumEntity(
                    id = key,
                    title = first.album?.takeIf(String::isNotBlank) ?: "Unknown album",
                    artist = first.artist?.takeIf(String::isNotBlank),
                    songCount = groupedSongs.size,
                    totalDurationMs = groupedSongs.sumOf { it.durationMs },
                    artworkUri = groupedSongs.firstNotNullOfOrNull { it.artworkUri },
                    sortKey = first.albumSortKey,
                )
            }
            .sortedBy { it.sortKey }

    private fun buildArtists(songs: List<SongEntity>): List<ArtistEntity> =
        songs.groupBy { it.artistSortKey }
            .map { (key, groupedSongs) ->
                val first = groupedSongs.first()
                ArtistEntity(
                    id = key,
                    name = first.artist?.takeIf(String::isNotBlank) ?: "Unknown artist",
                    songCount = groupedSongs.size,
                    totalDurationMs = groupedSongs.sumOf { it.durationMs },
                    artworkUri = groupedSongs.firstNotNullOfOrNull { it.artworkUri },
                    sortKey = key,
                )
            }
            .sortedBy { it.sortKey }
}

internal fun shouldRefreshLibrary(
    forceRefresh: Boolean,
    cachedSongCount: Int,
    lastSuccessfulRefreshAtMillis: Long,
    nowMillis: Long,
    refreshIntervalMillis: Long = AUTO_REFRESH_INTERVAL_MS,
): Boolean =
    when {
        forceRefresh -> true
        cachedSongCount <= 0 -> true
        lastSuccessfulRefreshAtMillis <= 0L -> true
        nowMillis - lastSuccessfulRefreshAtMillis >= refreshIntervalMillis -> true
        else -> false
    }

private data class PreparedLibrarySnapshot(
    val songEntities: List<SongEntity>,
    val albumEntities: List<AlbumEntity>,
    val artistEntities: List<ArtistEntity>,
)

internal fun mergeScanSongEntities(
    scannedSongs: List<SongEntity>,
    cachedMediaStoreSongs: List<SongEntity>,
    isMediaStoreComplete: Boolean,
): List<SongEntity> {
    if (isMediaStoreComplete) return scannedSongs
    return (cachedMediaStoreSongs + scannedSongs)
        .associateBy(SongEntity::id)
        .values
        .toList()
}

private const val AUTO_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

private fun ScannedSong.asEntity(isFavorite: Boolean): SongEntity {
    val normalizedTitle = title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.')
    val normalizedArtist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
    val normalizedAlbum = album?.takeIf { it.isNotBlank() } ?: "Unknown album"
    return SongEntity(
        id = id,
        sourceType = sourceType.name,
        contentUri = contentUri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        dateAddedEpochSeconds = dateAddedEpochSeconds,
        dateModifiedEpochSeconds = dateModifiedEpochSeconds,
        displayName = displayName,
        relativePath = relativePath,
        mimeType = mimeType,
        artworkUri = artworkUri,
        isFavorite = isFavorite,
        titleSortKey = normalizedTitle.normalizedSortKey(),
        artistSortKey = normalizedArtist.normalizedSortKey(),
        albumSortKey = normalizedAlbum.normalizedSortKey(),
    )
}

private fun String.normalizedSortKey(): String = trim().lowercase(Locale.US)
