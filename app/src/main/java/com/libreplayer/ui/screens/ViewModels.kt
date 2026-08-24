package com.libreplayer.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.PlaylistRepository
import com.libreplayer.data.repository.PlaylistSong
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.ThemeMode
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.ImportedRoot
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.data.repository.LibrarySyncState
import com.libreplayer.media.playback.PlaybackConnection
import com.libreplayer.settings.SettingsRepository
import com.libreplayer.util.LibrarySearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings(),
    )

    private val catalogFlow = combine(
        libraryRepository.observeSongs(),
        libraryRepository.observeAlbums(),
        libraryRepository.observeArtists(),
        playlistRepository.observePlaylists(),
    ) { songs, albums, artists, playlists ->
        LibraryCatalogSnapshot(songs, albums, artists, playlists)
    }

    private val metadataFlow = combine(
        libraryRepository.observeFavorites(),
        libraryRepository.observeRecentlyPlayed(),
        libraryRepository.observeImportedRoots(),
        libraryRepository.syncState,
    ) { favorites, recentlyPlayed, importedRoots, syncState ->
        LibraryMetaSnapshot(favorites, recentlyPlayed, importedRoots, syncState)
    }

    private val catalogPresentationFlow = combine(
        catalogFlow,
        settings,
    ) { catalog, currentSettings ->
        val sortedSongs = catalog.songs.sortedWith(songComparator(currentSettings.defaultSortOption))
        LibraryCatalogPresentation(
            songs = sortedSongs,
            albums = enrichAlbumsWithArtwork(
                albums = catalog.albums,
                songs = catalog.songs,
            ),
            artists = enrichArtistsWithArtwork(
                artists = catalog.artists,
                songs = catalog.songs,
            ),
            playlists = catalog.playlists,
        )
    }.flowOn(Dispatchers.Default)

    private val metadataPresentationFlow = combine(
        metadataFlow,
        settings,
    ) { metadata, currentSettings ->
        LibraryMetaPresentation(
            favorites = metadata.favorites.sortedWith(songComparator(currentSettings.defaultSortOption)),
            recentlyPlayed = metadata.recentlyPlayed,
            importedRoots = metadata.importedRoots,
            syncState = metadata.syncState,
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryScreenState> = combine(
        catalogPresentationFlow,
        metadataPresentationFlow,
        searchQuery,
    ) { catalog, metadata, query ->
        val searchResult = LibrarySearchEngine.search(
            query = query,
            songs = catalog.songs,
            albums = catalog.albums,
            artists = catalog.artists,
        )
        LibraryScreenState(
            isLoading = metadata.syncState.isLoading,
            permissionRequired = metadata.syncState.permissionRequired,
            errorMessage = metadata.syncState.errorMessage,
            songs = catalog.songs,
            albums = catalog.albums,
            artists = catalog.artists,
            playlists = catalog.playlists,
            favorites = metadata.favorites,
            recentlyPlayed = metadata.recentlyPlayed,
            searchQuery = query,
            searchResult = searchResult,
            importedRoots = metadata.importedRoots,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryScreenState(),
    )

    init {
        viewModelScope.launch {
            libraryRepository.refreshLibraryIfNeeded()
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            libraryRepository.rescanLibrary()
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun clearSearch() {
        searchQuery.value = ""
    }

    fun setDefaultSortOption(option: LibrarySortOption) {
        viewModelScope.launch {
            settingsRepository.setDefaultSortOption(option)
        }
    }

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            libraryRepository.toggleFavorite(songId)
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name)
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(playlistId, name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            playlistRepository.addSongs(playlistId, listOf(songId))
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            playlistRepository.removeSong(playlistId, songId)
        }
    }

    fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            playlistRepository.moveSong(playlistId, fromIndex, toIndex)
        }
    }

    fun addImportedRoot(uri: Uri, displayName: String) {
        viewModelScope.launch {
            libraryRepository.addImportedRoot(uri.toString(), displayName)
        }
    }

    fun removeImportedRoot(uri: String) {
        viewModelScope.launch {
            libraryRepository.removeImportedRoot(uri)
        }
    }

    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        playlistRepository.observePlaylistSongs(playlistId)

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                LibraryViewModel(
                    libraryRepository = application.appContainer.libraryRepository,
                    playlistRepository = application.appContainer.playlistRepository,
                    settingsRepository = application.appContainer.settingsRepository,
                )
            }
        }
    }
}

