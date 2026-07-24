package com.libreplayer.library.details

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class AudioDetailsReader(
    private val contentResolverOwner: android.content.Context,
) {
    private val context = contentResolverOwner.applicationContext

    suspend fun read(song: Song): AudioDetails = withContext(Dispatchers.IO) {
        val uri = song.content
        val queryMetadata = queryMetadata(uri)
        val retrieverMetadata = readRetrieverMetadata(uri)
        val extractorMetadata = readExtractorMetadata(uri)

        buildDetails(
            song = song,
            queryMetadata = queryMetadata,
            retrieverMetadata = retrieverMetadata,
            extractorMetadata = extractorMetadata,
        )
    }

    fun fallback(song: Song): AudioDetails =
        buildDetails(
            song = song,
            queryMetadata = QueryMetadata(),
            retrieverMetadata = RetrieverMetadata(),
            extractorMetadata = ExtractorMetadata(),
        )

    private fun buildDetails(
        song: Song,
        queryMetadata: QueryMetadata,
        retrieverMetadata: RetrieverMetadata,
        extractorMetadata: ExtractorMetadata,
    ): AudioDetails {
        val extension = extensionFrom(
            song.displayName,
            queryMetadata.displayName,
            song.content,
        )
        val mimeType = extractorMetadata.mimeType ?: queryMetadata.mimeType ?: song.mimeType
        val format = AudioDetailsFormatter.resolveFormat(
            mimeType = mimeType,
            extension = extension,
        )
        val bitDepth = AudioDetailsFormatter.resolveBitDepth(
            format = format,
            reportedBitDepth = retrieverMetadata.bitsPerSample,
            pcmEncoding = extractorMetadata.pcmEncoding,
        )
        val sampleRateHz = retrieverMetadata.sampleRateHz ?: extractorMetadata.sampleRateHz
        val bitrateKbps = firstPositive(
            retrieverMetadata.bitrateBps,
            extractorMetadata.bitrateBps,
        )?.let { (it / 1000.0).roundToInt() }
        val durationMs = song.durationMs.takeIf { it > 0L }
            ?: retrieverMetadata.durationMs
            ?: extractorMetadata.durationMs
        val encodingKind = AudioDetailsFormatter.resolveEncodingKind(
            format = format,
            bitDepth = bitDepth,
        )
        val sourceTypeLabel = when (song.sourceType) {
            SongSourceType.MEDIA_STORE -> "MediaStore"
            SongSourceType.DOCUMENT -> if (song.contentUri.contains("/tree/")) "Imported folder / SAF" else "Imported file"
        }
        val lastModifiedEpochMillis = when {
            song.dateModifiedEpochSeconds > 0L -> song.dateModifiedEpochSeconds * 1000L
            else -> queryMetadata.lastModifiedEpochMillis
        }
        val fileName = song.displayName.takeIf(String::isNotBlank)
            ?: queryMetadata.displayName
            ?: song.content.lastPathSegment
        val location = song.relativePath?.takeIf(String::isNotBlank)
            ?: queryMetadata.locationHint
            ?: song.content.toString()

        return AudioDetails(
            songId = song.id,
            songUri = song.contentUri,
            format = format,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            bitrateMode = AudioBitrateMode.UNKNOWN,
            channelCount = extractorMetadata.channelCount,
            durationMs = durationMs,
            fileSizeBytes = queryMetadata.fileSizeBytes,
            encodingKind = encodingKind,
            qualityLabel = AudioDetailsFormatter.classifyQuality(
                encodingKind = encodingKind,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                bitrateKbps = bitrateKbps,
            ),
            title = song.title ?: retrieverMetadata.title,
            artist = song.artist ?: retrieverMetadata.artist,
            album = song.album ?: retrieverMetadata.album,
            albumArtist = retrieverMetadata.albumArtist,
            trackNumber = song.trackNumber ?: retrieverMetadata.trackNumber,
            discNumber = song.discNumber ?: retrieverMetadata.discNumber,
            year = song.year ?: retrieverMetadata.year,
            genre = retrieverMetadata.genre,
            composer = retrieverMetadata.composer,
            fileName = fileName,
            location = location,
            dateAddedEpochSeconds = song.dateAddedEpochSeconds.takeIf { it > 0L },
            lastModifiedEpochMillis = lastModifiedEpochMillis,
            sourceTypeLabel = sourceTypeLabel,
            hasEmbeddedArtwork = retrieverMetadata.hasEmbeddedArtwork,
        )
    }

    private fun queryMetadata(uri: Uri): QueryMetadata {
        val cursorMetadata = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    MediaStore.MediaColumns.MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use QueryMetadata()
                QueryMetadata(
                    displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                    fileSizeBytes = cursor.longOrNull(OpenableColumns.SIZE),
                    lastModifiedEpochMillis = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    mimeType = cursor.stringOrNull(MediaStore.MediaColumns.MIME_TYPE),
                )
            }
        }.getOrNull() ?: QueryMetadata()

        val documentMetadata = runCatching {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
                ?: DocumentFile.fromTreeUri(context, uri)
                ?: return@runCatching QueryMetadata()
            QueryMetadata(
                displayName = documentFile.name,
                fileSizeBytes = documentFile.length().takeIf { it > 0L },
                lastModifiedEpochMillis = documentFile.lastModified().takeIf { it > 0L },
                mimeType = documentFile.type,
                locationHint = documentFile.uri.path,
            )
        }.getOrDefault(QueryMetadata())

        val assetLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull()

        return QueryMetadata(
            displayName = cursorMetadata.displayName ?: documentMetadata.displayName,
            fileSizeBytes = cursorMetadata.fileSizeBytes ?: documentMetadata.fileSizeBytes ?: assetLength,
            lastModifiedEpochMillis = cursorMetadata.lastModifiedEpochMillis ?: documentMetadata.lastModifiedEpochMillis,
            mimeType = cursorMetadata.mimeType ?: documentMetadata.mimeType,
            locationHint = documentMetadata.locationHint,
        )
    }

    private fun readRetrieverMetadata(uri: Uri): RetrieverMetadata =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                RetrieverMetadata(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    trackNumber = parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                    discNumber = parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                    year = parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)),
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                    sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
                    bitsPerSample = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull(),
                    hasEmbeddedArtwork = retriever.embeddedPicture != null,
                )
            } finally {
                retriever.release()
            }
        }.getOrDefault(RetrieverMetadata())

    private fun readExtractorMetadata(uri: Uri): ExtractorMetadata =
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, emptyMap())
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mimeType = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.US).orEmpty()
                    if (!mimeType.startsWith("audio/")) continue
                    return@runCatching ExtractorMetadata(
                        mimeType = format.getString(MediaFormat.KEY_MIME),
                        sampleRateHz = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                        bitrateBps = format.getLongOrNull(MediaFormat.KEY_BIT_RATE),
                        pcmEncoding = format.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING),
                        durationMs = format.getLongOrNull(MediaFormat.KEY_DURATION)?.div(1000L),
                    )
                }
                ExtractorMetadata()
            } finally {
                extractor.release()
            }
        }.getOrDefault(ExtractorMetadata())

    private fun extensionFrom(
        primaryDisplayName: String?,
        secondaryDisplayName: String?,
        uri: Uri,
    ): String? {
        val name = listOf(primaryDisplayName, secondaryDisplayName, uri.lastPathSegment)
            .firstOrNull { !it.isNullOrBlank() }
            ?: return null
        return name.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != name }
            ?.lowercase(Locale.US)
    }

    private fun parseNumber(raw: String?): Int? =
        raw?.substringBefore('/')
            ?.trim()
            ?.toIntOrNull()

    private fun firstPositive(vararg values: Long?): Long? =
        values.firstOrNull { value -> value != null && value > 0L }

    private fun android.database.Cursor.stringOrNull(columnName: String): String? {
        val columnIndex = getColumnIndex(columnName)
        if (columnIndex < 0 || isNull(columnIndex)) return null
        return getString(columnIndex)
    }

    private fun android.database.Cursor.longOrNull(columnName: String): Long? {
        val columnIndex = getColumnIndex(columnName)
        if (columnIndex < 0 || isNull(columnIndex)) return null
        return getLong(columnIndex)
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}

private data class QueryMetadata(
    val displayName: String? = null,
    val fileSizeBytes: Long? = null,
    val lastModifiedEpochMillis: Long? = null,
    val mimeType: String? = null,
    val locationHint: String? = null,
)

private data class RetrieverMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val composer: String? = null,
    val durationMs: Long? = null,
    val bitrateBps: Long? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val hasEmbeddedArtwork: Boolean? = null,
)

private data class ExtractorMetadata(
    val mimeType: String? = null,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val bitrateBps: Long? = null,
    val pcmEncoding: Int? = null,
    val durationMs: Long? = null,
)
