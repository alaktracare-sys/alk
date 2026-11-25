package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.AlaktraBackground
import com.example.ui.theme.AlaktraCard
import com.example.ui.theme.AlaktraMint

fun parseDailyAccentColor(hex: String?, fallback: Color = AlaktraMint): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun DailyPlaylistCollageCover(
    coverUrls: List<String>,
    accentColor: Color,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    val cleanCovers = remember(coverUrls) { coverUrls.filter { it.isNotBlank() }.distinct() }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.55f),
                        Color(0xFF1A1C28),
                        AlaktraCard
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            cleanCovers.isEmpty() -> {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(36.dp)
                )
            }
            cleanCovers.size == 1 -> {
                CollageTileImage(url = cleanCovers[0], modifier = Modifier.fillMaxSize())
            }
            else -> {
                val tiles = List(4) { idx -> cleanCovers[idx % cleanCovers.size] }
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.5.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AlaktraBackground.copy(alpha = 0.65f))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        CollageTileImage(
                            url = tiles[0],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        CollageTileImage(
                            url = tiles[1],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        CollageTileImage(
                            url = tiles[2],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        CollageTileImage(
                            url = tiles[3],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageTileImage(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(AlaktraCard),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = AlaktraMint.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
        AsyncImage(
            model = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
