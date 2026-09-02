package com.libreplayer.library.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.libreplayer.library.semantics.parseMetadataOrdinal
import com.libreplayer.library.semantics.parseMetadataYear

data class ExtractedMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
)

class AudioMetadataReader(private val context: Context) {
    fun read(uri: Uri): ExtractedMetadata? =
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.use {
                it.setDataSource(context, uri)
                ExtractedMetadata(
                    title = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    trackNumber = parseMetadataOrdinal(
                        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    ),
                    discNumber = parseMetadataOrdinal(
                        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    ),
                    year = parseMetadataYear(
                        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    ),
                )
            }
        }.getOrNull()

    private fun MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> ExtractedMetadata): ExtractedMetadata =
        try {
            block(this)
        } finally {
            release()
        }
}
