package com.libreplayer.ui.screens

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseGroupId
import org.junit.Test

class SongActionRoutingTest {
    @Test
    fun `song actions invoke queue album and artist destinations`() {
        val song = song()
        var queuedSong: Song? = null
        var albumDestination: String? = null
        var artistDestination: String? = null

        routeAddToQueue(song) { queuedSong = it }
        routeOpenAlbum(song) { albumDestination = it }
        routeOpenArtist(song) { artistDestination = it }

        assertThat(queuedSong).isSameInstanceAs(song)
        assertThat(albumDestination).isEqualTo(song.albumBrowseGroupId())
        assertThat(artistDestination).isEqualTo(song.artistBrowseGroupId())
    }

    @Test
    fun `playlist deletion confirmation invokes delete exactly once for target`() {
        val playlist = UserPlaylist(
            id = 42L,
            name = "Road trip",
            songCount = 3,
            updatedAtEpochMillis = 1L,
        )
        val deletedIds = mutableListOf<Long>()

        confirmPlaylistDeletion(playlist, deletedIds::add)

        assertThat(deletedIds).containsExactly(42L)
    }
}

private fun song() = Song(
    id = "song",
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://audio/song",
    title = "Title",
    artist = "Artist",
    album = "Album",
    durationMs = 60_000L,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = 1L,
    displayName = "title.mp3",
    relativePath = "Music/",
    mimeType = "audio/mpeg",
    artworkUri = null,
    isFavorite = false,
)
