package com.libreplayer.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun secondaryScreenContentPadding(
    scaffoldPadding: PaddingValues,
    extraTop: Dp = 8.dp,
    extraBottom: Dp = 16.dp,
): PaddingValues = PaddingValues(
    top = scaffoldPadding.calculateTopPadding() + extraTop,
    bottom = extraBottom,
)
