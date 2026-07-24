package com.libreplayer.library.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

data class ExtractedMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val embeddedArtwork: Bitmap?,
)

class AudioMetadataReader(private val context: Context) {
    fun read(uri: Uri): ExtractedMetadata =
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.use {
                it.setDataSource(context, uri)
                ExtractedMetadata(
                    title = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    trackNumber = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        ?.toIntOrNull(),
                    discNumber = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                        ?.toIntOrNull(),
                    year = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                    embeddedArtwork = it.embeddedPicture?.let(::decodeByteArraySafe),
                )
            }
        }.getOrElse {
            ExtractedMetadata(
                title = null,
                artist = null,
                album = null,
                durationMs = null,
                trackNumber = null,
                discNumber = null,
                year = null,
                embeddedArtwork = null,
            )
        }

    private fun MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> ExtractedMetadata): ExtractedMetadata =
        try {
            block(this)
        } finally {
            release()
        }

    private companion object {
        fun decodeByteArraySafe(bytes: ByteArray): Bitmap? =
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
}
