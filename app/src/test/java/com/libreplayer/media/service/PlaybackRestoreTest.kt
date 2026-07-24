package com.libreplayer.media.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRestoreTest {
    private val savedQueue = listOf("a", "b", "c", "d")

    @Test
    fun `missing item before current restores current by media id`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 2,
            restoredIds = listOf("a", "c", "d"),
            savedPositionMs = 12_345L,
        )

        assertThat(selection.index).isEqualTo(1)
        assertThat(selection.positionMs).isEqualTo(12_345L)
    }

    @Test
    fun `missing current item clamps and resets position`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 2,
            restoredIds = listOf("a", "b", "d"),
            savedPositionMs = 12_345L,
        )

        assertThat(selection.index).isEqualTo(2)
        assertThat(selection.positionMs).isEqualTo(0L)
    }

    @Test
    fun `missing item after current preserves index and position`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 2,
            restoredIds = listOf("a", "b", "c"),
            savedPositionMs = 12_345L,
        )

        assertThat(selection.index).isEqualTo(2)
        assertThat(selection.positionMs).isEqualTo(12_345L)
    }

    @Test
    fun `duplicate media ids restore the saved occurrence`() {
        val selection = restoredQueueSelection(
            queueIds = listOf("a", "b", "a"),
            savedCurrentIndex = 2,
            restoredIds = listOf("a", "b", "a"),
            savedPositionMs = 12_345L,
        )

        assertThat(selection.index).isEqualTo(2)
        assertThat(selection.positionMs).isEqualTo(12_345L)
    }
}
