package com.libreplayer.library.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.library.metadata.AudioMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScannedSong(
    val id: String,
    val sourceType: SongSourceType,
    val contentUri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val dateAddedEpochSeconds: Long,
    val dateModifiedEpochSeconds: Long,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val artworkUri: String?,
)

sealed interface LibraryScanResult {
    data class Success(val songs: List<ScannedSong>) : LibraryScanResult
    data object PermissionRequired : LibraryScanResult
    data class Error(val message: String) : LibraryScanResult
}

class DeviceLibraryScanner(
    private val context: Context,
    private val metadataReader: AudioMetadataReader,
) {
    suspend fun scan(importedRoots: List<String>): LibraryScanResult = withContext(Dispatchers.IO) {
        runCatching {
            val mediaStoreScan = queryMediaStoreSafely()
            val importedSongs = scanImportedRoots(importedRoots)
            val songs = ScannedSongDeduper.dedupe(mediaStoreScan.songs + importedSongs)
            when {
                songs.isEmpty() && mediaStoreScan.permissionRequired -> LibraryScanResult.PermissionRequired
                else -> LibraryScanResult.Success(songs)
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is SecurityException -> LibraryScanResult.PermissionRequired
                else -> LibraryScanResult.Error(throwable.message ?: "Unable to scan local library.")
            }
        }
    }

    private fun queryMediaStoreSafely(): MediaStoreScan =
        try {
            MediaStoreScan(
                songs = queryMediaStore(),
                permissionRequired = false,
            )
        } catch (_: SecurityException) {
            MediaStoreScan(
                songs = emptyList(),
                permissionRequired = true,
            )
        }

    private fun queryMediaStore(): List<ScannedSong> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= 30000")
        }

        return buildList {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    val trackNumber = cursor.getInt(trackColumn).let { if (it == 0) null else it % 1000 }
                    val discNumber = cursor.getInt(trackColumn).let { if (it >= 1000) it / 1000 else null }
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val contentUri = ContentUris.withAppendedId(collection, mediaId)
                    val albumId = cursor.getLong(albumIdColumn)
                    add(
                        ScannedSong(
                            id = "media:$mediaId",
                            sourceType = SongSourceType.MEDIA_STORE,
                            contentUri = contentUri.toString(),
                            title = cursor.getString(titleColumn),
                            artist = cursor.getString(artistColumn),
                            album = cursor.getString(albumColumn),
                            durationMs = cursor.getLong(durationColumn),
                            trackNumber = trackNumber,
                            discNumber = discNumber,
                            year = cursor.getInt(yearColumn).takeIf { it > 0 },
                            dateAddedEpochSeconds = dateAdded,
                            dateModifiedEpochSeconds = dateModified,
                            displayName = cursor.getString(displayNameColumn) ?: mediaId.toString(),
                            relativePath = cursor.getString(relativePathColumn),
                            mimeType = cursor.getString(mimeTypeColumn),
                            artworkUri = albumArtUri(albumId, contentUri.toString()),
                        ),
                    )
                }
            }
        }
    }

    private fun albumArtUri(albumId: Long, fallbackContentUri: String): String =
        if (albumId > 0L) {
            ContentUris.withAppendedId(
                android.net.Uri.parse("content://media/external/audio/albumart"),
                albumId,
            ).toString()
        } else {
            fallbackContentUri
        }

    private fun scanImportedRoots(importedRoots: List<String>): List<ScannedSong> = buildList {
        importedRoots.forEach { rawUri ->
            val uri = Uri.parse(rawUri)
            val root = runCatching {
                DocumentFile.fromTreeUri(context, uri)
                    ?: DocumentFile.fromSingleUri(context, uri)
            }.getOrNull()
                ?: return@forEach
            runCatching {
                walkDocument(root, this)
            }
        }
    }

    private fun walkDocument(
        file: DocumentFile,
        destination: MutableList<ScannedSong>,
    ) {
        if (file.isDirectory) {
            file.listFiles().forEach { child -> walkDocument(child, destination) }
            return
        }
        if (!file.isFile || !file.isAudioCandidate()) return

        val uri = file.uri
        val metadata = metadataReader.read(uri)
        val duration = metadata.durationMs ?: 0L
        if (duration <= 0L) return
        if (duration in 1L..<30_000L) return

        destination +=
            ScannedSong(
                id = "document:$uri",
                sourceType = SongSourceType.DOCUMENT,
                contentUri = uri.toString(),
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = duration,
                trackNumber = metadata.trackNumber,
                discNumber = metadata.discNumber,
                year = metadata.year,
                dateAddedEpochSeconds = (file.lastModified() / 1000L).coerceAtLeast(0L),
                dateModifiedEpochSeconds = (file.lastModified() / 1000L).coerceAtLeast(0L),
                displayName = file.name ?: uri.lastPathSegment.orEmpty(),
                relativePath = file.uri.path,
                mimeType = file.type,
                artworkUri = uri.toString(),
            )
    }

    private fun DocumentFile.isAudioCandidate(): Boolean {
        val type = type.orEmpty().lowercase()
        val name = name.orEmpty().lowercase()
        return type.startsWith("audio/") || SUPPORTED_EXTENSIONS.any(name::endsWith)
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = listOf(
            ".mp3",
            ".flac",
            ".m4a",
            ".aac",
            ".ogg",
            ".opus",
            ".wav",
            ".amr",
        )
    }
}

private data class MediaStoreScan(
    val songs: List<ScannedSong>,
    val permissionRequired: Boolean,
)
