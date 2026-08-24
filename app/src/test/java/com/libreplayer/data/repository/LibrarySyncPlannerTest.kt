package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.library.scanner.ScannedSong
import org.junit.Test

class LibrarySyncPlannerTest {
    @Test
    fun `empty initial library imports all rows`() {
        val changes = prepareLibraryChanges(
            scannedSongs = listOf(scannedSong("media:1")),
            currentSongs = emptyList(),
            currentAlbums = emptyList(),
            currentArtists = emptyList(),
        )

        assertThat(changes.songUpserts).hasSize(1)
        assertThat(changes.albumUpserts).hasSize(1)
        assertThat(changes.artistUpserts).hasSize(1)
        assertThat(changes.deletedSongIds).isEmpty()
    }

    @Test
    fun `unchanged library performs no database writes`() {
        val scanned = listOf(scannedSong("media:1"), scannedSong("media:2"))
        val currentSongs = scanned.map { it.asEntity(isFavorite = false) }

        val changes = plan(scanned, currentSongs)

        assertThat(changes.hasChanges).isFalse()
    }

    @Test
    fun `one added track changes only song and affected album and artist`() {
        val original = scannedSong("media:1")
        val currentSongs = listOf(original.asEntity(isFavorite = false))

        val changes = plan(
            scanned = listOf(original, scannedSong("media:2")),
            currentSongs = currentSongs,
        )

        assertThat(changes.songUpserts.map(SongEntity::id)).containsExactly("media:2")
        assertThat(changes.albumUpserts).hasSize(1)
        assertThat(changes.artistUpserts).hasSize(1)
        assertThat(changes.deletedSongIds).isEmpty()
    }

    @Test
    fun `new album writes its tracks and affected aggregates`() {
        val original = scannedSong("media:1")
        val additions = (2..11).map { index ->
            scannedSong("media:$index", album = "New album", artist = "New artist")
        }

        val changes = plan(
            scanned = listOf(original) + additions,
            currentSongs = listOf(original.asEntity(isFavorite = false)),
        )

        assertThat(changes.songUpserts).hasSize(10)
        assertThat(changes.albumUpserts).hasSize(1)
        assertThat(changes.artistUpserts).hasSize(1)
    }

    @Test
    fun `metadata modification preserves favorite and updates old and new groups`() {
        val original = scannedSong("media:1", title = "Old", album = "Old album", artist = "Old artist")
        val modified = scannedSong("media:1", title = "New", album = "New album", artist = "New artist")
        val current = original.asEntity(isFavorite = true)

        val changes = plan(listOf(modified), listOf(current))

        assertThat(changes.songUpserts.single().isFavorite).isTrue()
        assertThat(changes.albumUpserts.single().title).isEqualTo("New album")
        assertThat(changes.deletedAlbumIds).containsExactly("old album|old artist")
        assertThat(changes.artistUpserts.single().name).isEqualTo("New artist")
        assertThat(changes.deletedArtistIds).containsExactly("old artist")
    }

    @Test
    fun `deleted tracks are removed and aggregates are updated`() {
        val first = scannedSong("media:1")
        val second = scannedSong("media:2")
        val third = scannedSong("media:3", album = "Other")
        val currentSongs = listOf(first, second, third).map { it.asEntity(isFavorite = false) }

        val changes = plan(listOf(first), currentSongs)

        assertThat(changes.deletedSongIds).containsExactly("media:2", "media:3")
        assertThat(changes.albumUpserts.single().songCount).isEqualTo(1)
        assertThat(changes.deletedAlbumIds).containsExactly("other|artist")
        assertThat(changes.artistUpserts.single().songCount).isEqualTo(1)
    }

    @Test
    fun `SAF move is represented as one deletion and one addition`() {
        val oldLocation = scannedSong("document:content://provider/old")
            .copy(sourceType = SongSourceType.DOCUMENT)
        val newLocation = oldLocation.copy(
            id = "document:content://provider/new",
            contentUri = "content://provider/new",
            relativePath = "/Music/new.mp3",
        )

        val changes = plan(
            scanned = listOf(newLocation),
            currentSongs = listOf(oldLocation.asEntity(isFavorite = false)),
        )

        assertThat(changes.deletedSongIds).containsExactly(oldLocation.id)
        assertThat(changes.songUpserts.map(SongEntity::id)).containsExactly(newLocation.id)
    }

    @Test
    fun `full and incremental planning converge on identical catalog`() {
        val finalScan = listOf(
            scannedSong("media:2", title = "Changed"),
            scannedSong("media:3", album = "New album"),
        )
        val staleSongs = listOf(
            scannedSong("media:1").asEntity(isFavorite = false),
            scannedSong("media:2").asEntity(isFavorite = false),
        )

        val incremental = plan(finalScan, staleSongs)
        val full = prepareLibraryChanges(finalScan, emptyList(), emptyList(), emptyList())

        assertThat(incremental.songs).containsExactlyElementsIn(full.songs)
        assertThat(incremental.albums).containsExactlyElementsIn(full.albums)
        assertThat(incremental.artists).containsExactlyElementsIn(full.artists)
    }

    private fun plan(
        scanned: List<ScannedSong>,
        currentSongs: List<SongEntity>,
    ): LibraryDatabaseChanges =
        prepareLibraryChanges(
            scannedSongs = scanned,
            currentSongs = currentSongs,
            currentAlbums = buildAlbums(currentSongs),
            currentArtists = buildArtists(currentSongs),
        )
}

private fun scannedSong(
    id: String,
    title: String = id,
    album: String = "Album",
    artist: String = "Artist",
) = ScannedSong(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://audio/$id",
    title = title,
    artist = artist,
    album = album,
    durationMs = 60_000L,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = 1L,
    displayName = "$id.mp3",
    relativePath = null,
    mimeType = "audio/mpeg",
    artworkUri = null,
)
