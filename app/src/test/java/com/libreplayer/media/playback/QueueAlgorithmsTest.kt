package com.libreplayer.media.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueAlgorithmsTest {
    @Test
    fun `nextIndex wraps only in repeat all`() {
        assertThat(QueueAlgorithms.nextIndex(3, 2, Player.REPEAT_MODE_OFF)).isNull()
        assertThat(QueueAlgorithms.nextIndex(3, 2, Player.REPEAT_MODE_ALL)).isEqualTo(0)
        assertThat(QueueAlgorithms.nextIndex(3, 2, Player.REPEAT_MODE_ONE)).isEqualTo(2)
    }

    @Test
    fun `previousIndex wraps only in repeat all`() {
        assertThat(QueueAlgorithms.previousIndex(3, 0, Player.REPEAT_MODE_OFF)).isNull()
        assertThat(QueueAlgorithms.previousIndex(3, 0, Player.REPEAT_MODE_ALL)).isEqualTo(2)
        assertThat(QueueAlgorithms.previousIndex(3, 1, Player.REPEAT_MODE_ONE)).isEqualTo(1)
    }

    @Test
    fun `shuffledIndices is deterministic for same seed`() {
        val first = QueueAlgorithms.shuffledIndices(size = 5, seed = 42L)
        val second = QueueAlgorithms.shuffledIndices(size = 5, seed = 42L)

        assertThat(first).isEqualTo(second)
        assertThat(first).containsExactly(0, 1, 2, 3, 4)
    }
}