class PlaybackViewModel(
    private val libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {
    val uiState: StateFlow<PlaybackUiState> = combine(
        playbackConnection.uiState,
        libraryRepository.observeSongs(),
    ) { playbackState, songs ->
        if (!playbackState.isConnected) return@combine playbackState
        val songMap = songs.associateBy { it.id }
        playbackState.copy(
            currentSong = playbackState.currentSong?.let { songMap[it.id] ?: it },
            queue = playbackState.queue.map { song -> songMap[song.id] ?: song },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlaybackUiState(),
    )

    fun playSong(queue: List<Song>, startIndex: Int) {
        if (startIndex !in queue.indices) return
        viewModelScope.launch {
            playbackConnection.playSong(queue, startIndex)
        }
    }

    fun playQueue(queue: List<Song>, startIndex: Int = 0) {
        if (queue.isEmpty() || startIndex !in queue.indices) return
        viewModelScope.launch {
            playbackConnection.playQueue(queue, startIndex)
        }
    }

    fun addToQueue(song: Song) {
        viewModelScope.launch {
            playbackConnection.addToQueue(song)
        }
    }

    fun togglePlayPause() = playbackConnection.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)

    fun skipNext() = playbackConnection.skipNext()

    fun skipPrevious() = playbackConnection.skipPrevious()

    fun toggleShuffle() {
        playbackConnection.setShuffleEnabled(!uiState.value.shuffleEnabled)
    }

    fun cycleRepeatMode() = playbackConnection.cycleRepeatMode()

    fun toggleFavoriteCurrent() {
        uiState.value.currentSong?.id?.let { songId ->
            viewModelScope.launch {
                libraryRepository.toggleFavorite(songId)
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                PlaybackViewModel(
                    libraryRepository = application.appContainer.libraryRepository,
                    playbackConnection = application.appContainer.playbackConnection,
                )
            }
        }
    }
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings(),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setShowFileNamesWhenMetadataMissing(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowFileNamesWhenMetadataMissing(enabled)
        }
    }

    fun setDefaultSortOption(option: LibrarySortOption) {
        viewModelScope.launch {
            settingsRepository.setDefaultSortOption(option)
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            libraryRepository.rescanLibrary()
        }
    }

    fun rebuildLibrary() {
        viewModelScope.launch {
            libraryRepository.rebuildLibrary()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                SettingsViewModel(
                    settingsRepository = application.appContainer.settingsRepository,
                    libraryRepository = application.appContainer.libraryRepository,
                )
            }
        }
    }
}

private fun songComparator(option: LibrarySortOption): Comparator<Song> =
    when (option) {
        LibrarySortOption.TITLE -> compareBy<Song> { it.resolvedTitle.normalized() }
            .thenBy { it.resolvedArtist.normalized() }
        LibrarySortOption.ARTIST -> compareBy<Song> { it.resolvedArtist.normalized() }
            .thenBy { it.resolvedAlbum.normalized() }
            .thenBy { it.resolvedTitle.normalized() }
        LibrarySortOption.ALBUM -> compareBy<Song> { it.resolvedAlbum.normalized() }
            .thenBy { it.discNumber ?: 0 }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.resolvedTitle.normalized() }
        LibrarySortOption.DURATION -> compareByDescending<Song> { it.durationMs }
            .thenBy { it.resolvedTitle.normalized() }
        LibrarySortOption.DATE_ADDED -> compareByDescending<Song> { it.dateAddedEpochSeconds }
            .thenBy { it.resolvedTitle.normalized() }
    }

private fun String.normalized(): String = trim().lowercase(Locale.US)

private data class LibraryCatalogSnapshot(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<UserPlaylist>,
)

private data class LibraryCatalogPresentation(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val playlists: List<UserPlaylist>,
)

private data class LibraryMetaSnapshot(
    val favorites: List<Song>,
    val recentlyPlayed: List<Song>,
    val importedRoots: List<ImportedRoot>,
    val syncState: LibrarySyncState,
)

private data class LibraryMetaPresentation(
    val favorites: List<Song>,
    val recentlyPlayed: List<Song>,
    val importedRoots: List<ImportedRoot>,
    val syncState: LibrarySyncState,
)
