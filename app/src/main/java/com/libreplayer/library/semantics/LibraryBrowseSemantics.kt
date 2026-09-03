package com.libreplayer.library.semantics

import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.Song

/** Q3.2 authority for mapping one source occurrence into its album browse group. */
internal fun Song.albumBrowseGroupId(): String =
    albumGroupingKey(
        album = album,
        albumArtist = null,
        trackArtist = artist,
    )

/** Q3.2 authority for mapping one source occurrence into its track-artist browse group. */
internal fun Song.artistBrowseGroupId(): String = artistGroupingKey(artist)

internal fun albumBrowseSongs(songs: List<Song>, albumId: String): List<Song> =
    songs.filter { song -> song.albumBrowseGroupId() == albumId }

internal fun artistBrowseSongs(songs: List<Song>, artistId: String): List<Song> =
    songs.filter { song -> song.artistBrowseGroupId() == artistId }

internal fun artistBrowseAlbums(
    albums: List<Album>,
    artistSongs: List<Song>,
): List<Album> {
    val albumIds = artistSongs.mapTo(LinkedHashSet(), Song::albumBrowseGroupId)
    return albums.filter { album -> album.id in albumIds }
}

/** Builds one coherent browse projection from a single observable song snapshot. */
internal fun buildBrowseAlbums(songs: List<Song>): List<Album> =
    songs.groupBy(Song::albumBrowseGroupId)
        .map { (id, members) ->
            val first = members.first()
            Album(
                id = id,
                title = resolvedAlbumTitle(first.album),
                artist = albumGroupingArtist(albumArtist = null, trackArtist = first.artist),
                songCount = members.size,
                totalDurationMs = members.sumOf(Song::durationMs),
                artworkUri = members.firstNotNullOfOrNull(Song::artworkUri),
            )
        }
        .sortedBy { album -> album.title.normalizedGroupingKey() }

internal fun buildBrowseArtists(songs: List<Song>): List<Artist> =
    songs.groupBy(Song::artistBrowseGroupId)
        .map { (id, members) ->
            val first = members.first()
            Artist(
                id = id,
                name = resolvedTrackArtist(first.artist),
                songCount = members.size,
                totalDurationMs = members.sumOf(Song::durationMs),
                artworkUri = members.firstNotNullOfOrNull(Song::artworkUri),
            )
        }
        .sortedBy { artist -> artist.name.normalizedGroupingKey() }
