package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.library.scanner.ScannedSong
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.albumBrowseSongs
import com.libreplayer.library.semantics.artistBrowseAlbums
import com.libreplayer.library.semantics.artistBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseSongs
import com.libreplayer.library.semantics.buildBrowseAlbums
import com.libreplayer.library.semantics.buildBrowseArtists
import org.junit.Test

class LibraryBrowseSemanticsTest {
    @Test
    fun `songs browsing retains each physical occurrence exactly once`() {
        val catalog = semanticCatalog()

        assertThat(catalog.songs.map(Song::id)).containsNoDuplicates()
        assertThat(catalog.songs.map(Song::id)).containsAtLeast("media:beta-twin-a", "media:beta-twin-b")
        assertThat(catalog.songs.filter { it.title == "Twin" }).hasSize(2)
    }

    @Test
    fun `album aggregates and detail membership share one authority`() {
        val catalog = semanticCatalog()
        val artistAAlphaId = song("probe", album = "Album Alpha", artist = "Artist A").albumBrowseGroupId()
        val artistBAlphaId = song("probe", album = "Album Alpha", artist = "Artist B").albumBrowseGroupId()
        val betaId = song("probe", album = "Album Beta", artist = "Artist A").albumBrowseGroupId()

        assertThat(artistAAlphaId).isNotEqualTo(artistBAlphaId)
        assertThat(albumBrowseSongs(catalog.songs, artistAAlphaId).map(Song::discNumber))
            .containsExactly(1, 1, 2)
        assertThat(albumBrowseSongs(catalog.songs, betaId).map(Song::id))
            .containsExactly("media:beta", "media:beta-twin-a", "media:beta-twin-b")
        catalog.albums.forEach { album ->
            val members = albumBrowseSongs(catalog.songs, album.id)
            assertThat(album.songCount).isEqualTo(members.size)
            assertThat(album.totalDurationMs).isEqualTo(members.sumOf(Song::durationMs))
        }
    }

    @Test
    fun `artist aggregates and detail albums share one authority`() {
        val catalog = semanticCatalog()
        val artistAId = song("probe", artist = "Artist A").artistBrowseGroupId()
        val artistASongs = artistBrowseSongs(catalog.songs, artistAId)
        val artistAAlbums = artistBrowseAlbums(catalog.albums, artistASongs)
        val aggregate = catalog.artists.single { it.id == artistAId }

        assertThat(artistASongs).hasSize(8)
        assertThat(aggregate.songCount).isEqualTo(artistASongs.size)
        assertThat(artistAAlbums.map(Album::id))
            .containsExactlyElementsIn(artistASongs.map(Song::albumBrowseGroupId).distinct())
        catalog.artists.forEach { artist ->
            assertThat(artist.songCount).isEqualTo(artistBrowseSongs(catalog.songs, artist.id).size)
        }
    }

    @Test
    fun `single-snapshot browse aggregates match persisted aggregate semantics`() {
        val catalog = semanticCatalog()

        assertThat(buildBrowseAlbums(catalog.songs)).containsExactlyElementsIn(catalog.albums)
        assertThat(buildBrowseArtists(catalog.songs)).containsExactlyElementsIn(catalog.artists)
    }

    @Test
    fun `legacy persisted aggregate IDs are not browse projection inputs`() {
        val catalog = semanticCatalog()
        val legacyAlbums = catalog.albums.map { album ->
            album.copy(id = "${album.title.trim().lowercase()}|${album.artist?.trim()?.lowercase() ?: "unknown artist"}")
        }
        val legacyArtists = catalog.artists.map { artist ->
            artist.copy(id = artist.name.trim().lowercase())
        }

        val currentAlbums = buildBrowseAlbums(catalog.songs)
        val currentArtists = buildBrowseArtists(catalog.songs)

        assertThat(currentAlbums.map(Album::id)).containsNoneIn(legacyAlbums.map(Album::id))
        assertThat(currentArtists.map(Artist::id)).containsNoneIn(legacyArtists.map(Artist::id))
        currentAlbums.forEach { album ->
            assertThat(album.songCount).isEqualTo(albumBrowseSongs(catalog.songs, album.id).size)
        }
        currentArtists.forEach { artist ->
            assertThat(artist.songCount).isEqualTo(artistBrowseSongs(catalog.songs, artist.id).size)
        }
    }

    @Test
    fun `missing groups remain distinct from literal fallback text`() {
        val missingArtist = song("missing", artist = null, album = "Orphans")
        val literalArtist = song("literal", artist = "Unknown Artist", album = "Orphans")
        val missingAlbum = song("missing-album", artist = "Artist A", album = null)
        val literalAlbum = song("literal-album", artist = "Artist A", album = "Unknown Album")

        assertThat(missingArtist.artistBrowseGroupId()).isNotEqualTo(literalArtist.artistBrowseGroupId())
        assertThat(missingArtist.albumBrowseGroupId()).isNotEqualTo(literalArtist.albumBrowseGroupId())
        assertThat(missingAlbum.albumBrowseGroupId()).isNotEqualTo(literalAlbum.albumBrowseGroupId())
        assertThat(missingArtist.resolvedArtist).isEqualTo("Unknown artist")
        assertThat(literalArtist.resolvedArtist).isEqualTo("Unknown Artist")
    }

