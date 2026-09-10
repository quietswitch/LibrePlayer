package com.libreplayer.ui.screens

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.LibrarySyncState
import com.libreplayer.data.repository.PlaylistRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.settings.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
class LibraryViewModelSortSearchTest {
    @Test
    fun `latest query refresh clear and active sort remain separate`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val slow = song("media:slow", "Alpha", "Needle Artist", "Needle Album", 100L)
            val fast = song("media:fast", "Zulu", "Needle Artist", "Needle Album", 300L)
            val twinA = song("media:twin-a", "Twin", "Artist", "Duplicates", 200L)
            val twinB = song("media:twin-b", "Twin", "Artist", "Duplicates", 200L)
            val songsFlow = MutableStateFlow(listOf(fast, twinB, slow, twinA))
            val settingsFlow = MutableStateFlow(AppSettings(defaultSortOption = LibrarySortOption.TITLE))
            val viewModel = viewModel(songsFlow, settingsFlow)

            assertThat(viewModel.state.first { it.songs.size == 4 }.songs.map(Song::id))
                .containsExactly("media:slow", "media:twin-a", "media:twin-b", "media:fast").inOrder()

            viewModel.setSearchQuery("a")
            viewModel.setSearchQuery("al")
            viewModel.setSearchQuery("album")
            viewModel.setSearchQuery("zzz")
            val latest = viewModel.state.first { it.searchQuery == "zzz" }
            assertThat(latest.searchResult.songs).isEmpty()

            viewModel.setSearchQuery("needle")
            val titleSorted = viewModel.state.first { it.searchQuery == "needle" && it.searchResult.songs.size == 2 }
            assertThat(titleSorted.searchResult.songs.map(Song::id))
                .containsExactly("media:slow", "media:fast").inOrder()

            viewModel.setDefaultSortOption(LibrarySortOption.DURATION)
            val durationSorted = viewModel.state.first {
                it.searchQuery == "needle" && it.searchResult.songs.firstOrNull()?.id == "media:fast"
            }
            assertThat(durationSorted.searchResult.songs.map(Song::id))
                .containsExactly("media:fast", "media:slow").inOrder()

            viewModel.setSearchQuery("twin")
            val duplicates = viewModel.state.first { it.searchQuery == "twin" && it.searchResult.songs.size == 2 }
            assertThat(duplicates.searchResult.songs.map(Song::id))
                .containsExactly("media:twin-a", "media:twin-b").inOrder()
            assertThat(duplicates.searchResult.songs.map(Song::contentUri)).containsNoDuplicates()

            songsFlow.value = listOf(fast, slow, twinA)
            val refreshed = viewModel.state.first {
                it.songs.size == 3 && it.searchQuery == "twin" &&
                    it.searchResult.songs.map(Song::id) == listOf("media:twin-a")
            }
            assertThat(refreshed.songs).hasSize(3)

            viewModel.clearSearch()
            val cleared = viewModel.state.first { it.searchQuery.isEmpty() }
            assertThat(cleared.searchResult.songs).isEmpty()
            assertThat(cleared.songs.map(Song::id))
                .containsExactly("media:fast", "media:twin-a", "media:slow").inOrder()
            assertThat(cleared.songs.first().contentUri).isEqualTo("content://audio/media:fast")
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        songsFlow: MutableStateFlow<List<Song>>,
        settingsFlow: MutableStateFlow<AppSettings>,
    ): LibraryViewModel {
        val libraryRepository = mockk<LibraryRepository>()
        every { libraryRepository.observeSongs() } returns songsFlow
        every { libraryRepository.observeFavorites() } returns flowOf(emptyList())
        every { libraryRepository.observeRecentlyPlayed(any()) } returns flowOf(emptyList())
        every { libraryRepository.observeImportedRoots() } returns flowOf(emptyList())
        every { libraryRepository.syncState } returns MutableStateFlow(LibrarySyncState())
        coEvery { libraryRepository.refreshLibraryIfNeeded() } just Runs

        val playlistRepository = mockk<PlaylistRepository>()
        every { playlistRepository.observePlaylists() } returns flowOf(emptyList())
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } returns settingsFlow
        coEvery { settingsRepository.setDefaultSortOption(any()) } answers {
            settingsFlow.value = settingsFlow.value.copy(defaultSortOption = firstArg<LibrarySortOption>())
        }
        return LibraryViewModel(libraryRepository, playlistRepository, settingsRepository)
    }
}

private fun song(
    id: String,
    title: String,
    artist: String,
    album: String,
    durationMs: Long,
) = Song(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://audio/$id",
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = durationMs,
    dateModifiedEpochSeconds = 1L,
    displayName = "$id.mp3",
    relativePath = "Music/Q33/",
    mimeType = "audio/mpeg",
    artworkUri = null,
    isFavorite = false,
)
