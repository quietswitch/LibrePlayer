package com.libreplayer.media.service

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/** Debug-only visibility into the platform-dependent part of the playback pipeline. */
@UnstableApi
internal object PlaybackAudioDiagnostics : AnalyticsListener {
    private const val TAG = "LibrePlayerAudio"

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.d(TAG, "decoder initialized: name=$decoderName durationMs=$initializationDurationMs")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.d(
            TAG,
            "input format: container=${format.containerMimeType} sample=${format.sampleMimeType} " +
                "codecs=${format.codecs} rateHz=${format.sampleRate} channels=${format.channelCount} " +
                "pcmEncoding=${format.pcmEncoding} encoderDelay=${format.encoderDelay} " +
                "encoderPadding=${format.encoderPadding} bitrate=${format.bitrate}",
        )
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        Log.d(
            TAG,
            "AudioTrack initialized: rateHz=${audioTrackConfig.sampleRate} " +
                "encoding=${audioTrackConfig.encoding} channelConfig=${audioTrackConfig.channelConfig} " +
                "bufferBytes=${audioTrackConfig.bufferSize} offload=${audioTrackConfig.offload} " +
                "tunneling=${audioTrackConfig.tunneling}",
        )
    }

    override fun onAudioSessionIdChanged(
        eventTime: AnalyticsListener.EventTime,
        audioSessionId: Int,
    ) {
        Log.d(TAG, "audio session: id=$audioSessionId")
    }

    override fun onAudioAttributesChanged(
        eventTime: AnalyticsListener.EventTime,
        audioAttributes: AudioAttributes,
    ) {
        Log.d(
            TAG,
            "audio attributes: usage=${audioAttributes.usage} contentType=${audioAttributes.contentType} " +
                "flags=${audioAttributes.flags}",
        )
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Log.w(
            TAG,
            "underrun: bufferBytes=$bufferSize bufferMs=$bufferSizeMs " +
                "elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs",
        )
    }

    override fun onAudioCodecError(
        eventTime: AnalyticsListener.EventTime,
        audioCodecError: Exception,
    ) {
        Log.e(TAG, "audio codec error", audioCodecError)
    }

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) {
        Log.e(TAG, "audio sink error", audioSinkError)
    }
}
