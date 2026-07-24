package com.libreplayer.library.details

enum class AudioEncodingKind {
    LOSSLESS,
    LOSSY,
    UNKNOWN,
}

enum class AudioQualityLabel(val label: String) {
    LOSSLESS("Lossless"),
    HI_RES_LOSSLESS("Hi-Res Lossless"),
    HIGH_BITRATE_LOSSY("High bitrate lossy"),
    COMPRESSED("Compressed"),
    UNKNOWN("Unknown quality"),
}

enum class AudioBitrateMode(val label: String) {
    CBR("CBR"),
    VBR("VBR"),
    UNKNOWN("Unknown"),
}

data class AudioFormatDescriptor(
    val codecLabel: String?,
    val containerLabel: String?,
    val extension: String?,
    val mimeType: String?,
    val encodingKindHint: AudioEncodingKind,
)

data class AudioDetails(
    val songId: String,
    val songUri: String,
    val format: AudioFormatDescriptor,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
    val bitrateMode: AudioBitrateMode,
    val channelCount: Int?,
    val durationMs: Long?,
    val fileSizeBytes: Long?,
    val encodingKind: AudioEncodingKind,
    val qualityLabel: AudioQualityLabel,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val composer: String?,
    val fileName: String?,
    val location: String?,
    val dateAddedEpochSeconds: Long?,
    val lastModifiedEpochMillis: Long?,
    val sourceTypeLabel: String,
    val hasEmbeddedArtwork: Boolean?,
)
