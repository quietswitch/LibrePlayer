package com.libreplayer.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormattersTest {
    @Test
    fun `formatDuration renders minutes and seconds`() {
        assertThat(formatDuration(185_000L)).isEqualTo("3:05")
    }

    @Test
    fun `formatDuration renders hours when needed`() {
        assertThat(formatDuration(3_726_000L)).isEqualTo("1:02:06")
    }

    @Test
    fun `formatRemainingDuration never goes below zero`() {
        assertThat(formatRemainingDuration(5_000L, 1_000L)).isEqualTo("-0:00")
    }
}

