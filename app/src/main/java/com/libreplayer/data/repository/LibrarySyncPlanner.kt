package com.libreplayer.data.repository

import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.library.scanner.ScannedSong
import com.libreplayer.library.semantics.albumGroupingArtist
import com.libreplayer.library.semantics.albumGroupingKey
import com.libreplayer.library.semantics.artistGroupingKey
import com.libreplayer.library.semantics.normalizedGroupingKey
import com.libreplayer.library.semantics.resolvedAlbumTitle
import com.libreplayer.library.semantics.resolvedSongTitle
import com.libreplayer.library.semantics.resolvedTrackArtist

internal data class LibraryDatabaseChanges(
    val songs: List<SongEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
    val songUpserts: List<SongEntity>,
    val deletedSongIds: List<String>,
    val albumUpserts: List<AlbumEntity>,
    val deletedAlbumIds: List<String>,
    val artistUpserts: List<ArtistEntity>,
    val deletedArtistIds: List<String>,
) {
    val hasChanges: Boolean
        get() = songUpserts.isNotEmpty() || deletedSongIds.isNotEmpty() ||
            albumUpserts.isNotEmpty() || deletedAlbumIds.isNotEmpty() ||
            artistUpserts.isNotEmpty() || deletedArtistIds.isNotEmpty()
}

internal fun prepareLibraryChanges(
    scannedSongs: List<ScannedSong>,
    currentSongs: List<SongEntity>,
    currentAlbums: List<AlbumEntity>,
    currentArtists: List<ArtistEntity>,
): LibraryDatabaseChanges {
    val currentSongsById = currentSongs.associateBy(SongEntity::id)
    val songs = scannedSongs.map { scanned ->
        scanned.asEntity(isFavorite = currentSongsById[scanned.id]?.isFavorite == true)
    }
    val albums = buildAlbums(songs)
    val artists = buildArtists(songs)

    val currentAlbumsById = currentAlbums.associateBy(AlbumEntity::id)
    val currentArtistsById = currentArtists.associateBy(ArtistEntity::id)
    val songIds = songs.mapTo(HashSet(), SongEntity::id)
    val albumIds = albums.mapTo(HashSet(), AlbumEntity::id)
    val artistIds = artists.mapTo(HashSet(), ArtistEntity::id)
    return LibraryDatabaseChanges(
        songs = songs,
        albums = albums,
        artists = artists,
        songUpserts = songs.filter { currentSongsById[it.id] != it },
        deletedSongIds = currentSongs.mapNotNull { it.id.takeIf { id -> id !in songIds } },
        albumUpserts = albums.filter { currentAlbumsById[it.id] != it },
        deletedAlbumIds = currentAlbums.mapNotNull { it.id.takeIf { id -> id !in albumIds } },
        artistUpserts = artists.filter { currentArtistsById[it.id] != it },
        deletedArtistIds = currentArtists.mapNotNull { it.id.takeIf { id -> id !in artistIds } },
    )
}

internal fun buildAlbums(songs: List<SongEntity>): List<AlbumEntity> =
    songs.groupBy { song ->
        albumGroupingKey(
            album = song.album,
            albumArtist = null,
            trackArtist = song.artist,
        )
    }
        .map { (key, groupedSongs) ->
            val first = groupedSongs.first()
            AlbumEntity(
                id = key,
                title = resolvedAlbumTitle(first.album),
                artist = albumGroupingArtist(albumArtist = null, trackArtist = first.artist),
                songCount = groupedSongs.size,
                totalDurationMs = groupedSongs.sumOf { it.durationMs },
                artworkUri = groupedSongs.firstNotNullOfOrNull { it.artworkUri },
                sortKey = first.albumSortKey,
            )
        }
        .sortedBy { it.sortKey }

internal fun buildArtists(songs: List<SongEntity>): List<ArtistEntity> =
    songs.groupBy { artistGroupingKey(it.artist) }
        .map { (key, groupedSongs) ->
            val first = groupedSongs.first()
            ArtistEntity(
                id = key,
                name = resolvedTrackArtist(first.artist),
                songCount = groupedSongs.size,
                totalDurationMs = groupedSongs.sumOf { it.durationMs },
                artworkUri = groupedSongs.firstNotNullOfOrNull { it.artworkUri },
                sortKey = resolvedTrackArtist(first.artist).normalizedGroupingKey(),
            )
        }
        .sortedBy { it.sortKey }

internal fun ScannedSong.asEntity(isFavorite: Boolean): SongEntity {
    val normalizedTitle = resolvedSongTitle(title, displayName)
    val normalizedArtist = resolvedTrackArtist(artist)
    val normalizedAlbum = resolvedAlbumTitle(album)
    return SongEntity(
        id = id,
        sourceType = sourceType.name,
        contentUri = contentUri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        dateAddedEpochSeconds = dateAddedEpochSeconds,
        dateModifiedEpochSeconds = dateModifiedEpochSeconds,
        displayName = displayName,
        relativePath = relativePath,
        mimeType = mimeType,
        artworkUri = artworkUri,
        isFavorite = isFavorite,
        titleSortKey = normalizedTitle.normalizedSortKey(),
        artistSortKey = normalizedArtist.normalizedSortKey(),
        albumSortKey = normalizedAlbum.normalizedSortKey(),
    )
}

internal fun SongEntity.asScannedSong(): ScannedSong =
    ScannedSong(
        id = id,
        sourceType = runCatching { SongSourceType.valueOf(sourceType) }
            .getOrDefault(SongSourceType.MEDIA_STORE),
        contentUri = contentUri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        dateAddedEpochSeconds = dateAddedEpochSeconds,
        dateModifiedEpochSeconds = dateModifiedEpochSeconds,
        displayName = displayName,
        relativePath = relativePath,
        mimeType = mimeType,
        artworkUri = artworkUri,
    )

// Retain the accepted Baseline Profile symbol while sharing Q3.1 normalization authority.
private fun String.normalizedSortKey(): String = normalizedGroupingKey()
