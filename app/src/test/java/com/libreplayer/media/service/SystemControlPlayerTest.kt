package com.libreplayer.media.service

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class SystemControlPlayerTest {
    private val player = mockk<Player>(relaxed = true)
    private val systemPlayer = SystemControlPlayer(player)

    @Test
    fun `system previous below five seconds selects previous item`() {
        every { player.currentPosition } returns 4_000L
        every { player.hasPreviousMediaItem() } returns true

        systemPlayer.seekToPrevious()

        verify(exactly = 1) { player.seekToPreviousMediaItem() }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `system previous above five seconds restarts current item`() {
        every { player.currentPosition } returns 6_000L
        every { player.hasPreviousMediaItem() } returns true

        systemPlayer.seekToPrevious()

        verify(exactly = 1) { player.seekTo(0L) }
        verify(exactly = 0) { player.seekToPreviousMediaItem() }
    }

    @Test
    fun `system previous at five seconds restarts current item`() {
        every { player.currentPosition } returns 5_000L
        every { player.hasPreviousMediaItem() } returns true

        systemPlayer.seekToPrevious()

        verify(exactly = 1) { player.seekTo(0L) }
        verify(exactly = 0) { player.seekToPreviousMediaItem() }
    }

    @Test
    fun `system previous at first item start is a no-op`() {
        every { player.currentPosition } returns 0L
        every { player.hasPreviousMediaItem() } returns false

        systemPlayer.seekToPrevious()

        verify(exactly = 0) { player.seekTo(any<Long>()) }
        verify(exactly = 0) { player.seekToPreviousMediaItem() }
    }
}
