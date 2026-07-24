package com.libreplayer.data.repository

import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.ImportedRootEntity
import com.libreplayer.data.database.entity.SongEntity

internal fun SongEntity.asModel(): Song =
    Song(
        id = id,
        sourceType = runCatching { SongSourceType.valueOf(sourceType) }.getOrDefault(SongSourceType.MEDIA_STORE),
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
    )

internal fun AlbumEntity.asModel(): Album =
    Album(
        id = id,
        title = title,
        artist = artist,
        songCount = songCount,
        totalDurationMs = totalDurationMs,
        artworkUri = artworkUri,
    )

internal fun ArtistEntity.asModel(): Artist =
    Artist(
        id = id,
        name = name,
        songCount = songCount,
        totalDurationMs = totalDurationMs,
        artworkUri = artworkUri,
    )

internal fun ImportedRootEntity.asModel(): ImportedRoot =
    ImportedRoot(
        uri = uri,
        displayName = displayName,
        addedAtEpochMillis = addedAtEpochMillis,
    )
