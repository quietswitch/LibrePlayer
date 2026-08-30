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

    @Test
    fun `no surviving items restores an empty selection`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 2,
            restoredIds = emptyList(),
            savedPositionMs = 12_345L,
        )

        assertThat(selection.index).isEqualTo(-1)
        assertThat(selection.positionMs).isEqualTo(0L)
    }

    @Test
    fun `restored position is never negative`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 1,
            restoredIds = savedQueue,
            savedPositionMs = -1L,
        )

        assertThat(selection.index).isEqualTo(1)
        assertThat(selection.positionMs).isEqualTo(0L)
    }

    @Test
    fun `restored position is bounded by known duration`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 1,
            restoredIds = savedQueue,
            restoredDurationsMs = listOf(60_000L, 90_000L, 60_000L, 60_000L),
            savedPositionMs = 120_000L,
        )

        assertThat(selection.index).isEqualTo(1)
        assertThat(selection.positionMs).isEqualTo(90_000L)
    }

    @Test
    fun `restored position just before known duration is preserved`() {
        val selection = restoredQueueSelection(
            queueIds = savedQueue,
            savedCurrentIndex = 1,
            restoredIds = savedQueue,
            restoredDurationsMs = listOf(60_000L, 90_000L, 60_000L, 60_000L),
            savedPositionMs = 89_999L,
        )

        assertThat(selection.index).isEqualTo(1)
        assertThat(selection.positionMs).isEqualTo(89_999L)
    }

    @Test
    fun `duplicate occurrence uses its own duration when restoring position`() {
        val selection = restoredQueueSelection(
            queueIds = listOf("a", "b", "a"),
            savedCurrentIndex = 2,
            restoredIds = listOf("a", "b", "a"),
            restoredDurationsMs = listOf(10_000L, 20_000L, 30_000L),
            savedPositionMs = 25_000L,
        )

        assertThat(selection.index).isEqualTo(2)
        assertThat(selection.positionMs).isEqualTo(25_000L)
    }
}
