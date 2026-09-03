package com.libreplayer.ui.screens

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.LibrarySyncState
import com.libreplayer.data.repository.PlaylistRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.albumBrowseSongs
import com.libreplayer.library.semantics.artistBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseSongs
import com.libreplayer.settings.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelBrowseProjectionTest {
    @Test
    fun `stale persisted aggregate rows cannot hide current song groups`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val songs = listOf(
                song("media:a1", album = "Shared", artist = "Artist A"),
                song("media:a2", album = "Shared", artist = "Artist A"),
                song("media:b1", album = "Shared", artist = "Artist B"),
            )
            val legacyAlbums = listOf(
                Album("shared|artist a", "Shared", "Artist A", 99, 99L, null),
            )
            val legacyArtists = listOf(
                Artist("artist a", "Artist A", 99, 99L, null),
            )
            val libraryRepository = mockk<LibraryRepository>()
            every { libraryRepository.observeSongs() } returns flowOf(songs)
            every { libraryRepository.observeAlbums() } returns flowOf(legacyAlbums)
            every { libraryRepository.observeArtists() } returns flowOf(legacyArtists)
            every { libraryRepository.observeFavorites() } returns flowOf(emptyList())
            every { libraryRepository.observeRecentlyPlayed(any()) } returns flowOf(emptyList())
            every { libraryRepository.observeImportedRoots() } returns flowOf(emptyList())
            every { libraryRepository.syncState } returns MutableStateFlow(LibrarySyncState())
            coEvery { libraryRepository.refreshLibraryIfNeeded() } just Runs

            val playlistRepository = mockk<PlaylistRepository>()
            every { playlistRepository.observePlaylists() } returns flowOf(emptyList())
            val settingsRepository = mockk<SettingsRepository>()
            every { settingsRepository.settings } returns flowOf(AppSettings())

            val state = LibraryViewModel(
                libraryRepository = libraryRepository,
                playlistRepository = playlistRepository,
                settingsRepository = settingsRepository,
            ).state.first { it.songs.size == songs.size }

            val artistAAlbumId = songs.first().albumBrowseGroupId()
            val artistAId = songs.first().artistBrowseGroupId()
            assertThat(state.albums).hasSize(2)
            assertThat(state.albums.single { it.id == artistAAlbumId }.songCount).isEqualTo(2)
            assertThat(albumBrowseSongs(state.songs, artistAAlbumId)).hasSize(2)
            assertThat(state.artists).hasSize(2)
            assertThat(state.artists.single { it.id == artistAId }.songCount).isEqualTo(2)
            assertThat(artistBrowseSongs(state.songs, artistAId)).hasSize(2)
            assertThat(state.albums.map(Album::id)).doesNotContain("shared|artist a")
            assertThat(state.artists.map(Artist::id)).doesNotContain("artist a")
            verify(exactly = 0) { libraryRepository.observeAlbums() }
            verify(exactly = 0) { libraryRepository.observeArtists() }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private fun song(
    id: String,
    album: String,
    artist: String,
) = Song(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://audio/$id",
    title = id,
    artist = artist,
    album = album,
    durationMs = 60_000L,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = 1L,
    displayName = "$id.mp3",
    relativePath = "Music/Q32/",
    mimeType = "audio/mpeg",
    artworkUri = null,
    isFavorite = false,
)
