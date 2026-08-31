package com.libreplayer.media.playback

import androidx.media3.common.Player

internal const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L

// Media3 converts public millisecond positions to its internal microsecond timebase.
// Keep the product boundary within a range that cannot overflow that conversion.
internal const val MAX_SAFE_MEDIA3_POSITION_MS = Long.MAX_VALUE / 1_000L

internal enum class PreviousAction {
    RESTART_CURRENT,
    SEEK_PREVIOUS,
    NO_OP,
}

internal enum class PlaybackToggleAction {
    PAUSE,
    PLAY,
    RETRY_CURRENT,
    RESTART_ENDED,
}

internal fun playbackToggleAction(
    playWhenReady: Boolean,
    hasPlaybackError: Boolean,
    playbackState: Int = Player.STATE_READY,
): PlaybackToggleAction = when {
    playbackState == Player.STATE_ENDED -> PlaybackToggleAction.RESTART_ENDED
    playWhenReady -> PlaybackToggleAction.PAUSE
    hasPlaybackError -> PlaybackToggleAction.RETRY_CURRENT
    else -> PlaybackToggleAction.PLAY
}

internal fun primaryControlShowsPause(
    playWhenReady: Boolean,
    playbackState: Int,
): Boolean = playWhenReady && playbackState != Player.STATE_ENDED

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

internal fun normalizedSeekPosition(
    requestedPositionMs: Long,
    knownDurationMs: Long?,
): Long {
    val safePositionMs = requestedPositionMs.coerceIn(0L, MAX_SAFE_MEDIA3_POSITION_MS)
    val safeDurationMs = knownDurationMs
        ?.takeIf { it > 0L }
        ?.coerceAtMost(MAX_SAFE_MEDIA3_POSITION_MS)
    return safeDurationMs?.let { safePositionMs.coerceAtMost(it) } ?: safePositionMs
}
