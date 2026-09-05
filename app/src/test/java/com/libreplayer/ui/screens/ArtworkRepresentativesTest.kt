package com.libreplayer.ui.screens

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseGroupId
import org.junit.Test

class ArtworkRepresentativesTest {
    @Test
    fun `album representative follows track order and ignores stale persisted aggregate`() {
        val songs = listOf(
            song(
                id = "2",
                title = "Track 2",
                artist = "Artist",
                album = "Album",
                trackNumber = 2,
                artworkUri = "content://art/2",
                contentUri = "content://audio/2",
            ),
            song(
                id = "1",
                title = "Track 1",
                artist = "Artist",
                album = "Album",
                trackNumber = 1,
                artworkUri = "content://art/1",
                contentUri = "content://audio/1",
            ),
        )

        val albums = enrichAlbumsWithArtwork(
            albums = listOf(
                Album(
                    songs.first().albumBrowseGroupId(),
                    "Album",
                    "Artist",
                    2,
                    480_000L,
                    "content://stale/persisted",
                ),
            ),
            songs = songs,
        )

        assertThat(albums.single().artworkCandidates.map { it.uri }).containsExactly(
            "content://art/1",
            "content://audio/1",
            "content://art/2",
            "content://audio/2",
        ).inOrder()
        assertThat(albums.single().artworkUri).isEqualTo("content://art/1")
        assertThat(albums.single().artworkFallbackUri).isEqualTo("content://audio/1")
    }

    @Test
    fun `partial-art album retains later member candidates after missing first member`() {
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
            song(
                id = "11",
                title = "Track 2",
                artist = "Artist",
                album = "Album",
                trackNumber = 2,
                artworkUri = "content://media/albumart/11",
                contentUri = "content://media/audio/11",
            ),
        )

        val albums = enrichAlbumsWithArtwork(
            albums = listOf(Album(songs.first().albumBrowseGroupId(), "Album", "Artist", 2, 480_000L, null)),
            songs = songs,
        )

        assertThat(albums.single().artworkCandidates.map { it.uri }).containsExactly(
            "content://media/audio/10",
            "content://media/albumart/11",
            "content://media/audio/11",
        ).inOrder()
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
            artists = listOf(Artist(songs.first().artistBrowseGroupId(), "Artist", 2, 480_000L, null)),
            songs = songs,
        )

        assertThat(artists.single().artworkUri).isEqualTo("content://media/albumart/1")
        assertThat(artists.single().artworkFallbackUri).isEqualTo("content://media/audio/1")
    }

    @Test
    fun `same-title albums for different artists never exchange candidates`() {
        val artistA = song(
            id = "a",
            title = "A",
            artist = "Artist A",
            album = "Greatest Hits",
            trackNumber = 1,
            artworkUri = "content://art/red",
            contentUri = "content://audio/a",
        )
        val artistB = song(
            id = "b",
            title = "B",
            artist = "Artist B",
            album = "Greatest Hits",
            trackNumber = 1,
            artworkUri = "content://art/blue",
            contentUri = "content://audio/b",
        )
        val albums = enrichAlbumsWithArtwork(
            albums = listOf(
                Album(artistA.albumBrowseGroupId(), "Greatest Hits", "Artist A", 1, 1L, null),
                Album(artistB.albumBrowseGroupId(), "Greatest Hits", "Artist B", 1, 1L, null),
            ),
            songs = listOf(artistB, artistA),
        ).associateBy { it.id }

        assertThat(artistA.albumBrowseGroupId()).isNotEqualTo(artistB.albumBrowseGroupId())
        assertThat(albums.getValue(artistA.albumBrowseGroupId()).artworkUri).isEqualTo("content://art/red")
        assertThat(albums.getValue(artistB.albumBrowseGroupId()).artworkUri).isEqualTo("content://art/blue")
    }

    @Test
    fun `representative member deletion advances without changing album identity`() {
        val first = song("1", "First", "Artist", "Album", 1, "content://art/1", "content://audio/1")
        val second = song("2", "Second", "Artist", "Album", 2, "content://art/2", "content://audio/2")
        val album = Album(first.albumBrowseGroupId(), "Album", "Artist", 2, 2L, null)

        val before = enrichAlbumsWithArtwork(listOf(album), listOf(second, first)).single()
        val after = enrichAlbumsWithArtwork(listOf(album.copy(songCount = 1)), listOf(second)).single()

        assertThat(before.id).isEqualTo(after.id)
        assertThat(before.artworkUri).isEqualTo("content://art/1")
        assertThat(after.artworkUri).isEqualTo("content://art/2")
    }

    @Test
    fun `duplicate metadata occurrences retain distinct artwork locators and song identities`() {
        val first = song("media:1", "Twin", "Artist", "Album", 1, "content://art/red", "content://audio/1")
        val second = song("document:2", "Twin", "Artist", "Album", 1, "content://art/green", "content://document/2")

        val representative = selectAlbumRepresentativeArtwork(listOf(second, first))

        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(representative.candidates.map { it.uri }).containsAtLeast(
            "content://art/red",
            "content://art/green",
        )
        assertThat(first.albumBrowseGroupId()).isEqualTo(second.albumBrowseGroupId())
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
    dateModifiedEpochSeconds = 1_700_000_100L + trackNumber,
    displayName = "$title.mp3",
    relativePath = "Music/$album/",
    mimeType = "audio/mpeg",
    artworkUri = artworkUri,
    isFavorite = false,
)
