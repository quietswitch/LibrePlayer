package com.libreplayer.library.semantics

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.asModel
import com.libreplayer.data.repository.asEntity
import com.libreplayer.data.repository.buildAlbums
import com.libreplayer.data.repository.buildArtists
import com.libreplayer.library.scanner.ScannedSong
import com.libreplayer.util.LibrarySearchEngine
import org.junit.Test

class MetadataPathologySemanticsTest {
    @Test
    fun `raw metadata survives while blank presentation and grouping use missing semantics`() {
        val rawTitle = " \t\n "
        val rawArtist = "  Artist\tName  "
        val rawAlbum = "\r\n"
        val entity = scannedSong(
            id = "media:raw",
            title = rawTitle,
            artist = rawArtist,
            album = rawAlbum,
            displayName = "Raw Fallback.mp3",
        ).asEntity(isFavorite = false)
        val model = entity.asModel()

        assertThat(entity.title).isEqualTo(rawTitle)
        assertThat(entity.artist).isEqualTo(rawArtist)
        assertThat(entity.album).isEqualTo(rawAlbum)
        assertThat(model.resolvedTitle).isEqualTo("Raw Fallback")
        assertThat(model.resolvedArtist).isEqualTo(rawArtist)
        assertThat(model.resolvedAlbum).isEqualTo(UNKNOWN_ALBUM)
        assertThat(model.artistBrowseGroupId()).isEqualTo(artistGroupingKey("artist\tname"))
        assertThat(model.albumBrowseGroupId()).isEqualTo(albumGroupingKey(null, null, rawArtist))
    }

    @Test
    fun `blank groups remain visible through fallback but distinct from fallback-like literals`() {
        val songs = listOf(
            scannedSong("media:null", artist = null, album = null),
            scannedSong("media:empty", artist = "", album = ""),
            scannedSong("media:whitespace", artist = " \t\n", album = "\r\n"),
            scannedSong("media:literal", artist = "Unknown Artist", album = "Unknown Album"),
        )
        val entities = songs.map { it.asEntity(isFavorite = false) }
        val artists = buildArtists(entities)
        val albums = buildAlbums(entities)

        assertThat(artists.map { it.name }).containsExactly(UNKNOWN_ARTIST, "Unknown Artist")
        assertThat(albums.map { it.title }).containsExactly(UNKNOWN_ALBUM, "Unknown Album")
        assertThat(artistGroupingKey(null)).isEqualTo(artistGroupingKey(" \t\n"))
        assertThat(artistGroupingKey(null)).isNotEqualTo(artistGroupingKey("Unknown Artist"))
        assertThat(albumGroupingKey(null, null, null))
            .isNotEqualTo(albumGroupingKey("Unknown Album", null, null))
    }

    @Test
    fun `unicode punctuation controls and typed components remain exact and collision-free`() {
        val precomposed = "Caf\u00e9 \u03a9 \u0416 \u6771\u4eac \u0645\u0631\u062d\u0628\u0627 \ud83d\ude00"
        val decomposed = "Cafe\u0301 \u03a9 \u0416 \u6771\u4eac \u0645\u0631\u062d\u0628\u0627 \ud83d\ude00"
        val punctuation = "A|B:C/D\\E%F?G#H&I\"J'K(L)[M]"
        val controls = "Line one\nLine two\rTabbed\tvalue"

        assertThat(artistGroupingKey(precomposed)).isNotEqualTo(artistGroupingKey(decomposed))
        assertThat(artistGroupingKey(punctuation)).contains(punctuation.lowercase())
        assertThat(artistGroupingKey(controls)).contains("\n")
        assertThat(
            albumGroupingKey("A|B", null, "C:D"),
        ).isNotEqualTo(albumGroupingKey("A", null, "B|C:D"))

        val model = scannedSong(
            id = "media:unicode",
            title = controls,
            artist = precomposed,
            album = punctuation,
        ).asEntity(false).asModel()
        assertThat(model.title).isEqualTo(controls)
        assertThat(model.artist).isEqualTo(precomposed)
        assertThat(model.album).isEqualTo(punctuation)
    }

    @Test
    fun `bounded long metadata groups sorts and searches without truncation`() {
        val title = "T".repeat(1_017) + "-needle"
        val artist = "A".repeat(4_096)
        val album = "B".repeat(4_096)
        val longSong = scannedSong(
            id = "media:long",
            title = title,
            artist = artist,
            album = album,
        ).asEntity(false).asModel()
        val control = scannedSong("media:control", title = "Control").asEntity(false).asModel()
        val songs = listOf(longSong, control)
        val albums = buildBrowseAlbums(songs)
        val artists = buildBrowseArtists(songs)

        assertThat(longSong.title).hasLength(1_024)
        assertThat(longSong.artist).hasLength(4_096)
        assertThat(longSong.album).hasLength(4_096)
        assertThat(longSong.albumBrowseGroupId()).isEqualTo(longSong.albumBrowseGroupId())
        assertThat(longSong.artistBrowseGroupId()).isEqualTo(longSong.artistBrowseGroupId())
        assertThat(sortSongs(songs, LibrarySortOption.TITLE)).containsExactly(control, longSong).inOrder()
        assertThat(LibrarySearchEngine.search("needle", songs, albums, artists).songs)
            .containsExactly(longSong)
    }

    @Test
    fun `pathological ordinals remain positive and preserve numeric album order`() {
        val songs = listOf(
            modelSong("track-10", track = parseMetadataOrdinal("10/12")),
            modelSong("track-missing", track = parseMetadataOrdinal("999999999999999999")),
            modelSong("track-2", track = parseMetadataOrdinal("02/12")),
            modelSong("track-zero", track = parseMetadataOrdinal("0/12")),
        )

        assertThat(sortAlbumTracks(songs).map(Song::id))
            .containsExactly("track-2", "track-10", "track-missing", "track-zero")
            .inOrder()
        assertThat(songs).containsNoneOf(
            songs[1].copy(trackNumber = 0),
            songs[3].copy(trackNumber = -1),
        )
    }
}

private fun modelSong(id: String, track: Int?): Song =
    scannedSong(id = id, title = id, track = track).asEntity(false).asModel()

private fun scannedSong(
    id: String,
    title: String? = id,
    artist: String? = "Artist",
    album: String? = "Album",
    displayName: String = "$id.mp3",
    track: Int? = 1,
) = ScannedSong(
    id = id,
    sourceType = SongSourceType.MEDIA_STORE,
    contentUri = "content://media/external/audio/media/${id.substringAfterLast(':')}",
    title = title,
    artist = artist,
    album = album,
    durationMs = 31_000L,
    trackNumber = track,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = 2L,
    displayName = displayName,
    relativePath = "Music/LibrePlayerQ34/",
    mimeType = "audio/mpeg",
    artworkUri = null,
)
