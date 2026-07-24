package com.libreplayer.util

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.SearchResult
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class LibrarySearchEngineTest {
    @Test
    fun `search matches songs albums and artists case-insensitively`() {
        val songs = listOf(song(id = "1", title = "Midnight City", artist = "M83", album = "Hurry Up"))
        val albums = listOf(Album("album1", "Hurry Up", "M83", 1, 240_000L, null))
        val artists = listOf(Artist("artist1", "M83", 1, 240_000L, null))

        val result = LibrarySearchEngine.search("m83", songs, albums, artists)

        assertThat(result.songs).hasSize(1)
        assertThat(result.albums).hasSize(1)
        assertThat(result.artists).hasSize(1)
    }

    @Test
    fun `search returns empty result for blank query`() {
        val result = LibrarySearchEngine.search("  ", emptyList(), emptyList(), emptyList())

        assertThat(result).isEqualTo(SearchResult())
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        album: String,
    ) = Song(
        id = id,
        sourceType = SongSourceType.MEDIA_STORE,
        contentUri = "content://song/$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = 240_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2011,
        dateAddedEpochSeconds = 1L,
        dateModifiedEpochSeconds = 1L,
        displayName = "$title.mp3",
        relativePath = "Music/",
        mimeType = "audio/mpeg",
        artworkUri = null,
        isFavorite = false,
    )
}

