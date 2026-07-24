package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.dao.PlaylistQueries
import com.libreplayer.data.database.dao.PlaylistSongWithSong
import com.libreplayer.data.database.dao.SongLookupQueries
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaylistRepositoryTest {
    @Test
    fun `addSongs appends songs without duplicates`() = runTest {
        val songs = listOf(songEntity("1", "One"), songEntity("2", "Two"))
        val queries = FakePlaylistQueries(songs)
        val songLookup = FakeSongLookupQueries(songs)
        val repository = DefaultPlaylistRepository(queries, songLookup)
        val playlistId = repository.createPlaylist("Road Trip")

        repository.addSongs(playlistId, listOf("1", "2", "1"))

        val playlistSongs = repository.getPlaylistSongs(playlistId)
        assertThat(playlistSongs.map { it.id }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `moveSong reorders playlist entries`() = runTest {
        val songs = listOf(songEntity("1", "One"), songEntity("2", "Two"), songEntity("3", "Three"))
        val queries = FakePlaylistQueries(songs)
        val songLookup = FakeSongLookupQueries(songs)
        val repository = DefaultPlaylistRepository(queries, songLookup)
        val playlistId = repository.createPlaylist("Focus")
        repository.addSongs(playlistId, listOf("1", "2", "3"))

        repository.moveSong(playlistId, fromIndex = 2, toIndex = 0)

        val playlistSongs = repository.getPlaylistSongs(playlistId)
        assertThat(playlistSongs.map { it.id }).containsExactly("3", "1", "2").inOrder()
    }

    @Test
    fun `removeSong drops the target track`() = runTest {
        val songs = listOf(songEntity("1", "One"), songEntity("2", "Two"))
        val queries = FakePlaylistQueries(songs)
        val songLookup = FakeSongLookupQueries(songs)
        val repository = DefaultPlaylistRepository(queries, songLookup)
        val playlistId = repository.createPlaylist("Favorites")
        repository.addSongs(playlistId, listOf("1", "2"))

        repository.removeSong(playlistId, "1")

        val playlistSongs = repository.getPlaylistSongs(playlistId)
        assertThat(playlistSongs.map { it.id }).containsExactly("2")
    }

    private fun songEntity(id: String, title: String) = SongEntity(
        id = id,
        sourceType = SongSourceType.MEDIA_STORE.name,
        contentUri = "content://song/$id",
        title = title,
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 180_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2024,
        dateAddedEpochSeconds = 1L,
        dateModifiedEpochSeconds = 1L,
        displayName = "$title.mp3",
        relativePath = "Music/",
        mimeType = "audio/mpeg",
        artworkUri = null,
        isFavorite = false,
        titleSortKey = title.lowercase(),
        artistSortKey = "artist$id",
        albumSortKey = "album$id",
    )
}

private class FakeSongLookupQueries(
    songs: List<SongEntity>,
) : SongLookupQueries {
    private val songMap = songs.associateBy { it.id }

    override suspend fun getSongsByIds(ids: List<String>): List<SongEntity> =
        ids.mapNotNull(songMap::get)
}

private class FakePlaylistQueries : PlaylistQueries {
    private var nextId = 1L
    private val playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    private val playlistSongs = mutableMapOf<Long, MutableList<PlaylistSongEntity>>()
    private val songsByPlaylist = mutableMapOf<Long, MutableStateFlow<List<PlaylistSongWithSong>>>()
    private val songLookup = mutableMapOf<String, SongEntity>()

    constructor(songs: List<SongEntity>) {
        songs.forEach { songLookup[it.id] = it }
    }

    override fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists

    override fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSongWithSong>> =
        songsByPlaylist.getOrPut(playlistId) { MutableStateFlow(emptyList()) }

    override suspend fun getPlaylistById(playlistId: Long): PlaylistEntity? =
        playlists.value.firstOrNull { it.id == playlistId }

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
        val assignedId = nextId++
        playlists.value = playlists.value + playlist.copy(id = assignedId)
        return assignedId
    }

    override suspend fun updatePlaylist(playlist: PlaylistEntity) {
        playlists.value = playlists.value.map { if (it.id == playlist.id) playlist else it }
    }

    override suspend fun deletePlaylistById(playlistId: Long) {
        playlists.value = playlists.value.filterNot { it.id == playlistId }
        playlistSongs.remove(playlistId)
        songsByPlaylist.remove(playlistId)
    }

    override suspend fun replaceSongs(playlistId: Long, songs: List<PlaylistSongEntity>) {
        playlistSongs[playlistId] = songs.toMutableList()
        songsByPlaylist.getOrPut(playlistId) { MutableStateFlow(emptyList()) }.value =
            getPlaylistSongsNow(playlistId)
    }

    override suspend fun getPlaylistSongsNow(playlistId: Long): List<PlaylistSongWithSong> {
        val items = playlistSongs[playlistId].orEmpty()
        return items.mapNotNull { entity ->
            songLookup[entity.songId]?.let { song ->
                PlaylistSongWithSong(crossRef = entity, song = song)
            }
        }
    }
}
