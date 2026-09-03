package com.libreplayer.library.semantics

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class LibraryOrderingSemanticsTest {
    @Test
    fun `every exposed Song sort is deterministic with occurrence ID tie breakers`() {
        val songs = listOf(
            song("1", "Same", "Beta", "Alpha", durationMs = 100L, added = 20L, disc = 1, track = 10),
            song("2", " same ", "Alpha", "Beta", durationMs = 200L, added = 10L, disc = 1, track = 2),
            song("3", "Zulu", "Alpha", "Alpha", durationMs = 0L, added = 0L),
            song("0", "Same", "Beta", "Alpha", durationMs = 100L, added = 20L, disc = 1, track = 10),
        )

        assertThat(sortSongs(songs, LibrarySortOption.TITLE).map(Song::id))
            .containsExactly("2", "0", "1", "3").inOrder()
        assertThat(sortSongs(songs, LibrarySortOption.ARTIST).map(Song::id))
            .containsExactly("3", "2", "0", "1").inOrder()
        assertThat(sortSongs(songs, LibrarySortOption.ALBUM).map(Song::id))
            .containsExactly("3", "0", "1", "2").inOrder()
        assertThat(sortSongs(songs, LibrarySortOption.DURATION).map(Song::id))
            .containsExactly("2", "0", "1", "3").inOrder()
        assertThat(sortSongs(songs, LibrarySortOption.DATE_ADDED).map(Song::id))
            .containsExactly("0", "1", "2", "3").inOrder()
    }

    @Test
    fun `album order uses title artist and semantic ID`() {
        val albums = listOf(
            Album("album-z", "Shared", "Artist B", 1, 1L, null),
            Album("album-b", " shared ", "Artist A", 1, 1L, null),
            Album("album-a", "Shared", "Artist A", 1, 1L, null),
            Album("album-missing", "Unknown album", null, 1, 1L, null),
        )

        assertThat(sortAlbums(albums).map(Album::id))
            .containsExactly("album-a", "album-b", "album-z", "album-missing").inOrder()
    }

    @Test
    fun `artist order preserves punctuation and articles with semantic ID ties`() {
        val artists = listOf(
            Artist("rem", "REM", 1, 1L, null),
            Artist("dots", "R.E.M.", 1, 1L, null),
            Artist("the", "The Beatles", 1, 1L, null),
            Artist("beatles", "Beatles", 1, 1L, null),
            Artist("unknown-literal", "Unknown Artist", 1, 1L, null),
            Artist("unknown-missing", "Unknown artist", 1, 1L, null),
        )

        assertThat(sortArtists(artists).map(Artist::id))
            .containsExactly("beatles", "dots", "rem", "the", "unknown-literal", "unknown-missing")
            .inOrder()
    }

    @Test
    fun `missing Song text uses presentation fallbacks without merging occurrences`() {
        val songs = listOf(
            song(
                id = "z-missing",
                title = null,
                artist = null,
                album = null,
                displayName = "Alpha.mp3",
            ),
            song(
                id = "a-literal",
                title = "Alpha",
                artist = "Unknown Artist",
                album = "Unknown Album",
            ),
        )

        listOf(
            LibrarySortOption.TITLE,
            LibrarySortOption.ARTIST,
            LibrarySortOption.ALBUM,
        ).forEach { option ->
            assertThat(sortSongs(songs, option).map(Song::id))
                .containsExactly("a-literal", "z-missing").inOrder()
        }
    }

    @Test
    fun `album tracks use numeric disc track missing-last title and ID order`() {
        val tracks = listOf(
            song("d2t1", "Disc two", disc = 2, track = 1),
            song("missing-disc", "No disc", disc = null, track = 1),
            song("d1t10", "Ten", disc = 1, track = 10),
            song("duplicate-b", "Duplicate", disc = 1, track = 2),
            song("d1t1", "One", disc = 1, track = 1),
            song("missing-track", "No track", disc = 1, track = null),
            song("duplicate-a", "Duplicate", disc = 1, track = 2),
        )

        assertThat(sortAlbumTracks(tracks).map(Song::id))
            .containsExactly(
                "d1t1",
                "duplicate-a",
                "duplicate-b",
                "d1t10",
                "missing-track",
                "d2t1",
                "missing-disc",
            )
            .inOrder()
    }
}

private fun song(
    id: String,
    title: String? = id,
    artist: String? = "Artist",
    album: String? = "Album",
    durationMs: Long = 1L,
    added: Long = 1L,
    disc: Int? = null,
    track: Int? = null,
    displayName: String = "$id.mp3",
) = Song(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://audio/$id",
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    trackNumber = track,
    discNumber = disc,
    year = 2026,
    dateAddedEpochSeconds = added,
    dateModifiedEpochSeconds = 1L,
    displayName = displayName,
    relativePath = "Music/Q33/",
    mimeType = "audio/mpeg",
    artworkUri = null,
    isFavorite = false,
)
