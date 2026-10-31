package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.example.audio.PlaybackState
import kotlin.math.PI
import kotlin.math.sin

private fun createDarkerShade(color: Color): Color {
    if (color == Color.Unspecified || color == Color.Transparent || color.alpha == 0f) {
        return Color.Transparent
    }
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    val desaturation = (1f - (hsl[1] / 0.35f)).coerceIn(0f, 1f)
    hsl[1] = if (hsl[1] > 0.05f) (hsl[1] * 1.08f).coerceIn(0f, 1f) else hsl[1]
    val minLightness = 0.16f + (0.08f * desaturation)
    hsl[2] = (hsl[2] * 0.56f).coerceIn(minLightness, 0.70f)
    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = color.alpha)
}

private fun createBackWaveColor(color: Color): Color {
    if (color == Color.Unspecified || color == Color.Transparent || color.alpha == 0f) {
        return Color.Transparent
    }
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    // Boost minimum lightness and soften darkening when saturation is low (grey/muted covers)
    // so the background wave stays clearly visible against the dark #161822 mini-player pill.
    val desaturation = (1f - (hsl[1] / 0.35f)).coerceIn(0f, 1f)
    hsl[1] = if (hsl[1] > 0.05f) (hsl[1] * 1.08f).coerceIn(0f, 1f) else hsl[1]
    val darkenFactor = 0.62f + (0.12f * desaturation)
    val minLightness = 0.24f + (0.10f * desaturation) // 0.34f floor for greys, 0.24f for vivid colors
    hsl[2] = (hsl[2] * darkenFactor).coerceIn(minLightness, 0.74f)
    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = color.alpha)
}

private fun createLighterShade(color: Color): Color {
    if (color == Color.Unspecified || color == Color.Transparent || color.alpha == 0f) {
        return Color.Transparent
    }
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    val desaturation = (1f - (hsl[1] / 0.35f)).coerceIn(0f, 1f)
    hsl[1] = if (hsl[1] > 0.05f) (hsl[1] * 1.05f).coerceIn(0f, 1f) else hsl[1]
    val boostedLightness = minOf(
        hsl[2] + 0.25f,
        hsl[2] + (1f - hsl[2]) * 0.50f
    )
    val minLighter = 0.32f + (0.14f * desaturation)
    hsl[2] = boostedLightness.coerceIn(minLighter, 0.96f)
    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = color.alpha)
}

