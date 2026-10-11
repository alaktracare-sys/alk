package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.ui.theme.AlaktraCard
import com.example.ui.theme.AlaktraMint

@Composable
fun SongCover(
    imageUrl: String,
    size: Dp = 56.dp,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    val baseModifier = if (size != Dp.Unspecified) modifier.size(size) else modifier.aspectRatio(1f)
    val iconFallbackSize = if (size != Dp.Unspecified) size / 2.2f else 28.dp

    Box(
        modifier = baseModifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF222433), AlaktraCard)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = "Song Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E202C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = AlaktraMint.copy(alpha = 0.5f),
                            modifier = Modifier.size(iconFallbackSize)
                        )
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E202C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(iconFallbackSize)
                        )
                    }
                }
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(iconFallbackSize)
            )
        }
    }
}

