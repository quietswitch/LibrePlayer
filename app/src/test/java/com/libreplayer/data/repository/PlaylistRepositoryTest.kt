package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.dao.PlaylistQueries
import com.libreplayer.data.database.dao.PlaylistSongWithSong
import com.libreplayer.data.database.dao.PlaylistWithCount
import com.libreplayer.data.database.dao.SongLookupQueries
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaylistRepositoryTest {
    @Test
    fun `addSongs appends songs without duplicates`() = runTest {
        val songs = listOf(songEntity("1", "One"), songEntity("2", "Two"))
        val queries = FakePlaylistQueries(songs)
        val songLookup = FakeSongLookupQueries(songs)
        val repository = DefaultPlaylistRepository(queries, songLookup) { it() }
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
        val repository = DefaultPlaylistRepository(queries, songLookup) { it() }
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
        val repository = DefaultPlaylistRepository(queries, songLookup) { it() }
        val playlistId = repository.createPlaylist("Favorites")
        repository.addSongs(playlistId, listOf("1", "2"))

        repository.removeSong(playlistId, "1")

        val playlistSongs = repository.getPlaylistSongs(playlistId)
        assertThat(playlistSongs.map { it.id }).containsExactly("2")
    }

    @Test
    fun `bulk add preserves requested order despite unordered lookup`() = runTest {
        val songs = listOf(songEntity("1", "One"), songEntity("2", "Two"), songEntity("3", "Three"))
        val queries = FakePlaylistQueries(songs)
        val repository = DefaultPlaylistRepository(queries, FakeSongLookupQueries(songs)) { it() }
        val id = repository.createPlaylist("Order")
        repository.addSongs(id, listOf("3", "1", "3", "2"))
        assertThat(repository.getPlaylistSongs(id).map { it.id }).containsExactly("3", "1", "2").inOrder()
        assertThat(queries.getPlaylistEntriesNow(id).map { it.position }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `rename and duplicate names retain stable playlist identities`() = runTest {
        val songs = listOf(songEntity("1", "One"))
        val queries = FakePlaylistQueries(songs)
        val repository = DefaultPlaylistRepository(queries, FakeSongLookupQueries(songs)) { it() }
        val first = repository.createPlaylist("  同名  ")
        val second = repository.createPlaylist("同名")
        repository.addSongs(first, listOf("1"))
        repository.renamePlaylist(first, " New name ")
        repository.renamePlaylist(first, "  ")
        assertThat(first).isNotEqualTo(second)
        assertThat(queries.getPlaylistById(first)?.name).isEqualTo("New name")
        assertThat(repository.getPlaylistSongIds(first)).containsExactly("1")
        repository.deletePlaylist(second)
        assertThat(repository.getPlaylistSongIds(first)).containsExactly("1")
    }

    @Test
    fun `mutations retain unavailable references and reorder only visible slots`() = runTest {
        val songs = listOf(songEntity("1", "One"), songEntity("2", "Two"), songEntity("3", "Three"))
        val queries = FakePlaylistQueries(songs)
        val repository = DefaultPlaylistRepository(queries, FakeSongLookupQueries(songs)) { it() }
        val id = repository.createPlaylist("Hidden")
        queries.replaceSongs(id, listOf(
            PlaylistSongEntity(id, "1", 0, 1), PlaylistSongEntity(id, "missing", 1, 2),
            PlaylistSongEntity(id, "2", 2, 3),
        ))
        repository.addSongs(id, listOf("3"))
        repository.moveSong(id, 2, 0)
        assertThat(repository.getPlaylistSongIds(id)).containsExactly("3", "missing", "1", "2").inOrder()
        repository.removeSong(id, "1")
        assertThat(repository.getPlaylistSongIds(id)).containsExactly("3", "missing", "2").inOrder()
        assertThat(queries.getPlaylistEntriesNow(id).map { it.position }).containsExactly(0, 1, 2).inOrder()
        assertThat(queries.getPlaylistEntriesNow(id)[1].addedAtEpochMillis).isEqualTo(2L)
    }

    @Test
    fun `import rejects changed library before creating playlist and mutations enter transaction`() = runTest {
        val songs = listOf(songEntity("1", "One"))
        val queries = FakePlaylistQueries(songs)
        var transactions = 0
        val repository = DefaultPlaylistRepository(queries, FakeSongLookupQueries(songs)) { block -> transactions++; block() }
        val failed = runCatching { repository.importPlaylist("Invalid", listOf("missing")) }
        assertThat(failed.isFailure).isTrue()
        val id = repository.importPlaylist("Valid", listOf("1", "1"))
        assertThat(id).isEqualTo(1L)
        repository.renamePlaylist(id, "Rename")
        repository.addSongs(id, listOf("1"))
        repository.moveSong(id, 0, 0)
        repository.removeSong(id, "1")
        assertThat(transactions).isEqualTo(6)
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
        ids.mapNotNull(songMap::get).reversed()
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

    override fun observePlaylists(): Flow<List<PlaylistWithCount>> = playlists.map { rows ->
        rows.map { PlaylistWithCount(it, getPlaylistSongsNow(it.id).size) }
    }

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
        val items = getPlaylistEntriesNow(playlistId)
        return items.mapNotNull { entity ->
            songLookup[entity.songId]?.let { song ->
                PlaylistSongWithSong(crossRef = entity, song = song)
            }
        }
    }

    override suspend fun getPlaylistEntriesNow(playlistId: Long): List<PlaylistSongEntity> =
        playlistSongs[playlistId].orEmpty().sortedWith(compareBy({ it.position }, { it.songId }))
}
