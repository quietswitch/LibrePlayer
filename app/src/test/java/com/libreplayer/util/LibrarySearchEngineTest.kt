package com.libreplayer.util

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.SearchResult
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import java.util.Locale
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

    @Test
    fun `search trims and uses locale independent case normalization`() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val songs = listOf(song(id = "1", title = "INDIGO", artist = "Artist", album = "Album"))

            assertThat(LibrarySearchEngine.search("  indigo  ", songs, emptyList(), emptyList()).songs)
                .containsExactlyElementsIn(songs)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `song scope is presentation title artist album and filename but not identity or path`() {
        val fields = listOf(
            song("title", "Blue Moon", "One", "First"),
            song("artist", "Second", "Needle Artist", "Second"),
            song("album", "Third", "Three", "Needle Album"),
            song("filename", "Fourth", "Four", "Fourth", displayName = "needle-file.mp3"),
            song(
                "needle-internal-id",
                "Opaque",
                "Five",
                "Fifth",
                displayName = "opaque.mp3",
                relativePath = "Secret/needle/path/",
                contentUri = "content://needle/private",
            ),
        )

        assertThat(LibrarySearchEngine.search("blue moon", fields, emptyList(), emptyList()).songs.map(Song::id))
            .containsExactly("title")
        assertThat(LibrarySearchEngine.search("needle artist", fields, emptyList(), emptyList()).songs.map(Song::id))
            .containsExactly("artist")
        assertThat(LibrarySearchEngine.search("needle album", fields, emptyList(), emptyList()).songs.map(Song::id))
            .containsExactly("album")
        assertThat(LibrarySearchEngine.search("needle-file", fields, emptyList(), emptyList()).songs.map(Song::id))
            .containsExactly("filename")
        assertThat(LibrarySearchEngine.search("private", fields, emptyList(), emptyList()).songs).isEmpty()
        assertThat(LibrarySearchEngine.search("internal-id", fields, emptyList(), emptyList()).songs).isEmpty()
    }

    @Test
    fun `matching is one punctuation-preserving contiguous substring`() {
        val songs = listOf(
            song("dots", "Live", "R.E.M.", "Blue Moon Rising"),
        )

        assertThat(LibrarySearchEngine.search("r.e.m.", songs, emptyList(), emptyList()).songs).hasSize(1)
        assertThat(LibrarySearchEngine.search("rem", songs, emptyList(), emptyList()).songs).isEmpty()
        assertThat(LibrarySearchEngine.search("blue moon", songs, emptyList(), emptyList()).songs).hasSize(1)
        assertThat(LibrarySearchEngine.search("blue rising", songs, emptyList(), emptyList()).songs).isEmpty()
    }

    @Test
    fun `missing and literal fallback values both match without merging identities`() {
        val songs = listOf(
            song("missing", "Missing", null, null),
            song("literal", "Literal", "Unknown Artist", "Unknown Album"),
        )
        val albums = listOf(
            Album("missing-album", "Unknown album", null, 1, 1L, null),
            Album("literal-album", "Unknown Album", "Unknown Artist", 1, 1L, null),
        )
        val artists = listOf(
            Artist("missing-artist", "Unknown artist", 1, 1L, null),
            Artist("literal-artist", "Unknown Artist", 1, 1L, null),
        )

        val artistResult = LibrarySearchEngine.search("unknown artist", songs, albums, artists)
        assertThat(artistResult.songs.map(Song::id)).containsExactly("missing", "literal")
        assertThat(artistResult.artists.map(Artist::id)).containsExactly("missing-artist", "literal-artist")
        assertThat(LibrarySearchEngine.search("unknown album", songs, albums, artists).albums.map(Album::id))
            .containsExactly("missing-album", "literal-album")
    }

    @Test
    fun `search preserves input order and duplicate source occurrences`() {
        val songs = listOf(
            song("media:b", "Twin", "Artist", "Album"),
            song("media:a", "Twin", "Artist", "Album"),
        )

        val result = LibrarySearchEngine.search("twin", songs, emptyList(), emptyList())

        assertThat(result.songs.map(Song::id)).containsExactly("media:b", "media:a").inOrder()
        assertThat(result.songs.map(Song::contentUri)).containsNoDuplicates()
        assertThat(LibrarySearchEngine.search("no match", songs, emptyList(), emptyList())).isEqualTo(SearchResult())
    }

    private fun song(
        id: String,
        title: String,
        artist: String?,
        album: String?,
        displayName: String = "$title.mp3",
        relativePath: String = "Music/",
        contentUri: String = "content://song/$id",
    ) = Song(
        id = id,
        sourceType = SongSourceType.MEDIA_STORE,
            contentUri = contentUri,
        title = title,
        artist = artist,
        album = album,
        durationMs = 240_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2011,
        dateAddedEpochSeconds = 1L,
        dateModifiedEpochSeconds = 1L,
            displayName = displayName,
            relativePath = relativePath,
        mimeType = "audio/mpeg",
        artworkUri = null,
        isFavorite = false,
    )
}
