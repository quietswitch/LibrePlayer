package com.libreplayer.ui.screens

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class ArtworkRepresentativesTest {
    @Test
    fun `album representative prefers stored artwork and keeps content fallback`() {
        val songs = listOf(
            song(
                id = "1",
                title = "Track 1",
                artist = "Artist",
                album = "Album",
                trackNumber = 1,
                artworkUri = "content://media/albumart/1",
                contentUri = "content://media/audio/1",
            ),
            song(
                id = "2",
                title = "Track 2",
                artist = "Artist",
                album = "Album",
                trackNumber = 2,
                artworkUri = null,
                contentUri = "content://media/audio/2",
            ),
        )

        val albums = enrichAlbumsWithArtwork(
            albums = listOf(Album("album|artist", "Album", "Artist", 2, 480_000L, null)),
            songs = songs,
        )

        assertThat(albums.single().artworkUri).isEqualTo("content://media/albumart/1")
        assertThat(albums.single().artworkFallbackUri).isEqualTo("content://media/audio/1")
    }

    @Test
    fun `album representative falls back to file uri when no explicit art uri exists`() {
        val songs = listOf(
            song(
                id = "10",
                title = "Track 1",
                artist = "Artist",
                album = "Album",
                trackNumber = 1,
                artworkUri = null,
                contentUri = "content://media/audio/10",
            ),
        )

        val albums = enrichAlbumsWithArtwork(
            albums = listOf(Album("album|artist", "Album", "Artist", 1, 240_000L, null)),
            songs = songs,
        )

        assertThat(albums.single().artworkUri).isEqualTo("content://media/audio/10")
        assertThat(albums.single().artworkFallbackUri).isNull()
    }

    @Test
    fun `artist representative uses deterministic first artwork candidate`() {
        val songs = listOf(
            song(
                id = "2",
                title = "Beta",
                artist = "Artist",
                album = "Second Album",
                trackNumber = 1,
                artworkUri = "content://media/albumart/2",
                contentUri = "content://media/audio/2",
            ),
            song(
                id = "1",
                title = "Alpha",
                artist = "Artist",
                album = "First Album",
                trackNumber = 1,
                artworkUri = "content://media/albumart/1",
                contentUri = "content://media/audio/1",
            ),
        )

        val artists = enrichArtistsWithArtwork(
            artists = listOf(Artist("artist", "Artist", 2, 480_000L, null)),
            songs = songs,
        )

        assertThat(artists.single().artworkUri).isEqualTo("content://media/albumart/1")
        assertThat(artists.single().artworkFallbackUri).isEqualTo("content://media/audio/1")
    }
}

private fun song(
    id: String,
    title: String,
    artist: String,
    album: String,
    trackNumber: Int,
    artworkUri: String?,
    contentUri: String,
) = Song(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = contentUri,
    title = title,
    artist = artist,
    album = album,
    durationMs = 240_000L,
    trackNumber = trackNumber,
    discNumber = 1,
    year = 2024,
    dateAddedEpochSeconds = 1_700_000_000L,
    dateModifiedEpochSeconds = 1_700_000_100L,
    displayName = "$title.mp3",
    relativePath = "Music/$album/",
    mimeType = "audio/mpeg",
    artworkUri = artworkUri,
    isFavorite = false,
)