@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onLikeToggle: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return

    var totalDrag by remember { mutableFloatStateOf(0f) }
    val backWavePath = remember { Path() }
    val wavePath = remember { Path() }

    // Wave animation controller: 2-second repeat loop (equivalent to Flutter's AnimationController..repeat())
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    val backWavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "back_wave_phase"
    )

    val rawMajorColor = when {
        playbackState.dominantColor != Color.Unspecified && playbackState.dominantColor != Color.Transparent ->
            playbackState.dominantColor
        playbackState.secondaryColor != Color.Unspecified && playbackState.secondaryColor != Color.Transparent ->
            playbackState.secondaryColor
        else -> Color.Transparent
    }

    val targetStartColor = remember(rawMajorColor) { createDarkerShade(rawMajorColor) }
    val targetBackWaveColor = remember(rawMajorColor) { createBackWaveColor(rawMajorColor) }
    val targetEndColor = remember(rawMajorColor) { createLighterShade(rawMajorColor) }

    // Smooth 500ms transition across darker start shade -> major color -> lighter end shade
    val waveStartColor by animateColorAsState(
        targetValue = targetStartColor,
        animationSpec = tween(500),
        label = "waveStartColor"
    )

    val backWaveColor by animateColorAsState(
        targetValue = targetBackWaveColor,
        animationSpec = tween(500),
        label = "backWaveColor"
    )

    val waveMidColor by animateColorAsState(
        targetValue = rawMajorColor,
        animationSpec = tween(500),
        label = "waveMidColor"
    )

    val waveEndColor by animateColorAsState(
        targetValue = targetEndColor,
        animationSpec = tween(500),
        label = "waveEndColor"
    )

    val progressFraction = remember(playbackState.currentPositionMs, playbackState.durationMs) {
        if (playbackState.durationMs > 0) {
            (playbackState.currentPositionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    val cardShape = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .height(62.dp)
            .shadow(
                elevation = 12.dp,
                shape = cardShape,
                spotColor = if (playbackState.dominantColor != Color.Unspecified) playbackState.dominantColor.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.6f),
                ambientColor = Color.Black
            )
            .clip(cardShape)
            .background(Color(0xFF161822).copy(alpha = 0.85f))
            .border(width = 1.dp, color = Color.Black.copy(alpha = 0.14f), shape = cardShape)
            .draggable(
                state = rememberDraggableState { delta ->
                    totalDrag += delta
                },
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    // Swipe to change song
                    if (velocity < -300f || totalDrag < -50f) {
                        onNext()
                    } else if (velocity > 300f || totalDrag > 50f) {
                        onPrevious()
                    }
                    totalDrag = 0f
                }
            )
            .clickable(onClick = onExpand)
    ) {
        // 🔥 LIQUID WAVE FILL ACROSS THE PELLET
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
        ) {
            val width = size.width
            val height = size.height
            val fillWidth = width * progressFraction

            if (fillWidth > 0f && waveMidColor.alpha > 0f) {
                val waveHeight = 6.dp.toPx()
                val backWaveHeight = 4.dp.toPx()
                val waveLength = 72.dp.toPx()
                val backWaveLength = 52.dp.toPx()
                val twoPi = (2 * PI).toFloat()
                val halfPi = (PI / 2.0).toFloat()
                val steps = height.toInt().coerceAtLeast(10)
                val stepHeight = height / steps
                val phaseOffset = wavePhase * twoPi
                val backPhaseOffset = (backWavePhase * twoPi) + halfPi
                val leadRamp = (progressFraction / 0.05f).coerceIn(0f, 1f)
                val backFillWidth = fillWidth + (6.dp.toPx() * leadRamp)

                // Background wave: single darker shade of major color with 90° initial phase offset, faster speed & higher frequency
                backWavePath.reset()
                backWavePath.moveTo(0f, 0f)

                for (i in 0..steps) {
                    val y = stepHeight * i
                    val backDx = (backWaveHeight * sin((y / backWaveLength * twoPi) + backPhaseOffset)).toFloat()
                    backWavePath.lineTo(backFillWidth + backDx, y)
                }

                backWavePath.lineTo(0f, height)
                backWavePath.close()

                drawPath(
                    path = backWavePath,
                    color = backWaveColor.copy(alpha = backWaveColor.alpha * 0.96f)
                )

                // Foreground wave: darker -> major -> lighter horizontal gradient
                wavePath.reset()
                wavePath.moveTo(0f, 0f)

                // Sinusoidal wave across the height of the pill
                for (i in 0..steps) {
                    val y = stepHeight * i
                    val dx = (waveHeight * sin((y / waveLength * twoPi) + phaseOffset)).toFloat()
                    wavePath.lineTo(fillWidth + dx, y)
                }

                wavePath.lineTo(0f, height)
                wavePath.close()

                drawPath(
                    path = wavePath,
                    brush = Brush.horizontalGradient(
                        0.0f to waveStartColor.copy(alpha = waveStartColor.alpha * 0.94f),
                        0.50f to waveMidColor.copy(alpha = waveMidColor.alpha * 0.90f),
                        1.0f to waveEndColor.copy(alpha = waveEndColor.alpha * 0.88f),
                        startX = 0f,
                        endX = (fillWidth + waveHeight).coerceAtLeast(1f)
                    )
                )
            }
        }

        // 🔥 CONTENT INSIDE PELLET
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
        ) {
            // Circular / capsule cover artwork inside pellet
            SongCover(
                imageUrl = song.coverUrl,
                size = 46.dp,
                cornerRadius = 23.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // TITLE + ARTIST
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp
                )
            }

            // LIKE BUTTON
            IconButton(
                onClick = onLikeToggle,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (song.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (song.isLiked) "Unlike" else "Like",
                    tint = if (song.isLiked) Color(0xFFEF4444) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // CIRCULAR PLAY / PAUSE BUTTON
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}


