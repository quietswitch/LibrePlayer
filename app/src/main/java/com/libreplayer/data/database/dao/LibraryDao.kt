package com.libreplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.libreplayer.data.database.entity.AlbumEntity
import com.libreplayer.data.database.entity.ArtistEntity
import com.libreplayer.data.database.entity.ImportedRootEntity
import com.libreplayer.data.database.entity.RecentlyPlayedEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.data.database.dao.SongLookupQueries
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao : SongLookupQueries {
    @Query("SELECT * FROM songs")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun countSongs(): Int

    @Query("SELECT * FROM songs WHERE isFavorite = 1")
    fun observeFavoriteSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    override suspend fun getSongsByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE sourceType = :sourceType")
    suspend fun getSongsBySourceType(sourceType: String): List<SongEntity>

    @Query("SELECT id FROM songs WHERE isFavorite = 1")
    suspend fun getFavoriteIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    @Query("DELETE FROM songs WHERE id NOT IN (:ids)")
    suspend fun deleteSongsMissingFrom(ids: List<String>)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun updateFavorite(songId: String, isFavorite: Boolean)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY sortKey ASC")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY sortKey ASC")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtists(artists: List<ArtistEntity>)

    @Query("DELETE FROM artists")
    suspend fun clearArtists()
}

@Dao
interface ImportedRootDao {
    @Query("SELECT * FROM imported_roots ORDER BY displayName ASC")
    fun observeRoots(): Flow<List<ImportedRootEntity>>

    @Query("SELECT * FROM imported_roots ORDER BY displayName ASC")
    suspend fun getRoots(): List<ImportedRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoot(root: ImportedRootEntity)

    @Query("DELETE FROM imported_roots WHERE uri = :uri")
    suspend fun deleteRoot(uri: String)
}

@Dao
interface RecentlyPlayedDao {
    @Query("SELECT * FROM recently_played ORDER BY playedAtEpochMillis DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<RecentlyPlayedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentlyPlayedEntity)
}
