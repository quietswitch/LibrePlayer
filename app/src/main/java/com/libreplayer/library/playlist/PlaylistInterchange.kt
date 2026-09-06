package com.libreplayer.library.playlist

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.PlaylistRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class PlaylistImportPreview(val name: String, val report: M3uImportReport)
internal data class PlaylistExportResult(val written: Int, val unavailable: Int, val uriReferences: Int)

/** One-time user-selected document IO. Library references are resolved, never opened or ingested. */
internal class PlaylistInterchange(
    private val resolver: ContentResolver,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
) {
    suspend fun preview(uri: Uri): PlaylistImportPreview = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a local playlist document." }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        require(name.endsWith(".m3u", true) || name.endsWith(".m3u8", true)) { "Choose an .m3u or .m3u8 file." }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= M3uSemantics.MAX_BYTES) { "Playlist exceeds the 1 MiB input limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Playlist document is unavailable.")
        val document = M3uSemantics.parse(bytes)
        val sources = sources(library.observeSongs().first())
        val directory = externalDocumentPath(uri)?.substringBeforeLast('/')
        PlaylistImportPreview(M3uSemantics.defaultName(name), M3uSemantics.resolve(document, sources, directory))
    }

    suspend fun commitImport(preview: PlaylistImportPreview): Long =
        playlists.importPlaylist(preview.name, preview.report.importSongIds)

    suspend fun export(playlistId: Long, uri: Uri): PlaylistExportResult = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a local export document." }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        require(name.endsWith(".m3u", true) || name.endsWith(".m3u8", true)) { "Export requires an .m3u or .m3u8 document." }
        val storedIds = playlists.getPlaylistSongIds(playlistId)
        val librarySources = sources(library.observeSongs().first())
        val destinationPath = externalDocumentPath(uri)?.let(PlaylistPaths::key)
        require(librarySources.none { source ->
            source.song.contentUri == uri.toString() ||
                (destinationPath != null && source.physicalPath?.let(PlaylistPaths::key) == destinationPath)
        }) { "Export cannot overwrite a library Song." }
        val byId = librarySources.associateBy { it.song.id }
        val selected = storedIds.mapNotNull(byId::get)
        val bytes = M3uSemantics.write(selected) // Validate completely before opening the destination.
        coroutineContext.ensureActive()
        resolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("Export document is unavailable.")
        PlaylistExportResult(selected.size, storedIds.size - selected.size, selected.count { it.physicalPath == null })
    }

    @Suppress("DEPRECATION") // DATA is read-only path evidence, never used to open or mutate media.
    private suspend fun sources(songs: List<Song>): List<PlaylistSource> {
        val paths = mutableMapOf<String, String>()
        val media = songs.filter { it.sourceType == SongSourceType.MEDIA_STORE }
            .filter { Uri.parse(it.contentUri).authority == MediaStore.AUTHORITY }
            .groupBy { Uri.parse(it.contentUri).buildUpon().path(Uri.parse(it.contentUri).path?.substringBeforeLast('/')).build() }
        media.forEach { (collection, members) ->
            members.chunked(500).forEach { chunk ->
                coroutineContext.ensureActive()
                val byId = chunk.associateBy { Uri.parse(it.contentUri).lastPathSegment.orEmpty() }
                try {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.DATA),
                        "${MediaStore.Audio.Media._ID} IN (${byId.keys.joinToString(",") { "?" }})",
                        byId.keys.toTypedArray(),
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val song = byId[cursor.getLong(0).toString()] ?: continue
                            val path = cursor.getString(1) ?: continue
                            if (path.substringAfterLast('/') == song.displayName && PlaylistPaths.key(path) != null) {
                                paths[song.id] = path
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Provider does not expose path evidence: exact existing content URI remains usable.
                }
            }
        }
        return songs.map { song ->
            PlaylistSource(song, paths[song.id] ?: if (song.sourceType == SongSourceType.DOCUMENT) {
                externalDocumentPath(Uri.parse(song.contentUri))
            } else null)
        }
    }
}

/** Only the platform external-storage provider has the volume/path document contract used here. */
internal fun externalDocumentPath(uri: Uri): String? {
    if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    if (':' !in id) return null
    val root = when {
        volume == "primary" -> "/storage/emulated/0"
        volume.matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) -> "/storage/$volume"
        else -> return null
    }
    val path = PlaylistPaths.normalize("$root/${id.substringAfter(':')}") ?: return null
    return path.takeIf { it.startsWith("$root/") }
}
