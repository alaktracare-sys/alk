package com.example.ui.responsive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthSize {
    COMPACT, // < 600.dp (Phones)
    MEDIUM,  // 600.dp .. < 840.dp (Foldables, landscape phones, small tablets)
    EXPANDED // >= 840.dp (Tablets, desktop)
}

enum class WindowHeightSize {
    COMPACT, // < 480.dp (Landscape phones)
    MEDIUM,  // 480.dp .. < 900.dp (Typical phones and tablets)
    EXPANDED // >= 900.dp (Tall screens)
}

data class WindowSizeInfo(
    val widthSize: WindowWidthSize,
    val heightSize: WindowHeightSize,
    val width: Dp,
    val height: Dp,
    val isLandscape: Boolean
) {
    val isCompact: Boolean get() = widthSize == WindowWidthSize.COMPACT
    val isMedium: Boolean get() = widthSize == WindowWidthSize.MEDIUM
    val isExpanded: Boolean get() = widthSize == WindowWidthSize.EXPANDED

    // Adaptive padding recommendations
    val horizontalPadding: Dp get() = when (widthSize) {
        WindowWidthSize.COMPACT -> 16.dp
        WindowWidthSize.MEDIUM -> 24.dp
        WindowWidthSize.EXPANDED -> 32.dp
    }

    // Adaptive content max widths
    val contentMaxWidth: Dp get() = when (widthSize) {
        WindowWidthSize.COMPACT -> Dp.Unspecified
        WindowWidthSize.MEDIUM -> 760.dp
        WindowWidthSize.EXPANDED -> 980.dp
    }
}

val LocalWindowSizeInfo = compositionLocalOf {
    WindowSizeInfo(
        widthSize = WindowWidthSize.COMPACT,
        heightSize = WindowHeightSize.MEDIUM,
        width = 360.dp,
        height = 640.dp,
        isLandscape = false
    )
}

@Composable
fun rememberWindowSizeInfo(): WindowSizeInfo {
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp.dp
    val screenHeight = config.screenHeightDp.dp
    return remember(screenWidth, screenHeight) {
        calculateWindowSizeInfo(screenWidth, screenHeight)
    }
}

fun calculateWindowSizeInfo(width: Dp, height: Dp): WindowSizeInfo {
    val widthSize = when {
        width < 600.dp -> WindowWidthSize.COMPACT
        width < 840.dp -> WindowWidthSize.MEDIUM
        else -> WindowWidthSize.EXPANDED
    }
    val heightSize = when {
        height < 480.dp -> WindowHeightSize.COMPACT
        height < 900.dp -> WindowHeightSize.MEDIUM
        else -> WindowHeightSize.EXPANDED
    }
    return WindowSizeInfo(
        widthSize = widthSize,
        heightSize = heightSize,
        width = width,
        height = height,
        isLandscape = width > height
    )
}

@Composable
fun ResponsiveContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(WindowSizeInfo) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val sizeInfo = remember(maxWidth, maxHeight) {
            calculateWindowSizeInfo(maxWidth, maxHeight)
        }
        CompositionLocalProvider(LocalWindowSizeInfo provides sizeInfo) {
            content(sizeInfo)
        }
    }
}
