package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.room.withTransaction
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.dao.PlaylistQueries
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.repository.DefaultPlaylistRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.library.playlist.M3uResolution
import com.libreplayer.library.playlist.M3uSemantics
import com.libreplayer.library.playlist.PlaylistSource
import com.libreplayer.library.playlist.externalDocumentPath
import com.libreplayer.media.service.PlaybackService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Synthetic-only, benchmark-only authority. No package clear, schema change or user-playlist targeting. */
class PlaylistAuthorityProbeProvider : ContentProvider() {
    private val app get() = requireNotNull(context).applicationContext as LibrePlayerApplication
    private val container get() = app.appContainer
    private val playlists get() = container.playlistRepository
    private val journal get() = app.getSharedPreferences("q36-authority", 0)
    private val directory get() = File(app.cacheDir, "q36-playlist-authority")

    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = runBlocking(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT == 36 && Build.MODEL.startsWith("sdk_gphone"))
        when (method) {
            "setup" -> setup()
            "inspect" -> inspect()
            "playback" -> playback(arg?.toInt() ?: 2)
            "ui-moved" -> uiEdit(remove = false)
            "ui-removed" -> uiEdit(remove = true)
            "removed" -> removed()
            "cleanup" -> cleanup()
            "empty" -> {
                container.libraryRepository.rescanLibrary()
                check(fixtureSongs().isEmpty())
                Bundle().apply { putString("status", "PASS: no Q36 library Songs remain") }
            }
            else -> error("Unknown Q3.6 method")
        }
    }

    private suspend fun fixtureSongs(): List<Song> = container.libraryRepository.getAllSongs()
        .filter { it.relativePath?.startsWith(RELATIVE_ROOT) == true }

    private fun record(id: Long) {
        val ids = journal.getStringSet("ids", emptySet()).orEmpty() + id.toString()
        check(journal.edit().putStringSet("ids", ids).commit())
    }

    private suspend fun setup(): Bundle {
        check(journal.all.isEmpty() && !directory.exists()) { "Q3.6 evidence already exists; inspect/clean before another explicit run." }
        check(playlists.observePlaylists().first().none { it.name.startsWith("Q36 ") })
        check(directory.mkdir())
        container.libraryRepository.rescanLibrary()
        val songs = fixtureSongs()
        check(songs.size == 4)
        roomAuthority(songs.first())
        val one = songs.single { it.displayName == "One.mp3" }
        val unicode = songs.single { it.displayName.startsWith("音楽") }
        val left = songs.single { it.relativePath?.endsWith("left/") == true }
        val right = songs.single { it.relativePath?.endsWith("right/") == true }
        val originalEntities = container.database.songDao().getSongsByIds(songs.map { it.id }).sortedBy { it.id }
        val main = playlists.createPlaylist("Q36 Same name").also(::record)
        val other = playlists.createPlaylist("Q36 Same name").also(::record)
        check(main != other)
        playlists.addSongs(main, listOf(unicode.id, one.id, left.id, unicode.id, right.id))
        check(playlists.getPlaylistSongIds(main) == listOf(unicode.id, one.id, left.id, right.id))
        playlists.moveSong(main, 3, 1)
        playlists.removeSong(main, left.id)
        playlists.renamePlaylist(main, "Q36 Ordered")
        val expected = listOf(unicode.id, right.id, one.id)
        check(playlists.getPlaylistSongIds(main) == expected)
        check(container.database.playlistDao().getPlaylistEntriesNow(main).map { it.position } == listOf(0, 1, 2))
        journal.edit().putLong("main", main).putString("expected", expected.joinToString("|"))
            .putString("removed", one.id).putString("selectedTitle", one.resolvedTitle).commit()

        // Real synthetic local file context; no global filename lookup or source ingestion.
        val relative = File(directory, "relative.m3u")
        val sourceEvidence = songs.map { PlaylistSource(it, "/storage/emulated/0/${it.relativePath}${it.displayName}") }
        val unicodeRelative = File(ROOT, unicode.displayName).relativeTo(relative.parentFile!!).invariantSeparatorsPath
        val oneRelative = File(ROOT, "One.mp3").relativeTo(relative.parentFile!!).invariantSeparatorsPath
        relative.writeText("\uFEFF#EXTM3U\r\n#EXTINF:999,Do not rewrite\n$unicodeRelative\n$oneRelative\n$oneRelative", Charsets.UTF_8)
        val relativeReport = M3uSemantics.resolve(M3uSemantics.parse(relative.readBytes()), sourceEvidence, relative.parentFile!!.absolutePath)
        check(relativeReport.resolvedSongIds == listOf(unicode.id, one.id, one.id))
        val platformDocument = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:${RELATIVE_ROOT}relative.m3u")
        check(externalDocumentPath(platformDocument) == "$ROOT/relative.m3u")

        val input = File(directory, "input.m3u8")
        input.writeText("\uFEFF#EXTM3U\r\n#EXTINF:999,Fake metadata\r\n$ROOT/${unicode.displayName}\n" +
            "$ROOT/One.mp3\n${unicode.contentUri}\nSame.mp3\n$ROOT/missing.mp3\nhttps://invalid.example/no-fetch\n" +
            "unknown:entry\nrelative-without-context.mp3", Charsets.UTF_8)
        val preview = container.playlistInterchange.preview(documentUri(input.name))
        check(preview.report.resolvedSongIds == listOf(unicode.id, one.id, unicode.id))
        check(preview.report.duplicateCount == 1)
        listOf(M3uResolution.AMBIGUOUS, M3uResolution.MISSING, M3uResolution.UNSUPPORTED_REMOTE,
            M3uResolution.UNSUPPORTED_SCHEME, M3uResolution.RELATIVE_CONTEXT_UNAVAILABLE).forEach { status ->
            check(preview.report.entries.count { it.status == status } == 1)
        }
        val imported = container.playlistInterchange.commitImport(preview.copy(name = "Q36 Imported")).also(::record)
        check(playlists.getPlaylistSongIds(imported) == listOf(unicode.id, one.id))

        val export = container.playlistInterchange.export(main, documentUri("output.m3u8"))
        check(export.written == 3 && export.unavailable == 0)
        val bytes = File(directory, "output.m3u8").readBytes()
        container.playlistInterchange.export(main, documentUri("second.m3u8"))
        check(bytes.contentEquals(File(directory, "second.m3u8").readBytes()))
        val roundTrip = container.playlistInterchange.preview(documentUri("output.m3u8"))
        check(roundTrip.report.resolvedSongIds == expected)
        val roundTripId = container.playlistInterchange.commitImport(roundTrip.copy(name = "Q36 Round trip")).also(::record)
        check(playlists.getPlaylistSongIds(roundTripId) == expected)

        File(directory, "invalid.m3u").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        check(runCatching { container.playlistInterchange.preview(documentUri("invalid.m3u")) }.isFailure)
        container.libraryRepository.rescanLibrary()
        check(playlists.getPlaylistSongIds(main) == expected)
        check(container.database.songDao().getSongsByIds(songs.map { it.id }).sortedBy { it.id } == originalEntities)
        playlists.deletePlaylist(other)
        check(playlists.getPlaylistSongIds(main) == expected && fixtureSongs().size == 4)
        return inspect().apply {
            putString("room", "PASS: transaction rollback, raw references, uniqueness, concurrent appends, counts, rename identity")
            putString("interchange", preview.report.summary())
            putString("relative", "PASS: real local-file context + platform external-storage document path; generic provider limitation reported")
            putString("roundTrip", "PASS: deterministic UTF8 output; same identities/order")
        }
    }

    private suspend fun inspect(): Bundle {
        val id = journal.getLong("main", -1)
        val expected = journal.getString("expected", "").orEmpty().split('|')
        check(playlists.getPlaylistSongIds(id) == expected)
        check(container.database.playlistDao().getPlaylistById(id)?.name == "Q36 Ordered")
        return Bundle().apply {
            putLong("mainId", id)
            putStringArray("ids", expected.toTypedArray())
            putStringArray("uris", playlists.getPlaylistSongs(id).map { it.contentUri }.toTypedArray())
            putString("selectedTitle", journal.getString("selectedTitle", ""))
            putStringArray("titles", playlists.getPlaylistSongs(id).map { it.resolvedTitle }.toTypedArray())
            putString("status", "PASS")
        }
    }

    private suspend fun playback(index: Int): Bundle {
        val songs = playlists.getPlaylistSongs(journal.getLong("main", -1))
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            return withContext(Dispatchers.Main) {
                check(controller.currentMediaItemIndex == index && controller.isPlaying && controller.playerError == null)
                val ids = List(controller.mediaItemCount) { controller.getMediaItemAt(it).mediaId }
                check(ids == songs.map { it.id })
                check(container.playbackConnection.uiState.value.currentSong?.contentUri == songs[index].contentUri)
                Bundle().apply { putStringArray("queueIds", ids.toTypedArray()); putInt("index", index); putString("uri", songs[index].contentUri); putString("status", "PASS") }
            }
        } finally { withContext(Dispatchers.Main) { controller.pause(); controller.release() } }
    }

    private suspend fun uiEdit(remove: Boolean): Bundle {
        val before = journal.getString("expected", "").orEmpty().split('|')
        val expected = if (remove) before.dropLast(1) else listOf(before[0], before[2], before[1])
        check(playlists.getPlaylistSongIds(journal.getLong("main", -1)) == expected)
        journal.edit().putString("expected", expected.joinToString("|")).commit()
        return inspect()
    }

    private suspend fun removed(): Bundle {
        container.libraryRepository.rescanLibrary()
        val id = journal.getLong("main", -1)
        val expected = journal.getString("expected", "").orEmpty().split('|')
        val removed = journal.getString("removed", "")
        check(fixtureSongs().size == 3)
        check(playlists.getPlaylistSongIds(id) == expected)
        check(playlists.getPlaylistSongs(id).map { it.id } == expected.filterNot { it == removed })
        check(playlists.observePlaylists().first().single { it.id == id }.songCount == expected.size - 1)
        return Bundle().apply { putString("status", "PASS: missing Song hidden, raw reference/order retained, live count updated") }
    }

    private suspend fun roomAuthority(song: Song) {
        val memory = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val entity = container.database.songDao().getSongById(song.id)!!
            val entities = listOf(entity.copy(id = "room-a", isFavorite = true), entity.copy(id = "room-b"), entity.copy(id = "room-c"))
            memory.songDao().upsertSongs(entities)
            val dao = memory.playlistDao()
            val repo = DefaultPlaylistRepository(dao, memory.songDao()) { block -> memory.withTransaction { block() } }
            val id = repo.createPlaylist("Room")
            coroutineScope { listOf("room-a", "room-b").map { name -> async { repo.addSongs(id, listOf(name)) } }.awaitAll() }
            check(repo.getPlaylistSongIds(id).toSet() == setOf("room-a", "room-b"))
            repo.addSongs(id, listOf("room-c", "room-c"))
            check(repo.getPlaylistSongIds(id).size == 3)
            val before = dao.getPlaylistEntriesNow(id)
            memory.songDao().deleteSongsByIds(listOf("room-b"))
            check(repo.getPlaylistSongIds(id).size == 3 && repo.getPlaylistSongs(id).size == 2)
            repo.moveSong(id, 1, 0)
            check(repo.getPlaylistSongIds(id).contains("room-b"))
            memory.songDao().upsertSongs(listOf(entities[1]))
            check(repo.getPlaylistSongs(id).size == 3)
            val snapshot = dao.getPlaylistEntriesNow(id)
            val names = dao.observePlaylists().first()
            val failing = object : PlaylistQueries by dao {
                override suspend fun replaceSongs(playlistId: Long, songs: List<PlaylistSongEntity>) {
                    dao.replaceSongs(playlistId, songs)
                    error("Injected failure after row replacement")
                }
            }
            val broken = DefaultPlaylistRepository(failing, memory.songDao()) { block -> memory.withTransaction { block() } }
            check(runCatching { broken.removeSong(id, "room-a") }.isFailure)
            check(dao.getPlaylistEntriesNow(id) == snapshot)
            check(runCatching { broken.importPlaylist("Must roll back", listOf("room-a")) }.isFailure)
            check(dao.observePlaylists().first() == names)
            repo.renamePlaylist(id, "Renamed")
            check(dao.getPlaylistEntriesNow(id) == snapshot && dao.getPlaylistById(id)?.name == "Renamed")
            check(memory.songDao().getSongById("room-a")?.isFavorite == true)
            check(before.all { old -> snapshot.single { it.songId == old.songId }.addedAtEpochMillis == old.addedAtEpochMillis })
            repo.deletePlaylist(id)
            check(dao.getPlaylistEntriesNow(id).isEmpty() && memory.songDao().getAllSongs().size == 3)
        } finally { memory.close() }
    }

    private suspend fun cleanup(): Bundle {
        journal.getStringSet("ids", emptySet()).orEmpty().forEach { value ->
            val id = value.toLong()
            val entity = container.database.playlistDao().getPlaylistById(id)
            check(entity == null || entity.name.startsWith("Q36 "))
            playlists.deletePlaylist(id)
        }
        DOCUMENTS.forEach { name -> File(directory, name).let { check(!it.exists() || it.delete()) } }
        check(!directory.exists() || directory.delete())
        check(journal.edit().clear().commit())
        return Bundle().apply { putString("status", "PASS: exact Q36 playlists and private documents removed") }
    }

    private fun documentUri(name: String) = Uri.parse("content://com.libreplayer.playlist-authority/$name")
    private fun document(uri: Uri): File {
        check(uri.authority == "com.libreplayer.playlist-authority" && uri.lastPathSegment in DOCUMENTS)
        return File(directory, uri.lastPathSegment!!)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = document(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else null }.toTypedArray()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(document(uri), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "audio/x-mpegurl"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val RELATIVE_ROOT = "Music/LibrePlayerBenchmark/Q36_PLAYLIST_AUTHORITY/"
        private const val ROOT = "/storage/emulated/0/Music/LibrePlayerBenchmark/Q36_PLAYLIST_AUTHORITY"
        private val DOCUMENTS = setOf("relative.m3u", "input.m3u8", "output.m3u8", "second.m3u8", "invalid.m3u")
    }
}
