package com.libreplayer.data.repository

import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.library.scanner.ScannedSong
import java.util.Locale

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
    songs.groupBy { "${it.albumSortKey}|${it.artistSortKey}" }
        .map { (key, groupedSongs) ->
            val first = groupedSongs.first()
            AlbumEntity(
                id = key,
                title = first.album?.takeIf(String::isNotBlank) ?: "Unknown album",
                artist = first.artist?.takeIf(String::isNotBlank),
                songCount = groupedSongs.size,
                totalDurationMs = groupedSongs.sumOf { it.durationMs },
                artworkUri = groupedSongs.firstNotNullOfOrNull { it.artworkUri },
                sortKey = first.albumSortKey,
            )
        }
        .sortedBy { it.sortKey }

internal fun buildArtists(songs: List<SongEntity>): List<ArtistEntity> =
    songs.groupBy { it.artistSortKey }
        .map { (key, groupedSongs) ->
            val first = groupedSongs.first()
            ArtistEntity(
                id = key,
                name = first.artist?.takeIf(String::isNotBlank) ?: "Unknown artist",
                songCount = groupedSongs.size,
                totalDurationMs = groupedSongs.sumOf { it.durationMs },
                artworkUri = groupedSongs.firstNotNullOfOrNull { it.artworkUri },
                sortKey = key,
            )
        }
        .sortedBy { it.sortKey }

internal fun ScannedSong.asEntity(isFavorite: Boolean): SongEntity {
    val normalizedTitle = title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.')
    val normalizedArtist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
    val normalizedAlbum = album?.takeIf { it.isNotBlank() } ?: "Unknown album"
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

private fun String.normalizedSortKey(): String = trim().lowercase(Locale.US)
