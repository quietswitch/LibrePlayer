package com.libreplayer.library.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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

data class MediaStoreCheckpoint(
    val version: String,
    val generation: Long,
)

enum class LibraryScanMode {
    INCREMENTAL,
    FULL_REBUILD,
}

data class LibraryScanStatistics(
    val elapsedMillis: Long,
    val mediaStoreRowsRead: Int,
    val mediaStoreIdRowsRead: Int,
    val documentFilesVisited: Int,
    val documentMetadataReads: Int,
    val documentMetadataFailures: Int,
    val cachedSongsReused: Int,
)

sealed interface LibraryScanResult {
    data class Success(
        val songs: List<ScannedSong>,
        val isMediaStoreComplete: Boolean,
        val mediaStoreCheckpoint: MediaStoreCheckpoint?,
        val performedFullReconciliation: Boolean,
        val statistics: LibraryScanStatistics,
    ) : LibraryScanResult

    data object PermissionRequired : LibraryScanResult
    data class Error(val message: String) : LibraryScanResult
}

class DeviceLibraryScanner(
    private val context: Context,
    private val metadataReader: AudioMetadataReader,
) {
    suspend fun scan(
        importedRoots: List<String>,
        cachedSongs: List<ScannedSong>,
        checkpoint: MediaStoreCheckpoint?,
        mode: LibraryScanMode,
    ): LibraryScanResult = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            val cachedMediaStoreSongs = cachedSongs.filterSource(SongSourceType.MEDIA_STORE)
            val cachedDocumentSongs = cachedSongs.filterSource(SongSourceType.DOCUMENT)
            val mediaStoreScan = queryMediaStoreSafely(
                cachedSongs = cachedMediaStoreSongs,
                checkpoint = checkpoint,
                forceFull = mode == LibraryScanMode.FULL_REBUILD,
            )
            val forceFullDocuments = mode == LibraryScanMode.FULL_REBUILD ||
                mediaStoreScan.checkpointInvalidated
            val documentScan = scanImportedRoots(
                importedRoots = importedRoots,
                cachedSongs = cachedDocumentSongs,
                forceFull = forceFullDocuments,
            )
            val mediaStoreSongs = if (mediaStoreScan.permissionRequired) {
                cachedMediaStoreSongs
            } else {
                mediaStoreScan.songs
            }
            val songs = ScannedSongDeduper.dedupe(mediaStoreSongs + documentScan.songs)
            LibraryScanResult.Success(
                songs = songs,
                isMediaStoreComplete = !mediaStoreScan.permissionRequired,
                mediaStoreCheckpoint = mediaStoreScan.checkpoint,
                performedFullReconciliation = !mediaStoreScan.permissionRequired &&
                    forceFullDocuments && documentScan.isComplete,
                statistics = LibraryScanStatistics(
                    elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                    mediaStoreRowsRead = mediaStoreScan.rowsRead,
                    mediaStoreIdRowsRead = mediaStoreScan.idRowsRead,
                    documentFilesVisited = documentScan.filesVisited,
                    documentMetadataReads = documentScan.metadataReads,
                    documentMetadataFailures = documentScan.metadataFailures,
                    cachedSongsReused = mediaStoreScan.cachedSongsReused + documentScan.cachedSongsReused,
                ),
            )
        }.getOrElse { throwable ->
            when (throwable) {
                is SecurityException -> LibraryScanResult.PermissionRequired
                else -> LibraryScanResult.Error(throwable.message ?: "Unable to scan local library.")
            }
        }
    }

    private fun queryMediaStoreSafely(
        cachedSongs: List<ScannedSong>,
        checkpoint: MediaStoreCheckpoint?,
        forceFull: Boolean,
    ): MediaStoreScan =
        try {
            queryMediaStore(cachedSongs, checkpoint, forceFull)
        } catch (_: SecurityException) {
            MediaStoreScan(
                songs = cachedSongs,
                permissionRequired = true,
                checkpoint = checkpoint,
                checkpointInvalidated = false,
                rowsRead = 0,
                idRowsRead = 0,
                cachedSongsReused = cachedSongs.size,
            )
        }

    private fun queryMediaStore(
        cachedSongs: List<ScannedSong>,
        checkpoint: MediaStoreCheckpoint?,
        forceFull: Boolean,
    ): MediaStoreScan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !hasOnlyPrimaryExternalVolume()) {
            val songs = queryMediaStoreSongs(minimumGenerationExclusive = null)
            return MediaStoreScan(
                songs = songs,
                permissionRequired = false,
                checkpoint = currentCheckpointOrNull(),
                checkpointInvalidated = false,
                rowsRead = songs.size,
                idRowsRead = 0,
                cachedSongsReused = 0,
            )
        }

        return queryGenerationAwareMediaStore(cachedSongs, checkpoint, forceFull)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryGenerationAwareMediaStore(
        cachedSongs: List<ScannedSong>,
        checkpoint: MediaStoreCheckpoint?,
        forceFull: Boolean,
    ): MediaStoreScan {
        val currentCheckpoint = currentCheckpointOrNull()
        val syncDecision = decideGenerationSync(checkpoint, currentCheckpoint, forceFull)
        if (syncDecision == GenerationSyncDecision.FULL) {
            val songs = queryMediaStoreSongs(minimumGenerationExclusive = null)
            return MediaStoreScan(
                songs = songs,
                permissionRequired = false,
                checkpoint = currentCheckpoint,
                checkpointInvalidated = !forceFull,
                rowsRead = songs.size,
                idRowsRead = 0,
                cachedSongsReused = 0,
            )
        }

        checkNotNull(currentCheckpoint)
        checkNotNull(checkpoint)
        if (syncDecision == GenerationSyncDecision.UNCHANGED) {
            // Generations identify added/modified rows, but an ID reconciliation is still needed
            // for deletions and for rows that no longer satisfy the music/duration filters.
            val currentIds = queryCurrentMediaStoreIds()
            val songs = mergeIncrementalMediaStoreSongs(cachedSongs, emptyList(), currentIds)
            return MediaStoreScan(
                songs = songs,
                permissionRequired = false,
                checkpoint = currentCheckpoint,
                checkpointInvalidated = false,
                rowsRead = 0,
                idRowsRead = currentIds.size,
                cachedSongsReused = songs.size,
            )
        }

        val changedSongs = queryMediaStoreSongs(checkpoint.generation)
        val currentIds = queryCurrentMediaStoreIds()
        val songs = mergeIncrementalMediaStoreSongs(
            cachedSongs = cachedSongs,
            changedSongs = changedSongs,
            currentIds = currentIds,
        )
        return MediaStoreScan(
            songs = songs,
            permissionRequired = false,
            checkpoint = currentCheckpoint,
            checkpointInvalidated = false,
            rowsRead = changedSongs.size,
            idRowsRead = currentIds.size,
            cachedSongsReused = (songs.size - changedSongs.size).coerceAtLeast(0),
        )
    }

    private fun hasOnlyPrimaryExternalVolume(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            false
        } else {
            MediaStore.getExternalVolumeNames(context) == setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    private fun currentCheckpointOrNull(): MediaStoreCheckpoint? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaStoreCheckpoint(
                version = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY),
                generation = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY),
            )
        }.getOrNull()
    }

    private fun queryMediaStoreSongs(minimumGenerationExclusive: Long?): List<ScannedSong> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mediaStoreAudioProjection(Build.VERSION.SDK_INT)
        val selectionParts = mutableListOf(
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            "${MediaStore.Audio.Media.DURATION} >= 30000",
        )
        val selectionArgs = mutableListOf<String>()
        if (minimumGenerationExclusive != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selectionParts += "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            selectionArgs += minimumGenerationExclusive.toString()
        }

        return buildList {
            context.contentResolver.query(
                collection,
                projection,
                selectionParts.joinToString(" AND "),
                selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.toScannedSong(collection))
                }
            }
        }
    }

    private fun queryCurrentMediaStoreIds(): Set<String> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= 30000"
        return buildSet {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                selection,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (cursor.moveToNext()) {
                    add("media:${cursor.getLong(idColumn)}")
                }
            }
        }
    }

    private fun Cursor.toScannedSong(collection: Uri): ScannedSong {
        val mediaId = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val rawTrackNumber = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK))
        val contentUri = ContentUris.withAppendedId(collection, mediaId)
        val albumId = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
        return ScannedSong(
            id = "media:$mediaId",
            sourceType = SongSourceType.MEDIA_STORE,
            contentUri = contentUri.toString(),
            title = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)),
            artist = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)),
            album = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)),
            durationMs = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
            trackNumber = rawTrackNumber.takeIf { it != 0 }?.rem(1000),
            discNumber = rawTrackNumber.takeIf { it >= 1000 }?.div(1000),
            year = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)).takeIf { it > 0 },
            dateAddedEpochSeconds = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)),
            dateModifiedEpochSeconds = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)),
            displayName = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                ?: mediaId.toString(),
            relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getString(getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH))
            } else {
                null
            },
            mimeType = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)),
            artworkUri = albumArtUri(albumId, contentUri.toString()),
        )
    }

    private fun albumArtUri(albumId: Long, fallbackContentUri: String): String =
        if (albumId > 0L) {
            ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            ).toString()
        } else {
            fallbackContentUri
        }

    private fun scanImportedRoots(
        importedRoots: List<String>,
        cachedSongs: List<ScannedSong>,
        forceFull: Boolean,
    ): DocumentScan {
        val destination = mutableListOf<ScannedSong>()
        val cachedById = cachedSongs.associateBy(ScannedSong::id)
        val counters = DocumentScanCounters()
        var isComplete = true
        importedRoots.forEach { rawUri ->
            val uri = Uri.parse(rawUri)
            val root = runCatching {
                DocumentFile.fromTreeUri(context, uri)
                    ?: DocumentFile.fromSingleUri(context, uri)
            }.getOrNull()
            if (root == null) {
                isComplete = false
                destination += cachedSongs.forRoot(uri)
                return@forEach
            }
            val walkSucceeded = runCatching {
                walkDocument(root, destination, cachedById, forceFull, counters)
            }.isSuccess
            if (!walkSucceeded) {
                isComplete = false
                destination += cachedSongs.forRoot(uri)
            }
        }
        return DocumentScan(
            songs = destination.associateBy(ScannedSong::id).values.toList(),
            filesVisited = counters.filesVisited,
            metadataReads = counters.metadataReads,
            metadataFailures = counters.metadataFailures,
            cachedSongsReused = counters.cachedSongsReused,
            isComplete = isComplete && counters.isComplete,
        )
    }

    private fun walkDocument(
        file: DocumentFile,
        destination: MutableList<ScannedSong>,
        cachedById: Map<String, ScannedSong>,
        forceFull: Boolean,
        counters: DocumentScanCounters,
    ) {
        if (file.isDirectory) {
            file.listFiles().forEach { child ->
                walkDocument(child, destination, cachedById, forceFull, counters)
            }
            return
        }
        if (!file.isFile || !file.isAudioCandidate()) return
        counters.filesVisited++

        val uri = file.uri
        val modifiedSeconds = (file.lastModified() / 1000L).coerceAtLeast(0L)
        val descriptor = DocumentDescriptor(
            id = "document:$uri",
            contentUri = uri.toString(),
            displayName = file.name ?: uri.lastPathSegment.orEmpty(),
            relativePath = uri.path,
            mimeType = file.type,
            dateModifiedEpochSeconds = modifiedSeconds,
        )
        val cached = cachedById[descriptor.id]
        if (!forceFull && cached != null && canReuseCachedDocument(cached, descriptor)) {
            destination += cached
            counters.cachedSongsReused++
            return
        }

        counters.metadataReads++
        val metadata = metadataReader.read(uri)
        if (metadata == null) {
            counters.isComplete = false
            counters.metadataFailures++
            if (cached != null) {
                destination += cached
                counters.cachedSongsReused++
            }
            return
        }
        val duration = metadata.durationMs ?: 0L
        if (duration <= 0L || duration in 1L..<30_000L) return
        destination += ScannedSong(
            id = descriptor.id,
            sourceType = SongSourceType.DOCUMENT,
            contentUri = descriptor.contentUri,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = duration,
            trackNumber = metadata.trackNumber,
            discNumber = metadata.discNumber,
            year = metadata.year,
            dateAddedEpochSeconds = modifiedSeconds,
            dateModifiedEpochSeconds = modifiedSeconds,
            displayName = descriptor.displayName,
            relativePath = descriptor.relativePath,
            mimeType = descriptor.mimeType,
            artworkUri = descriptor.contentUri,
        )
    }

    private fun DocumentFile.isAudioCandidate(): Boolean {
        val type = type.orEmpty().lowercase()
        val name = name.orEmpty().lowercase()
        return type.startsWith("audio/") || SUPPORTED_EXTENSIONS.any(name::endsWith)
    }

    private fun List<ScannedSong>.filterSource(sourceType: SongSourceType): List<ScannedSong> =
        filter { it.sourceType == sourceType }

    private fun List<ScannedSong>.forRoot(rootUri: Uri): List<ScannedSong> {
        if (!DocumentsContract.isTreeUri(rootUri)) {
            return filter { it.contentUri == rootUri.toString() }
        }
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
            ?: return emptyList()
        return filter { song ->
            val songUri = Uri.parse(song.contentUri)
            songUri.authority == rootUri.authority &&
                runCatching { DocumentsContract.getDocumentId(songUri) }
                    .getOrNull()
                    ?.let { documentId -> documentId == treeId || documentId.startsWith("$treeId/") } == true
        }
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
    val checkpoint: MediaStoreCheckpoint?,
    val checkpointInvalidated: Boolean,
    val rowsRead: Int,
    val idRowsRead: Int,
    val cachedSongsReused: Int,
)

