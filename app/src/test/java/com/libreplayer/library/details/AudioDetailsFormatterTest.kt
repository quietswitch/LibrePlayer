package com.libreplayer.library.details

import android.media.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioDetailsFormatterTest {
    @Test
    fun `resolveFormat recognizes flac as lossless`() {
        val format = AudioDetailsFormatter.resolveFormat(
            mimeType = "audio/flac",
            extension = "flac",
        )

        assertThat(format.codecLabel).isEqualTo("FLAC")
        assertThat(format.containerLabel).isEqualTo("FLAC")
        assertThat(format.encodingKindHint).isEqualTo(AudioEncodingKind.LOSSLESS)
    }

    @Test
    fun `resolveFormat labels aac in m4a container`() {
        val format = AudioDetailsFormatter.resolveFormat(
            mimeType = "audio/mp4a-latm",
            extension = "m4a",
        )

        assertThat(AudioDetailsFormatter.formatDisplayLabel(format)).isEqualTo("AAC (.m4a)")
        assertThat(format.encodingKindHint).isEqualTo(AudioEncodingKind.LOSSY)
    }

    @Test
    fun `resolveFormat stays conservative for unknown m4a codec`() {
        val format = AudioDetailsFormatter.resolveFormat(
            mimeType = null,
            extension = "m4a",
        )

        assertThat(format.codecLabel).isNull()
        assertThat(AudioDetailsFormatter.formatDisplayLabel(format)).isEqualTo("M4A")
        assertThat(format.encodingKindHint).isEqualTo(AudioEncodingKind.UNKNOWN)
    }

    @Test
    fun `classifyQuality returns hi res lossless only when sample rate and bit depth are known`() {
        assertThat(
            AudioDetailsFormatter.classifyQuality(
                encodingKind = AudioEncodingKind.LOSSLESS,
                sampleRateHz = 96_000,
                bitDepth = 24,
                bitrateKbps = 2_300,
            ),
        ).isEqualTo(AudioQualityLabel.HI_RES_LOSSLESS)

        assertThat(
            AudioDetailsFormatter.classifyQuality(
                encodingKind = AudioEncodingKind.LOSSLESS,
                sampleRateHz = 96_000,
                bitDepth = null,
                bitrateKbps = 2_300,
            ),
        ).isEqualTo(AudioQualityLabel.LOSSLESS)
    }

    @Test
    fun `classifyQuality distinguishes high bitrate lossy from compressed`() {
        assertThat(
            AudioDetailsFormatter.classifyQuality(
                encodingKind = AudioEncodingKind.LOSSY,
                sampleRateHz = 44_100,
                bitDepth = null,
                bitrateKbps = 320,
            ),
        ).isEqualTo(AudioQualityLabel.HIGH_BITRATE_LOSSY)

        assertThat(
            AudioDetailsFormatter.classifyQuality(
                encodingKind = AudioEncodingKind.LOSSY,
                sampleRateHz = 44_100,
                bitDepth = null,
                bitrateKbps = 160,
            ),
        ).isEqualTo(AudioQualityLabel.COMPRESSED)
    }

    @Test
    fun `resolveBitDepth suppresses lossy bit depth guesses`() {
        val mp3Format = AudioDetailsFormatter.resolveFormat(
            mimeType = "audio/mpeg",
            extension = "mp3",
        )

        assertThat(
            AudioDetailsFormatter.resolveBitDepth(
                format = mp3Format,
                reportedBitDepth = 16,
                pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        ).isNull()
    }

    @Test
    fun `resolveBitDepth keeps explicit lossless bit depth`() {
        val flacFormat = AudioDetailsFormatter.resolveFormat(
            mimeType = "audio/flac",
            extension = "flac",
        )

        assertThat(
            AudioDetailsFormatter.resolveBitDepth(
                format = flacFormat,
                reportedBitDepth = 24,
                pcmEncoding = null,
            ),
        ).isEqualTo(24)
    }

    @Test
    fun `summary line prefers meaningful verified fields`() {
        val details = audioDetails(
            format = AudioDetailsFormatter.resolveFormat("audio/flac", "flac"),
            bitDepth = 24,
            sampleRateHz = 96_000,
            channelCount = 2,
            encodingKind = AudioEncodingKind.LOSSLESS,
            qualityLabel = AudioQualityLabel.HI_RES_LOSSLESS,
        )

        assertThat(AudioDetailsFormatter.formatSummary(details))
            .isEqualTo("FLAC | 24-bit | 96 kHz | Stereo | Hi-Res Lossless")
    }

    @Test
    fun `summary line omits bit depth for lossy audio`() {
        val details = audioDetails(
            format = AudioDetailsFormatter.resolveFormat("audio/mpeg", "mp3"),
            bitrateKbps = 248,
            sampleRateHz = 44_100,
            channelCount = 2,
            encodingKind = AudioEncodingKind.LOSSY,
            qualityLabel = AudioQualityLabel.COMPRESSED,
        )

        assertThat(AudioDetailsFormatter.formatSummary(details))
            .isEqualTo("MP3 | 248 kbps | 44.1 kHz | Stereo | Compressed")
    }

    @Test
    fun `summary line falls back to unknown format when metadata is incomplete`() {
        val details = audioDetails(
            format = AudioDetailsFormatter.resolveFormat(null, null),
            encodingKind = AudioEncodingKind.UNKNOWN,
            qualityLabel = AudioQualityLabel.UNKNOWN,
        )

        assertThat(AudioDetailsFormatter.formatSummary(details)).isEqualTo("Unknown format")
    }

    @Test
    fun `format helpers return unknown when values are missing`() {
        assertThat(AudioDetailsFormatter.formatSampleRate(null)).isEqualTo("Unknown")
        assertThat(AudioDetailsFormatter.formatBitDepth(null)).isEqualTo("Unknown")
        assertThat(AudioDetailsFormatter.formatBitrate(null)).isEqualTo("Unknown")
        assertThat(AudioDetailsFormatter.formatChannels(null)).isEqualTo("Unknown")
        assertThat(AudioDetailsFormatter.formatFileSize(null)).isEqualTo("Unknown")
    }

    @Test
    fun `bitDepthFromPcmEncoding maps only explicit pcm encodings`() {
        assertThat(AudioDetailsFormatter.bitDepthFromPcmEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)).isEqualTo(24)
        assertThat(AudioDetailsFormatter.bitDepthFromPcmEncoding(AudioFormat.ENCODING_PCM_FLOAT)).isEqualTo(32)
        assertThat(AudioDetailsFormatter.bitDepthFromPcmEncoding(AudioFormat.ENCODING_MP3)).isNull()
    }
}

