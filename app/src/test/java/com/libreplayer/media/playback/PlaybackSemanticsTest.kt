package com.libreplayer.media.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSemanticsTest {
    @Test
    fun `toggle pauses whenever playback intent is active`() {
        assertThat(playbackToggleAction(playWhenReady = true, hasPlaybackError = false))
            .isEqualTo(PlaybackToggleAction.PAUSE)
        assertThat(playbackToggleAction(playWhenReady = true, hasPlaybackError = true))
            .isEqualTo(PlaybackToggleAction.PAUSE)
    }

    @Test
    fun `toggle plays when paused and retries a paused error`() {
        assertThat(playbackToggleAction(playWhenReady = false, hasPlaybackError = false))
            .isEqualTo(PlaybackToggleAction.PLAY)
        assertThat(playbackToggleAction(playWhenReady = false, hasPlaybackError = true))
            .isEqualTo(PlaybackToggleAction.RETRY_CURRENT)
    }

    @Test
    fun `previous restarts current item only beyond threshold`() {
        assertThat(previousAction(5_001L, hasPreviousMediaItem = true))
            .isEqualTo(PreviousAction.RESTART_CURRENT)
        assertThat(previousAction(5_001L, hasPreviousMediaItem = false))
            .isEqualTo(PreviousAction.RESTART_CURRENT)
    }

    @Test
    fun `previous at threshold selects previous item when present`() {
        assertThat(previousAction(PREVIOUS_RESTART_THRESHOLD_MS, hasPreviousMediaItem = true))
            .isEqualTo(PreviousAction.SEEK_PREVIOUS)
        assertThat(previousAction(0L, hasPreviousMediaItem = true))
            .isEqualTo(PreviousAction.SEEK_PREVIOUS)
    }

    @Test
    fun `previous at start of first item is a no-op`() {
        assertThat(previousAction(0L, hasPreviousMediaItem = false))
            .isEqualTo(PreviousAction.NO_OP)
    }

    @Test
    fun `repeat cycle is off all one off`() {
        assertThat(nextRepeatMode(Player.REPEAT_MODE_OFF)).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(nextRepeatMode(Player.REPEAT_MODE_ALL)).isEqualTo(Player.REPEAT_MODE_ONE)
        assertThat(nextRepeatMode(Player.REPEAT_MODE_ONE)).isEqualTo(Player.REPEAT_MODE_OFF)
    }

    @Test
    fun `unknown repeat mode returns to off`() {
        assertThat(nextRepeatMode(Int.MAX_VALUE)).isEqualTo(Player.REPEAT_MODE_OFF)
    }

    @Test
    fun `queue selection accepts only indices in a non-empty queue`() {
        assertThat(isValidQueueSelection(queueSize = 3, index = 0)).isTrue()
        assertThat(isValidQueueSelection(queueSize = 3, index = 2)).isTrue()
        assertThat(isValidQueueSelection(queueSize = 0, index = 0)).isFalse()
        assertThat(isValidQueueSelection(queueSize = 3, index = -1)).isFalse()
        assertThat(isValidQueueSelection(queueSize = 3, index = 3)).isFalse()
    }

    @Test
    fun `duplicate songs have distinct occurrence keys`() {
        assertThat(queueOccurrenceKey(index = 0, mediaId = "song"))
            .isNotEqualTo(queueOccurrenceKey(index = 2, mediaId = "song"))
    }
}
