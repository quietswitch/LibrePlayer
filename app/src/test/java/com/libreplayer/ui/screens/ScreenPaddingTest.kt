package com.libreplayer.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenPaddingTest {
    @Test
    fun `secondary screen padding keeps top scaffold inset and uses screen bottom spacing`() {
        val padding = secondaryScreenContentPadding(
            scaffoldPadding = PaddingValues(top = 12.dp, bottom = 56.dp),
        )

        assertThat(padding.calculateTopPadding()).isEqualTo(20.dp)
        assertThat(padding.calculateBottomPadding()).isEqualTo(16.dp)
    }

    @Test
    fun `secondary screen padding supports screen specific bottom spacing`() {
        val padding = secondaryScreenContentPadding(
            scaffoldPadding = PaddingValues(top = 4.dp, bottom = 56.dp),
            extraBottom = 24.dp,
        )

        assertThat(padding.calculateTopPadding()).isEqualTo(12.dp)
        assertThat(padding.calculateBottomPadding()).isEqualTo(24.dp)
    }
}
