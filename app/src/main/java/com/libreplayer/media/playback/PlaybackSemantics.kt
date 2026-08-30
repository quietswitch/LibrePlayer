package com.libreplayer.media.playback

import androidx.media3.common.Player

internal const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L

internal enum class PreviousAction {
    RESTART_CURRENT,
    SEEK_PREVIOUS,
    NO_OP,
}

internal enum class PlaybackToggleAction {
    PAUSE,
    PLAY,
    RETRY_CURRENT,
}

internal fun playbackToggleAction(
    playWhenReady: Boolean,
    hasPlaybackError: Boolean,
): PlaybackToggleAction = when {
    playWhenReady -> PlaybackToggleAction.PAUSE
    hasPlaybackError -> PlaybackToggleAction.RETRY_CURRENT
    else -> PlaybackToggleAction.PLAY
}

internal fun previousAction(
    currentPositionMs: Long,
    hasPreviousMediaItem: Boolean,
): PreviousAction = when {
    currentPositionMs > PREVIOUS_RESTART_THRESHOLD_MS -> PreviousAction.RESTART_CURRENT
    hasPreviousMediaItem -> PreviousAction.SEEK_PREVIOUS
    else -> PreviousAction.NO_OP
}

internal fun nextRepeatMode(currentRepeatMode: Int): Int =
    when (currentRepeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

internal fun isValidQueueSelection(queueSize: Int, index: Int): Boolean =
    queueSize > 0 && index in 0 until queueSize

internal fun queueOccurrenceKey(index: Int, mediaId: String): String = "$index:$mediaId"
