package com.libreplayer.media.service

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Adds format observation around the default sink without changing its processing. */
@UnstableApi
internal class PlaybackAudioDiagnosticRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? =
        super.buildAudioSink(
            context,
            enableFloatOutput,
            enableAudioOutputPlaybackParams,
        )?.let(::DiagnosticAudioSink)
}

@UnstableApi
private class DiagnosticAudioSink(
    audioSink: AudioSink,
) : ForwardingAudioSink(audioSink) {
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        PlaybackAudioDiagnostics.onSinkInputFormat(inputFormat, specifiedBufferSize, outputChannels)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
}
