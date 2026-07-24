package com.libreplayer.util

import java.util.Locale
import kotlin.math.max

fun formatDuration(durationMs: Long): String {
    val totalSeconds = max(durationMs, 0L) / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatRemainingDuration(currentPositionMs: Long, durationMs: Long): String =
    "-${formatDuration((durationMs - currentPositionMs).coerceAtLeast(0L))}"

