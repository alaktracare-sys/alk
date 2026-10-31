package com.example.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

enum class ShapeMode {
    MIX,
    HOLLOW,
    SOLID
}

/**
 * Draws a pre-composited randomized scatter of hollow and solid shapes behind the player's
 * color gradient and foreground controls.
 *
 * All parameters are primitive or `@Immutable` so Compose completely skips recomposing and
 * re-recording this Canvas during playback seekbar progress ticks, and GPU rendering is a
 * single bilinear texture blit instead of dozens of rotated bicubic draws.
 */
@Composable
fun RandomShapesBackground(
    seedKey: String,
    palette: ShapeColorPalette,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    mode: ShapeMode = ShapeMode.MIX,
    minCount: Int = 24,
    maxCount: Int = 44,
    minSizeFraction: Float = 0.09f,
    maxSizeFraction: Float = 0.25f,
    minAlpha: Float = 0.52f,
    maxAlpha: Float = 0.88f
) {
    val context = LocalContext.current
    val atlasVersion = ShapeAtlas.atlasVersion
    if (atlasVersion == 0) {
        SideEffect {
            ShapeAtlas.preloadAsync(context)
        }
    }

    val wallpaper = remember(
        seedKey,
        palette,
        isLandscape,
        mode,
        minCount,
        maxCount,
        minSizeFraction,
        maxSizeFraction,
        minAlpha,
        maxAlpha,
        atlasVersion
    ) {
        if (atlasVersion == 0) {
            null
        } else {
            ShapeAtlas.getOrRenderWallpaper(
                seedKey = seedKey,
                palette = palette,
                isLandscape = isLandscape,
                mode = mode,
                minCount = minCount,
                maxCount = maxCount,
                minSizeFraction = minSizeFraction,
                maxSizeFraction = maxSizeFraction,
                minAlpha = minAlpha,
                maxAlpha = maxAlpha
            )
        }
    }

    Canvas(
        modifier = modifier.testTag("player_random_shapes_background")
    ) {
        val bmp = wallpaper ?: return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val bmpW = bmp.width
        val bmpH = bmp.height
        val scale = maxOf(size.width / bmpW.toFloat(), size.height / bmpH.toFloat())
        val dstW = (bmpW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (bmpH * scale).roundToInt().coerceAtLeast(1)
        val dstX = ((size.width - dstW) * 0.5f).roundToInt()
        val dstY = ((size.height - dstH) * 0.5f).roundToInt()

        clipRect {
            drawImage(
                image = bmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmpW, bmpH),
                dstOffset = IntOffset(dstX, dstY),
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.Low
            )
        }
    }
}

@Composable
fun RandomShapesBackground(
    seedKey: String,
    modifier: Modifier = Modifier,
    mode: ShapeMode = ShapeMode.MIX,
    countRange: IntRange = 24..44,
    sizeFractionRange: ClosedFloatingPointRange<Float> = 0.09f..0.25f,
    alphaRange: ClosedFloatingPointRange<Float> = 0.50f..0.88f,
    colors: List<Color> = listOf(
        Color(0xFFFF9A8B),
        Color(0xFFFFD27F),
        Color(0xFF9FD8FF),
        Color(0xFFC3A6FF),
        Color.White
    )
) {
    val palette = remember(colors) {
        val safe = if (colors.isNotEmpty()) colors else listOf(Color.White)
        ShapeColorPalette(IntArray(safe.size) { idx -> safe[idx].toArgb() })
    }
    RandomShapesBackground(
        seedKey = seedKey,
        palette = palette,
        modifier = modifier,
        mode = mode,
        minCount = countRange.first,
        maxCount = countRange.last,
        minSizeFraction = sizeFractionRange.start,
        maxSizeFraction = sizeFractionRange.endInclusive,
        minAlpha = alphaRange.start,
        maxAlpha = alphaRange.endInclusive
    )
}
