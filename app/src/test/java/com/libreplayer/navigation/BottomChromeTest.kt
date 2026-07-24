package com.libreplayer.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BottomChromeTest {
    @Test
    fun `top level routes keep bottom navigation chrome`() {
        assertThat(
            resolveBottomChromeMode(
                isTopLevelRoute = true,
                showMiniPlayer = true,
            ),
        ).isEqualTo(BottomChromeMode.BOTTOM_NAVIGATION)
    }

    @Test
    fun `detail routes with mini player reserve only system navigation inset`() {
        assertThat(
            resolveBottomChromeMode(
                isTopLevelRoute = false,
                showMiniPlayer = true,
            ),
        ).isEqualTo(BottomChromeMode.SYSTEM_NAVIGATION_INSET)
    }

    @Test
    fun `detail routes without mini player use no bottom chrome`() {
        assertThat(
            resolveBottomChromeMode(
                isTopLevelRoute = false,
                showMiniPlayer = false,
            ),
        ).isEqualTo(BottomChromeMode.NONE)
    }
}
