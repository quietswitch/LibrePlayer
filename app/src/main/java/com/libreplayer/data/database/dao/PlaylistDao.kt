package com.libreplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

interface PlaylistQueries {
    fun observePlaylists(): Flow<List<PlaylistEntity>>
    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSongWithSong>>
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long
    suspend fun updatePlaylist(playlist: PlaylistEntity)
    suspend fun deletePlaylistById(playlistId: Long)
    suspend fun replaceSongs(playlistId: Long, songs: List<PlaylistSongEntity>)
    suspend fun getPlaylistSongsNow(playlistId: Long): List<PlaylistSongWithSong>
}

interface SongLookupQueries {
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>
}

data class PlaylistSongWithSong(
    @Embedded val crossRef: PlaylistSongEntity,
    @Embedded(prefix = "song_") val song: SongEntity,
)

@Dao
interface PlaylistDao : PlaylistQueries {
    @Query("SELECT * FROM playlists ORDER BY updatedAtEpochMillis DESC, name ASC")
    override fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Transaction
    @Query(
        """
        SELECT playlist_songs.playlistId,
               playlist_songs.songId,
               playlist_songs.position,
               playlist_songs.addedAtEpochMillis,
               songs.id AS song_id,
               songs.sourceType AS song_sourceType,
               songs.contentUri AS song_contentUri,
               songs.title AS song_title,
               songs.artist AS song_artist,
               songs.album AS song_album,
               songs.durationMs AS song_durationMs,
               songs.trackNumber AS song_trackNumber,
               songs.discNumber AS song_discNumber,
               songs.year AS song_year,
               songs.dateAddedEpochSeconds AS song_dateAddedEpochSeconds,
               songs.dateModifiedEpochSeconds AS song_dateModifiedEpochSeconds,
               songs.displayName AS song_displayName,
               songs.relativePath AS song_relativePath,
               songs.mimeType AS song_mimeType,
               songs.artworkUri AS song_artworkUri,
               songs.isFavorite AS song_isFavorite,
               songs.titleSortKey AS song_titleSortKey,
               songs.artistSortKey AS song_artistSortKey,
               songs.albumSortKey AS song_albumSortKey
        FROM playlist_songs
        INNER JOIN songs ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC
        """,
    )
    override fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSongWithSong>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    override suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    override suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    override suspend fun deletePlaylistById(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(songs: List<PlaylistSongEntity>)

    @Transaction
    override suspend fun replaceSongs(playlistId: Long, songs: List<PlaylistSongEntity>) {
        clearPlaylistSongs(playlistId)
        if (songs.isNotEmpty()) {
            insertPlaylistSongs(songs)
        }
    }

    @Transaction
    @Query(
        """
        SELECT playlist_songs.playlistId,
               playlist_songs.songId,
               playlist_songs.position,
               playlist_songs.addedAtEpochMillis,
               songs.id AS song_id,
               songs.sourceType AS song_sourceType,
               songs.contentUri AS song_contentUri,
               songs.title AS song_title,
               songs.artist AS song_artist,
               songs.album AS song_album,
               songs.durationMs AS song_durationMs,
               songs.trackNumber AS song_trackNumber,
               songs.discNumber AS song_discNumber,
               songs.year AS song_year,
               songs.dateAddedEpochSeconds AS song_dateAddedEpochSeconds,
               songs.dateModifiedEpochSeconds AS song_dateModifiedEpochSeconds,
               songs.displayName AS song_displayName,
               songs.relativePath AS song_relativePath,
               songs.mimeType AS song_mimeType,
               songs.artworkUri AS song_artworkUri,
               songs.isFavorite AS song_isFavorite,
               songs.titleSortKey AS song_titleSortKey,
               songs.artistSortKey AS song_artistSortKey,
               songs.albumSortKey AS song_albumSortKey
        FROM playlist_songs
        INNER JOIN songs ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC
        """,
    )
    override suspend fun getPlaylistSongsNow(playlistId: Long): List<PlaylistSongWithSong>
}

