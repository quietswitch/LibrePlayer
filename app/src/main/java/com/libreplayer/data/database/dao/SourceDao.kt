package com.libreplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.libreplayer.data.database.entity.LibrarySourceEntity
import com.libreplayer.data.database.entity.SongSourceEntity
import com.libreplayer.data.database.entity.LegacySongProtectionEntity

@Dao
interface SourceDao {
    @Query("SELECT * FROM library_sources")
    suspend fun getSources(): List<LibrarySourceEntity>

    @Query("SELECT * FROM song_sources")
    suspend fun getMemberships(): List<SongSourceEntity>

    @Query("SELECT * FROM legacy_song_protection")
    suspend fun getProtections(): List<LegacySongProtectionEntity>

    @Upsert
    suspend fun upsertSources(sources: List<LibrarySourceEntity>)

    @Upsert
    suspend fun upsertMemberships(memberships: List<SongSourceEntity>)

    @Upsert
    suspend fun upsertProtections(protections: List<LegacySongProtectionEntity>)

    @Query("DELETE FROM legacy_song_protection WHERE songId IN (:ids)")
    suspend fun deleteProtections(ids: List<String>)
}
