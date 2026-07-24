package com.libreplayer.library.details

import android.media.AudioFormat
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

object AudioDetailsFormatter {
    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())

    fun resolveFormat(
        mimeType: String?,
        extension: String?,
    ): AudioFormatDescriptor {
        val normalizedMime = mimeType?.trim()?.lowercase(Locale.US)
        val normalizedExtension = extension?.trimStart('.')?.lowercase(Locale.US)?.takeIf(String::isNotBlank)

        val codecLabel = when {
            normalizedMime?.contains("flac") == true -> "FLAC"
            normalizedMime?.contains("alac") == true -> "ALAC"
            normalizedMime == "audio/mp4a-latm" -> "AAC"
            normalizedMime == "audio/mpeg" -> "MP3"
            normalizedMime?.contains("opus") == true -> "Opus"
            normalizedMime?.contains("vorbis") == true -> "Vorbis"
            normalizedMime?.contains("wav") == true -> "PCM"
            normalizedMime?.contains("raw") == true -> "PCM"
            normalizedMime?.contains("pcm") == true -> "PCM"
            normalizedMime?.contains("aiff") == true -> "AIFF"
            normalizedExtension == "flac" -> "FLAC"
            normalizedExtension == "mp3" -> "MP3"
            normalizedExtension == "aac" -> "AAC"
            normalizedExtension == "opus" -> "Opus"
            normalizedExtension == "wav" || normalizedExtension == "wave" -> "PCM"
            normalizedExtension == "aif" || normalizedExtension == "aiff" || normalizedExtension == "aifc" -> "AIFF"
            else -> null
        }

        val containerLabel = when (normalizedExtension) {
            "m4a", "m4b" -> "M4A"
            "mp3" -> "MP3"
            "flac" -> "FLAC"
            "wav", "wave" -> "WAV"
            "aif", "aiff", "aifc" -> "AIFF"
            "ogg", "oga" -> "Ogg"
            "opus" -> "Opus"
            "aac" -> "AAC"
            else -> normalizedExtension?.uppercase(Locale.US)
        }

        val encodingKindHint = when {
            normalizedMime?.contains("flac") == true -> AudioEncodingKind.LOSSLESS
            normalizedMime?.contains("alac") == true -> AudioEncodingKind.LOSSLESS
            normalizedMime?.contains("wav") == true -> AudioEncodingKind.LOSSLESS
            normalizedMime?.contains("raw") == true -> AudioEncodingKind.LOSSLESS
            normalizedMime?.contains("pcm") == true -> AudioEncodingKind.LOSSLESS
            normalizedMime?.contains("aiff") == true -> AudioEncodingKind.LOSSLESS
            normalizedMime == "audio/mp4a-latm" -> AudioEncodingKind.LOSSY
            normalizedMime == "audio/mpeg" -> AudioEncodingKind.LOSSY
            normalizedMime?.contains("opus") == true -> AudioEncodingKind.LOSSY
            normalizedMime?.contains("vorbis") == true -> AudioEncodingKind.LOSSY
            normalizedExtension == "flac" -> AudioEncodingKind.LOSSLESS
            normalizedExtension == "wav" || normalizedExtension == "wave" -> AudioEncodingKind.LOSSLESS
            normalizedExtension == "aif" || normalizedExtension == "aiff" || normalizedExtension == "aifc" -> AudioEncodingKind.LOSSLESS
            normalizedExtension == "mp3" || normalizedExtension == "aac" || normalizedExtension == "opus" -> AudioEncodingKind.LOSSY
            else -> AudioEncodingKind.UNKNOWN
        }

        return AudioFormatDescriptor(
            codecLabel = codecLabel,
            containerLabel = containerLabel,
            extension = normalizedExtension,
            mimeType = normalizedMime,
            encodingKindHint = encodingKindHint,
        )
    }

    fun resolveEncodingKind(
        format: AudioFormatDescriptor,
        bitDepth: Int?,
    ): AudioEncodingKind =
        when {
            format.encodingKindHint != AudioEncodingKind.UNKNOWN -> format.encodingKindHint
            format.codecLabel == "PCM" -> AudioEncodingKind.LOSSLESS
            bitDepth != null && format.codecLabel in setOf("FLAC", "ALAC", "AIFF") -> AudioEncodingKind.LOSSLESS
            else -> AudioEncodingKind.UNKNOWN
        }

    fun resolveBitDepth(
        format: AudioFormatDescriptor,
        reportedBitDepth: Int?,
        pcmEncoding: Int?,
    ): Int? {
        val candidate = reportedBitDepth
            ?.takeIf { it > 0 }
            ?: bitDepthFromPcmEncoding(pcmEncoding)
        val encodingKind = resolveEncodingKind(format, candidate)
        return if (encodingKind == AudioEncodingKind.LOSSY) null else candidate
    }

    fun classifyQuality(
        encodingKind: AudioEncodingKind,
        sampleRateHz: Int?,
        bitDepth: Int?,
        bitrateKbps: Int?,
    ): AudioQualityLabel =
        when (encodingKind) {
            AudioEncodingKind.LOSSLESS -> {
                if (
                    sampleRateHz != null &&
                    bitDepth != null &&
                    (sampleRateHz > 48_000 || bitDepth > 16)
                ) {
                    AudioQualityLabel.HI_RES_LOSSLESS
                } else {
                    AudioQualityLabel.LOSSLESS
                }
            }

            AudioEncodingKind.LOSSY -> {
                when {
                    bitrateKbps == null -> AudioQualityLabel.UNKNOWN
                    bitrateKbps >= 256 -> AudioQualityLabel.HIGH_BITRATE_LOSSY
                    else -> AudioQualityLabel.COMPRESSED
                }
            }

            AudioEncodingKind.UNKNOWN -> AudioQualityLabel.UNKNOWN
        }

    fun formatSummary(details: AudioDetails): String {
        val parts = mutableListOf<String>()
        parts += formatDisplayLabel(details.format)
        details.bitDepth?.let { parts += formatBitDepth(it) }
        if (details.bitrateMode != AudioBitrateMode.UNKNOWN) {
            parts += details.bitrateMode.label
        }
        if (details.bitrateKbps != null && (details.encodingKind == AudioEncodingKind.LOSSY || details.bitDepth == null)) {
            parts += formatBitrate(details.bitrateKbps)
        }
        details.sampleRateHz?.let { parts += formatSampleRate(it) }
        details.channelCount?.let { parts += formatChannels(it) }
        if (details.qualityLabel != AudioQualityLabel.UNKNOWN) {
            parts += details.qualityLabel.label
        }
        return parts.joinToString(separator = " | ")
    }

    fun formatDisplayLabel(format: AudioFormatDescriptor): String =
        when {
            format.codecLabel == "PCM" && format.containerLabel != null -> "${format.containerLabel} / PCM"
            format.codecLabel != null && format.extension != null && format.containerLabel == "M4A" ->
                "${format.codecLabel} (.${format.extension})"
            format.codecLabel != null -> format.codecLabel
            format.containerLabel != null -> format.containerLabel
            else -> "Unknown format"
        }

    fun formatSampleRate(sampleRateHz: Int?): String {
        if (sampleRateHz == null || sampleRateHz <= 0) return "Unknown"
        val value = sampleRateHz / 1000.0
        val pattern = if (value % 1.0 == 0.0) "0" else "0.0"
        return "${DecimalFormat(pattern).format(value)} kHz"
    }

    fun formatBitDepth(bitDepth: Int?): String =
        bitDepth?.takeIf { it > 0 }?.let { "$it-bit" } ?: "Unknown"

    fun formatBitrate(bitrateKbps: Int?): String =
        bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" } ?: "Unknown"

    fun formatChannels(channelCount: Int?): String =
        when (channelCount) {
            null, 0 -> "Unknown"
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channelCount channels"
        }

    fun formatFileSize(fileSizeBytes: Long?): String {
        if (fileSizeBytes == null || fileSizeBytes <= 0L) return "Unknown"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = fileSizeBytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val pattern = if (value >= 10 || unitIndex == 0) "0" else "0.0"
        return "${DecimalFormat(pattern).format(value)} ${units[unitIndex]}"
    }

    fun formatExtension(extension: String?): String =
        extension?.takeIf(String::isNotBlank)?.let { ".${it.lowercase(Locale.US)}" } ?: "Unknown"

    fun formatDateTime(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return "Unknown"
        return runCatching {
            dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
        }.getOrDefault("Unknown")
    }

    fun formatDateAdded(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0L) return "Unknown"
        return formatDateTime(epochSeconds * 1000L)
    }

    fun encodingLabel(kind: AudioEncodingKind): String =
        when (kind) {
            AudioEncodingKind.LOSSLESS -> "Lossless"
            AudioEncodingKind.LOSSY -> "Lossy"
            AudioEncodingKind.UNKNOWN -> "Unknown"
        }

    fun artworkLabel(hasEmbeddedArtwork: Boolean?): String =
        when (hasEmbeddedArtwork) {
            true -> "Yes"
            false -> "No"
            null -> "Unknown"
        }

    fun bitDepthFromPcmEncoding(pcmEncoding: Int?): Int? =
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> null
        }

    fun plainText(details: AudioDetails): String = buildString {
        appendLine("Summary")
        appendLine(formatSummary(details).ifBlank { "Unknown format" })
        appendLine()
        appendLine("Audio")
        appendLine("Format: ${formatDisplayLabel(details.format)}")
        appendLine("Container: ${details.format.containerLabel ?: "Unknown"}")
        appendLine("Extension: ${formatExtension(details.format.extension)}")
        appendLine("Sample rate: ${formatSampleRate(details.sampleRateHz)}")
        appendLine("Bit depth: ${formatBitDepth(details.bitDepth)}")
        appendLine("Bitrate: ${formatBitrate(details.bitrateKbps)}")
        appendLine("Bitrate mode: ${details.bitrateMode.label}")
        appendLine("Channels: ${formatChannels(details.channelCount)}")
        appendLine("Duration: ${details.durationMs?.let(::formatDurationText) ?: "Unknown"}")
        appendLine("File size: ${formatFileSize(details.fileSizeBytes)}")
        appendLine("Encoding: ${encodingLabel(details.encodingKind)}")
        appendLine("Quality: ${details.qualityLabel.label}")
        appendLine()
        appendLine("Tags")
        appendLine("Title: ${orUnknown(details.title)}")
        appendLine("Artist: ${orUnknown(details.artist)}")
        appendLine("Album: ${orUnknown(details.album)}")
        appendLine("Album artist: ${orUnknown(details.albumArtist)}")
        appendLine("Track number: ${details.trackNumber?.toString() ?: "Unknown"}")
        appendLine("Disc number: ${details.discNumber?.toString() ?: "Unknown"}")
        appendLine("Year: ${details.year?.toString() ?: "Unknown"}")
        appendLine("Genre: ${orUnknown(details.genre)}")
        appendLine("Composer: ${orUnknown(details.composer)}")
        appendLine()
        appendLine("File")
        appendLine("File name: ${orUnknown(details.fileName)}")
        appendLine("Location: ${orUnknown(details.location)}")
        appendLine("Date added: ${formatDateAdded(details.dateAddedEpochSeconds)}")
        appendLine("Last modified: ${formatDateTime(details.lastModifiedEpochMillis)}")
        appendLine("Source type: ${details.sourceTypeLabel}")
        appendLine("Embedded artwork: ${artworkLabel(details.hasEmbeddedArtwork)}")
    }

    private fun formatDurationText(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun orUnknown(value: String?): String = value?.takeIf(String::isNotBlank) ?: "Unknown"
}
