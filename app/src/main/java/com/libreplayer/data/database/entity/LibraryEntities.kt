package com.libreplayer.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index("artistSortKey"),
        Index("albumSortKey"),
        Index("titleSortKey"),
        Index("dateAddedEpochSeconds"),
    ],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val contentUri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val dateAddedEpochSeconds: Long,
    val dateModifiedEpochSeconds: Long,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val artworkUri: String?,
    val isFavorite: Boolean,
    val titleSortKey: String,
    val artistSortKey: String,
    val albumSortKey: String,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val songCount: Int,
    val totalDurationMs: Long,
    val artworkUri: String?,
    val sortKey: String,
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val songCount: Int,
    val totalDurationMs: Long,
    val artworkUri: String?,
    val sortKey: String,
)

@Entity(tableName = "imported_roots")
data class ImportedRootEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val addedAtEpochMillis: Long,
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val songId: String,
    val playedAtEpochMillis: Long,
)

