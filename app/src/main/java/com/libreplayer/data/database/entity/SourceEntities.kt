package com.libreplayer.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "library_sources")
data class LibrarySourceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val authority: String,
    val locator: String,
    val incarnation: Long = 0,
    val version: String? = null,
    val generation: Long? = null,
    val reconciledAtEpochMillis: Long = 0,
)

// Inactive memberships retain exact identity aliases for Q3.6 reconnects. There is deliberately
// no Song FK: deleting a library row must not destroy its identity or any hidden user reference.
@Entity(
    tableName = "song_sources",
    primaryKeys = ["sourceId", "incarnation", "itemKey"],
    foreignKeys = [ForeignKey(
        entity = LibrarySourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("songId"), Index("physicalKey")],
)
data class SongSourceEntity(
    val sourceId: String,
    val incarnation: Long,
    val itemKey: String,
    val songId: String,
    val contentUri: String,
    val physicalKey: String?,
    val present: Boolean = true,
)

@Entity(tableName = "legacy_song_protection")
data class LegacySongProtectionEntity(
    @PrimaryKey val songId: String,
    val reason: String,
)