private data class DocumentScan(
    val songs: List<ScannedSong>,
    val filesVisited: Int,
    val metadataReads: Int,
    val metadataFailures: Int,
    val cachedSongsReused: Int,
    val isComplete: Boolean,
)

private class DocumentScanCounters(
    var filesVisited: Int = 0,
    var metadataReads: Int = 0,
    var cachedSongsReused: Int = 0,
    var metadataFailures: Int = 0,
    var isComplete: Boolean = true,
)

internal data class DocumentDescriptor(
    val id: String,
    val contentUri: String,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String?,
    val dateModifiedEpochSeconds: Long,
)

internal fun canReuseCachedDocument(
    cached: ScannedSong,
    descriptor: DocumentDescriptor,
): Boolean =
    descriptor.dateModifiedEpochSeconds > 0L &&
        cached.contentUri == descriptor.contentUri &&
        cached.displayName == descriptor.displayName &&
        cached.relativePath == descriptor.relativePath &&
        cached.mimeType == descriptor.mimeType &&
        cached.dateModifiedEpochSeconds == descriptor.dateModifiedEpochSeconds

internal fun mergeIncrementalMediaStoreSongs(
    cachedSongs: List<ScannedSong>,
    changedSongs: List<ScannedSong>,
    currentIds: Set<String>,
): List<ScannedSong> {
    val merged = cachedSongs.associateByTo(LinkedHashMap(), ScannedSong::id)
    changedSongs.forEach { merged[it.id] = it }
    return merged.values.filter { it.id in currentIds }
}

internal enum class GenerationSyncDecision {
    FULL,
    UNCHANGED,
    INCREMENTAL,
}

internal fun decideGenerationSync(
    previous: MediaStoreCheckpoint?,
    current: MediaStoreCheckpoint?,
    forceFull: Boolean,
): GenerationSyncDecision =
    when {
        forceFull -> GenerationSyncDecision.FULL
        previous == null || current == null -> GenerationSyncDecision.FULL
        previous.version != current.version -> GenerationSyncDecision.FULL
        current.generation < previous.generation -> GenerationSyncDecision.FULL
        current.generation == previous.generation -> GenerationSyncDecision.UNCHANGED
        else -> GenerationSyncDecision.INCREMENTAL
    }

internal fun mediaStoreAudioProjection(sdkInt: Int): Array<String> =
    buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.DATE_ADDED)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            add(MediaStore.Audio.Media.RELATIVE_PATH)
        }
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.ALBUM_ID)
        if (sdkInt >= Build.VERSION_CODES.R) {
            add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }
    }.toTypedArray()