    @Test
    fun `normalization is deterministic without fuzzy artist merging`() {
        assertThat(song("a", artist = " Artist A ").artistBrowseGroupId())
            .isEqualTo(song("b", artist = "artist a").artistBrowseGroupId())
        assertThat(song("dots", artist = "R.E.M.").artistBrowseGroupId())
            .isNotEqualTo(song("plain", artist = "REM").artistBrowseGroupId())
        assertThat(song("article", artist = "The Beatles").artistBrowseGroupId())
            .isNotEqualTo(song("no-article", artist = "Beatles").artistBrowseGroupId())
    }

    @Test
    fun `length-prefixed group IDs cannot collide through metadata separators`() {
        val separatorInAlbum = song("one", album = "A|B", artist = "C")
        val separatorInArtist = song("two", album = "A", artist = "B|C")

        assertThat(separatorInAlbum.albumBrowseGroupId())
            .isNotEqualTo(separatorInArtist.albumBrowseGroupId())
        assertThat(separatorInAlbum.albumBrowseGroupId()).contains("value:3:a|b")
    }

    @Test
    fun `browse selection preserves queue membership and selected occurrence identity`() {
        val catalog = semanticCatalog()
        val betaId = song("probe", album = "Album Beta", artist = "Artist A").albumBrowseGroupId()
        val displayedSongs = albumBrowseSongs(catalog.songs, betaId)
        val selectedIndex = displayedSongs.indexOfFirst { it.id == "media:beta-twin-b" }

        assertThat(displayedSongs.map(Song::id))
            .containsExactly("media:beta", "media:beta-twin-a", "media:beta-twin-b")
            .inOrder()
        assertThat(selectedIndex).isEqualTo(2)
        assertThat(displayedSongs[selectedIndex].contentUri).isEqualTo("content://audio/media:beta-twin-b")
    }

    @Test
    fun `refresh removal updates aggregate and detail membership together`() {
        val initial = listOf(
            scannedSong("media:one", album = "Refresh", artist = "Artist"),
            scannedSong("media:two", album = "Refresh", artist = "Artist"),
        )
        val currentSongs = initial.map { it.asEntity(false) }
        val refreshed = prepareLibraryChanges(
            scannedSongs = initial.take(1),
            currentSongs = currentSongs,
            currentAlbums = buildAlbums(currentSongs),
            currentArtists = buildArtists(currentSongs),
        )
        val modelSongs = refreshed.songs.map(SongEntity::asModel)
        val modelAlbum = refreshed.albums.single().asModel()

        assertThat(refreshed.deletedSongIds).containsExactly("media:two")
        assertThat(modelAlbum.songCount).isEqualTo(1)
        assertThat(albumBrowseSongs(modelSongs, modelAlbum.id)).hasSize(1)
    }
}

private data class SemanticCatalog(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
)

private fun semanticCatalog(): SemanticCatalog {
    val scanned = listOf(
        scannedSong("media:alpha-a-1", "Alpha 1", "Album Alpha", "Artist A", 1, 1),
        scannedSong("media:alpha-a-2", "Alpha 2", "Album Alpha", " artist a ", 2, 1),
        scannedSong("media:alpha-a-3", "Alpha 3", "Album Alpha", "Artist A", 1, 2),
        scannedSong("media:alpha-b", "Other Alpha", "Album Alpha", "Artist B"),
        scannedSong("media:beta", "Beta", "Album Beta", "Artist A"),
        scannedSong("media:beta-twin-a", "Twin", "Album Beta", "Artist A", 2, 1),
        scannedSong("media:beta-twin-b", "Twin", "Album Beta", "Artist A", 2, 1),
        scannedSong("media:missing-album", "Loose", null, "Artist A"),
        scannedSong("media:literal-album", "Literal album", "Unknown Album", "Artist A"),
        scannedSong("media:missing-artist", "Missing artist", "Orphans", null),
        scannedSong("media:literal-artist", "Literal artist", "Orphans", "Unknown Artist"),
        scannedSong("media:dots", "Dots", "Punctuation", "R.E.M."),
        scannedSong("media:plain", "Plain", "Punctuation", "REM"),
    )
    val entities = scanned.map { it.asEntity(false) }
    return SemanticCatalog(
        songs = entities.map(SongEntity::asModel),
        albums = buildAlbums(entities).map { it.asModel() },
        artists = buildArtists(entities).map { it.asModel() },
    )
}

private fun scannedSong(
    id: String,
    title: String? = id,
    album: String? = "Album",
    artist: String? = "Artist",
    track: Int? = 1,
    disc: Int? = 1,
) = ScannedSong(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://audio/$id",
    title = title,
    artist = artist,
    album = album,
    durationMs = 60_000L,
    trackNumber = track,
    discNumber = disc,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = 1L,
    displayName = "$id.mp3",
    relativePath = "Music/Q32/",
    mimeType = "audio/mpeg",
    artworkUri = null,
)

private fun song(
    id: String,
    album: String? = "Album",
    artist: String? = "Artist",
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