private fun audioDetails(
    format: AudioFormatDescriptor,
    sampleRateHz: Int? = null,
    bitDepth: Int? = null,
    bitrateKbps: Int? = null,
    channelCount: Int? = null,
    encodingKind: AudioEncodingKind = AudioEncodingKind.UNKNOWN,
    qualityLabel: AudioQualityLabel = AudioQualityLabel.UNKNOWN,
) = AudioDetails(
    songId = "song-1",
    songUri = "content://song/1",
    format = format,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    bitrateKbps = bitrateKbps,
    bitrateMode = AudioBitrateMode.UNKNOWN,
    channelCount = channelCount,
    durationMs = 240_000L,
    fileSizeBytes = 12_345_678L,
    encodingKind = encodingKind,
    qualityLabel = qualityLabel,
    title = "Track",
    artist = "Artist",
    album = "Album",
    albumArtist = "Artist",
    trackNumber = 1,
    discNumber = 1,
    year = 2024,
    genre = "Electronic",
    composer = "Composer",
    fileName = "track.flac",
    location = "Music/Album",
    dateAddedEpochSeconds = 1_700_000_000L,
    lastModifiedEpochMillis = 1_700_000_000_000L,
    sourceTypeLabel = "MediaStore",
    hasEmbeddedArtwork = true,
)
