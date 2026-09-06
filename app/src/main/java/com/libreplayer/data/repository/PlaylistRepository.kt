package com.libreplayer.data.repository

import com.libreplayer.data.database.dao.PlaylistQueries
import com.libreplayer.data.database.dao.PlaylistSongWithSong
import com.libreplayer.data.database.dao.SongLookupQueries
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<UserPlaylist>>
    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>>
    suspend fun getPlaylistSongs(playlistId: Long): List<Song>
    suspend fun getPlaylistSongIds(playlistId: Long): List<String>
    suspend fun createPlaylist(name: String): Long
    suspend fun importPlaylist(name: String, songIds: List<String>): Long
    suspend fun renamePlaylist(playlistId: Long, name: String)
    suspend fun deletePlaylist(playlistId: Long)
    suspend fun addSongs(playlistId: Long, songIds: List<String>)
    suspend fun removeSong(playlistId: Long, songId: String)
    suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int)
}

class DefaultPlaylistRepository(
    private val playlistQueries: PlaylistQueries,
    private val songLookupQueries: SongLookupQueries,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : PlaylistRepository {
    override fun observePlaylists(): Flow<List<UserPlaylist>> =
        playlistQueries.observePlaylists().map { playlists ->
            playlists.map { row ->
                UserPlaylist(
                    id = row.playlist.id,
                    name = row.playlist.name,
                    songCount = row.songCount,
                    updatedAtEpochMillis = row.playlist.updatedAtEpochMillis,
                )
            }
        }

    override fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        playlistQueries.observePlaylistSongs(playlistId).map { rows ->
            rows.map(PlaylistSongWithSong::asModel)
        }

    override suspend fun getPlaylistSongs(playlistId: Long): List<Song> =
        playlistQueries.getPlaylistSongsNow(playlistId).map { it.song.asModel() }

    override suspend fun getPlaylistSongIds(playlistId: Long): List<String> =
        playlistQueries.getPlaylistEntriesNow(playlistId).map { it.songId }

    override suspend fun importPlaylist(name: String, songIds: List<String>): Long {
        var playlistId = 0L
        transaction {
            val ids = songIds.distinct()
            val available = ids.chunked(900).flatMap { songLookupQueries.getSongsByIds(it) }.map { it.id }.toSet()
            check(ids.all { it in available }) { "Library changed; review the playlist again before importing." }
            playlistId = createPlaylist(name)
            val now = System.currentTimeMillis()
            playlistQueries.replaceSongs(playlistId, ids.mapIndexed { position, id ->
                PlaylistSongEntity(playlistId, id, position, now)
            })
        }
        return playlistId
    }

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

    override suspend fun renamePlaylist(playlistId: Long, name: String) = transaction {
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return@transaction
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

    override suspend fun addSongs(playlistId: Long, songIds: List<String>) = transaction {
        if (songIds.isEmpty()) return@transaction
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return@transaction
        val existing = playlistQueries.getPlaylistEntriesNow(playlistId)
        val existingIds = existing.map { it.songId }.toMutableSet()
        val available = songIds.distinct().chunked(900)
            .flatMap { songLookupQueries.getSongsByIds(it) }.map { it.id }.toSet()
        val songsToAdd = songIds.filter { it in available && existingIds.add(it) }
        val now = System.currentTimeMillis()
        val updated = existing.reindex().toMutableList()
        var nextPosition = updated.size
        songsToAdd.forEach { songId ->
            updated += PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                position = nextPosition++,
                addedAtEpochMillis = now,
            )
        }
        playlistQueries.replaceSongs(playlistId, updated)
        playlistQueries.updatePlaylist(playlist.copy(updatedAtEpochMillis = now))
    }

    override suspend fun removeSong(playlistId: Long, songId: String) = transaction {
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return@transaction
        val updated = playlistQueries.getPlaylistEntriesNow(playlistId)
            .filterNot { it.songId == songId }
            .reindex()
        playlistQueries.replaceSongs(playlistId, updated)
        playlistQueries.updatePlaylist(playlist.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    }

    override suspend fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int) = transaction {
        val playlist = playlistQueries.getPlaylistById(playlistId) ?: return@transaction
        val stored = playlistQueries.getPlaylistEntriesNow(playlistId)
        val visibleIds = playlistQueries.getPlaylistSongsNow(playlistId).map { it.crossRef.songId }.toSet()
        val visible = stored.filter { it.songId in visibleIds }.toMutableList()
        if (fromIndex !in visible.indices || toIndex !in visible.indices) return@transaction
        visible.add(toIndex, visible.removeAt(fromIndex))
        // Hidden references retain their slots; only the displayed subsequence moves.
        val reordered = visible.iterator()
        val updated = stored.map { if (it.songId in visibleIds) reordered.next() else it }
        playlistQueries.replaceSongs(playlistId, updated.reindex())
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
