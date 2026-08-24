package com.libreplayer.navigation

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class StartupReadinessTest {
    @Test
    fun cachedSongsAreReadyWhileRefreshContinues() {
        assertThat(
            isLibraryStartupReadyForFullyDrawn(
                LibraryScreenState(
                    isLoading = true,
                    songs = listOf(cachedSong()),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun initialLoadingWithoutCachedSongsIsNotReady() {
        assertThat(
            isLibraryStartupReadyForFullyDrawn(LibraryScreenState(isLoading = true)),
        ).isFalse()
    }

    @Test
    fun terminalVisibleStatesAreReady() {
        assertThat(
            listOf(
                LibraryScreenState(isLoading = false),
                LibraryScreenState(isLoading = true, permissionRequired = true),
                LibraryScreenState(isLoading = true, errorMessage = "Unavailable"),
            ).all(::isLibraryStartupReadyForFullyDrawn),
        ).isTrue()
    }

    private fun cachedSong() = Song(
        id = "cached-song",
        sourceType = SongSourceType.MEDIA_STORE,
        contentUri = "content://media/external/audio/media/1",
        displayName = "cached-song.mp3",
        title = "Cached song",
        artist = "Fixture artist",
        album = "Fixture album",
        durationMs = 31_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2026,
        dateAddedEpochSeconds = 0L,
        dateModifiedEpochSeconds = 0L,
        relativePath = "Music/LibrePlayerBenchmark/MEDIUM",
        mimeType = "audio/mpeg",
        artworkUri = null,
        isFavorite = false,
    )
}
