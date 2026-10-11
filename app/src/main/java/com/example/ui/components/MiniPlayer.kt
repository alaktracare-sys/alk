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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.PlaybackState
import com.example.ui.theme.AlaktraCyan
import com.example.ui.theme.AlaktraMint
import kotlin.math.PI
import kotlin.math.sin

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

    // Dynamic palette colors with 500ms smooth transition (matching Flutter AnimatedContainer)
    val color1 by animateColorAsState(
        targetValue = if (playbackState.dominantColor != Color.Unspecified) {
            playbackState.dominantColor
        } else {
            AlaktraMint
        },
        animationSpec = tween(500),
        label = "color1"
    )

    val color2 by animateColorAsState(
        targetValue = if (playbackState.secondaryColor != Color.Unspecified) {
            playbackState.secondaryColor
        } else {
            AlaktraCyan
        },
        animationSpec = tween(500),
        label = "color2"
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

            if (fillWidth > 0f) {
                val waveHeight = 6.dp.toPx()
                val waveLength = 72.dp.toPx()

                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(fillWidth, 0f)

                    // Sinusoidal wave across the height of the pill
                    val steps = height.toInt().coerceAtLeast(10)
                    for (i in 0..steps) {
                        val y = (height / steps) * i
                        val dx = (waveHeight * sin((y / waveLength * 2 * PI) + (wavePhase * 2 * PI))).toFloat()
                        lineTo(fillWidth + dx, y)
                    }

                    lineTo(0f, height)
                    close()
                }

                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            color1.copy(alpha = 0.90f),
                            color2.copy(alpha = 0.85f)
                        ),
                        startX = 0f,
                        endX = fillWidth.coerceAtLeast(1f)
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


