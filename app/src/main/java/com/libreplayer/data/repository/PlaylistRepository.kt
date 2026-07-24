package com.libreplayer.data.repository

import com.libreplayer.data.database.dao.PlaylistQueries
import com.libreplayer.data.database.dao.PlaylistSongWithSong
import com.libreplayer.data.database.dao.SongLookupQueries
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<UserPlaylist>>
    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>>
    suspend fun getPlaylistSongs(playlistId: Long): List<Song>
    suspend fun createPlaylist(name: String): Long
    suspend fun renamePlaylist(playlistId: Long, name: String)
    suspend fun deletePlaylist(playlistId: Long)
    suspend fun addSongs(playlistId: Long, songIds: List<String>)
    suspend fun removeSong(playlistId: Long, songId: String)
    suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int)
}

class DefaultPlaylistRepository(
    private val playlistQueries: PlaylistQueries,
    private val songLookupQueries: SongLookupQueries,
) : PlaylistRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePlaylists(): Flow<List<UserPlaylist>> =
        playlistQueries.observePlaylists().mapLatest { playlists ->
            playlists.map { playlist ->
                UserPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    songCount = playlistQueries.getPlaylistSongsNow(playlist.id).size,
                    updatedAtEpochMillis = playlist.updatedAtEpochMillis,
                )
            }
        }

    override fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        playlistQueries.observePlaylistSongs(playlistId).map { rows ->
            rows.map(PlaylistSongWithSong::asModel)
        }

    override suspend fun getPlaylistSongs(playlistId: Long): List<Song> =
        playlistQueries.getPlaylistSongsNow(playlistId).map { it.song.asModel() }

    override suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistQueries.insertPlaylist(
            PlaylistEntity(
                name = name.trim().ifBlank { "New playlist" },
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String) {
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return
        playlistQueries.updatePlaylist(
            playlist.copy(
                name = name.trim().ifBlank { playlist.name },
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistQueries.deletePlaylistById(playlistId)
    }

    override suspend fun addSongs(playlistId: Long, songIds: List<String>) {
        if (songIds.isEmpty()) return
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return
        val existing = playlistQueries.getPlaylistSongsNow(playlistId).toMutableList()
        val existingIds = existing.map { it.song.id }.toMutableSet()
        val songsToAdd = songLookupQueries.getSongsByIds(songIds)
            .filter { song -> existingIds.add(song.id) }
        val now = System.currentTimeMillis()
        val updated = existing.map { it.crossRef }.toMutableList()
        var nextPosition = updated.size
        songsToAdd.forEach { song ->
            updated += PlaylistSongEntity(
                playlistId = playlistId,
                songId = song.id,
                position = nextPosition++,
                addedAtEpochMillis = now,
            )
        }
        playlistQueries.replaceSongs(playlistId, updated)
        playlistQueries.updatePlaylist(playlist.copy(updatedAtEpochMillis = now))
    }

    override suspend fun removeSong(playlistId: Long, songId: String) {
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return
        val updated = playlistQueries.getPlaylistSongsNow(playlistId)
            .map { it.crossRef }
            .filterNot { it.songId == songId }
            .reindex()
        playlistQueries.replaceSongs(playlistId, updated)
        playlistQueries.updatePlaylist(playlist.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    }

    override suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return
        val items = playlistQueries.getPlaylistSongsNow(playlistId).map { it.crossRef }.toMutableList()
        if (fromIndex !in items.indices || toIndex !in items.indices) return
        val moved = items.removeAt(fromIndex)
        items.add(toIndex, moved)
        playlistQueries.replaceSongs(playlistId, items.reindex())
        playlistQueries.updatePlaylist(playlist.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    }
}

private fun PlaylistSongWithSong.asModel(): PlaylistSong =
    PlaylistSong(
        playlistId = crossRef.playlistId,
        songId = crossRef.songId,
        position = crossRef.position,
        song = song.asModel(),
    )

private fun List<PlaylistSongEntity>.reindex(): List<PlaylistSongEntity> =
    mapIndexed { index, item -> item.copy(position = index) }
