package com.libreplayer.media.playback

import androidx.media3.common.Player
import kotlin.random.Random

object QueueAlgorithms {
    fun shuffledIndices(size: Int, seed: Long): List<Int> {
        val indices = (0 until size).toMutableList()
        if (indices.size <= 1) return indices
        val random = Random(seed)
        for (i in indices.lastIndex downTo 1) {
            val swapIndex = random.nextInt(i + 1)
            val temp = indices[i]
            indices[i] = indices[swapIndex]
            indices[swapIndex] = temp
        }
        return indices
    }

    fun nextIndex(
        size: Int,
        currentIndex: Int,
        repeatMode: Int,
    ): Int? {
        if (size <= 0 || currentIndex !in 0 until size) return null
        return when {
            repeatMode == Player.REPEAT_MODE_ONE -> currentIndex
            currentIndex < size - 1 -> currentIndex + 1
            repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> null
        }
    }

    fun previousIndex(
        size: Int,
        currentIndex: Int,
        repeatMode: Int,
    ): Int? {
        if (size <= 0 || currentIndex !in 0 until size) return null
        return when {
            repeatMode == Player.REPEAT_MODE_ONE -> currentIndex
            currentIndex > 0 -> currentIndex - 1
            repeatMode == Player.REPEAT_MODE_ALL -> size - 1
            else -> null
        }
    }
}

