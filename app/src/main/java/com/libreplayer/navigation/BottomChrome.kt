package com.libreplayer.navigation

internal enum class BottomChromeMode {
    BOTTOM_NAVIGATION,
    SYSTEM_NAVIGATION_INSET,
    NONE,
}

internal fun resolveBottomChromeMode(
    isTopLevelRoute: Boolean,
    showMiniPlayer: Boolean,
): BottomChromeMode =
    when {
        isTopLevelRoute -> BottomChromeMode.BOTTOM_NAVIGATION
        showMiniPlayer -> BottomChromeMode.SYSTEM_NAVIGATION_INSET
        else -> BottomChromeMode.NONE
    }
