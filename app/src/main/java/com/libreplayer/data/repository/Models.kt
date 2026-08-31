package com.libreplayer.data.repository

import android.net.Uri
import androidx.media3.common.Player
import java.time.Instant

enum class SongSourceType {
    MEDIA_STORE,
    DOCUMENT,
}

enum class LibrarySortOption {
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
    DATE_ADDED,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class SmartPlaylistType {
    FAVORITES,
    RECENTLY_PLAYED,
}

data class Song(
    val id: String,
    val sourceType: SongSourceType,
    val contentUri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val dateAddedEpochSeconds: Long,
    val dateModifiedEpochSeconds: Long,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val artworkUri: String?,
    val isFavorite: Boolean,
) {
    val resolvedTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.')

    val resolvedArtist: String
        get() = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"

    val resolvedAlbum: String
        get() = album?.takeIf { it.isNotBlank() } ?: "Unknown album"

    val content: Uri
        get() = Uri.parse(contentUri)

    val artwork: Uri?
        get() = artworkUri?.let(Uri::parse)

    val addedAt: Instant
        get() = Instant.ofEpochSecond(dateAddedEpochSeconds)
}

data class Album(
    val id: String,
    val title: String,
    val artist: String?,
    val songCount: Int,
    val totalDurationMs: Long,
    val artworkUri: String?,
    val artworkFallbackUri: String? = null,
)

data class Artist(
    val id: String,
    val name: String,
    val songCount: Int,
    val totalDurationMs: Long,
    val artworkUri: String?,
    val artworkFallbackUri: String? = null,
)

data class UserPlaylist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val updatedAtEpochMillis: Long,
) {
    val lastUpdated: Instant
        get() = Instant.ofEpochMilli(updatedAtEpochMillis)
}

data class PlaylistSong(
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val song: Song,
)

data class ImportedRoot(
    val uri: String,
    val displayName: String,
    val addedAtEpochMillis: Long,
)

data class SearchResult(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showFileNamesWhenMetadataMissing: Boolean = true,
    val defaultSortOption: LibrarySortOption = LibrarySortOption.TITLE,
)

data class LibraryScreenState(
    val isLoading: Boolean = true,
    val permissionRequired: Boolean = false,
    val errorMessage: String? = null,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val favorites: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val searchQuery: String = "",
    val searchResult: SearchResult = SearchResult(),
    val importedRoots: List<ImportedRoot> = emptyList(),
)

data class QueueEntry(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
)

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val playbackSuppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    val primaryControlShowsPause: Boolean = false,
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val playWhenReady: Boolean = false,
    val errorMessage: String? = null,
)
