package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AlaktraBorder
import com.example.ui.theme.AlaktraSurface

private val ShimmerColors = listOf(
    Color(0xFF1E202B),
    Color(0xFF2D3143),
    Color(0xFF1E202B)
)

/**
 * Allocates a single [InfiniteTransition] brush to be shared across all skeleton placeholders
 * inside a screen or section, keeping animation CPU/recomposition overhead minimal.
 */
@Composable
fun rememberAlaktraShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "alaktra_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alaktra_shimmer_offset"
    )
    return Brush.linearGradient(
        colors = ShimmerColors,
        start = Offset(x = translateAnim - 500f, y = translateAnim - 500f),
        end = Offset(x = translateAnim, y = translateAnim)
    )
}

@Composable
fun AlaktraShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    shimmerBrush: Brush? = null,
    shape: Shape = RoundedCornerShape(cornerRadius)
) {
    val brush = shimmerBrush ?: rememberAlaktraShimmerBrush()
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

@Composable
fun SongRowSkeleton(
    modifier: Modifier = Modifier,
    shimmerBrush: Brush = rememberAlaktraShimmerBrush()
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        AlaktraShimmerBox(
            modifier = Modifier.size(48.dp),
            cornerRadius = 8.dp,
            shimmerBrush = shimmerBrush
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .height(14.dp),
                cornerRadius = 6.dp,
                shimmerBrush = shimmerBrush
            )
            Spacer(modifier = Modifier.height(6.dp))
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(11.dp),
                cornerRadius = 5.dp,
                shimmerBrush = shimmerBrush
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        AlaktraShimmerBox(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            shimmerBrush = shimmerBrush
        )
    }
}

@Composable
fun SongListSkeleton(
    count: Int = 6,
    horizontalPadding: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val brush = rememberAlaktraShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
    ) {
        repeat(count) {
            SongRowSkeleton(shimmerBrush = brush)
        }
    }
}

@Composable
fun LibraryRowSkeleton(
    modifier: Modifier = Modifier,
    shimmerBrush: Brush = rememberAlaktraShimmerBrush()
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        AlaktraShimmerBox(
            modifier = Modifier.size(56.dp),
            cornerRadius = 10.dp,
            shimmerBrush = shimmerBrush
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(15.dp),
                cornerRadius = 6.dp,
                shimmerBrush = shimmerBrush
            )
            Spacer(modifier = Modifier.height(7.dp))
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.32f)
                    .height(11.dp),
                cornerRadius = 5.dp,
                shimmerBrush = shimmerBrush
            )
        }
    }
}

@Composable
fun LibraryListSkeleton(
    count: Int = 4,
    horizontalPadding: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val brush = rememberAlaktraShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
    ) {
        repeat(count) {
            LibraryRowSkeleton(shimmerBrush = brush)
        }
    }
}

@Composable
fun ArtistRowSkeleton(
    modifier: Modifier = Modifier,
    shimmerBrush: Brush = rememberAlaktraShimmerBrush()
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        AlaktraShimmerBox(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            shimmerBrush = shimmerBrush
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(15.dp),
                cornerRadius = 6.dp,
                shimmerBrush = shimmerBrush
            )
            Spacer(modifier = Modifier.height(6.dp))
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(11.dp),
                cornerRadius = 5.dp,
                shimmerBrush = shimmerBrush
            )
        }
    }
}

@Composable
fun ArtistListSkeleton(
    count: Int = 5,
    horizontalPadding: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val brush = rememberAlaktraShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
    ) {
        repeat(count) {
            ArtistRowSkeleton(shimmerBrush = brush)
        }
    }
}

@Composable
fun HomeLoadingSkeleton(
    horizontalPad: Dp,
    modifier: Modifier = Modifier
) {
    val brush = rememberAlaktraShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPad)
    ) {
        // Shortcuts row (Liked Songs / Downloaded)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                cornerRadius = 10.dp,
                shimmerBrush = brush
            )
            AlaktraShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                cornerRadius = 10.dp,
                shimmerBrush = brush
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Quick access tiles
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AlaktraShimmerBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    cornerRadius = 10.dp,
                    shimmerBrush = brush
                )
                AlaktraShimmerBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    cornerRadius = 10.dp,
                    shimmerBrush = brush
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Section title
        AlaktraShimmerBox(
            modifier = Modifier
                .width(136.dp)
                .height(20.dp),
            cornerRadius = 6.dp,
            shimmerBrush = brush
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Daily mixes carousel row
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Column(modifier = Modifier.weight(1f)) {
                    AlaktraShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        cornerRadius = 12.dp,
                        shimmerBrush = brush
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AlaktraShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(12.dp),
                        cornerRadius = 5.dp,
                        shimmerBrush = brush
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        AlaktraShimmerBox(
            modifier = Modifier
                .width(110.dp)
                .height(20.dp),
            cornerRadius = 6.dp,
            shimmerBrush = brush
        )
        Spacer(modifier = Modifier.height(10.dp))
        repeat(5) {
            SongRowSkeleton(shimmerBrush = brush)
        }
    }
}

@Composable
fun ArtistProfileSkeleton(modifier: Modifier = Modifier) {
    val brush = rememberAlaktraShimmerBrush()
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                cornerRadius = 0.dp,
                shimmerBrush = brush
            )
            AlaktraShimmerBox(
                modifier = Modifier
                    .padding(start = 16.dp, top = 160.dp)
                    .size(88.dp)
                    .border(2.dp, AlaktraSurface, CircleShape),
                shape = CircleShape,
                shimmerBrush = brush
            )
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(24.dp),
                cornerRadius = 8.dp,
                shimmerBrush = brush
            )
            Spacer(modifier = Modifier.height(10.dp))
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(13.dp),
                cornerRadius = 6.dp,
                shimmerBrush = brush
            )
            Spacer(modifier = Modifier.height(6.dp))
            AlaktraShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(13.dp),
                cornerRadius = 6.dp,
                shimmerBrush = brush
            )
            Spacer(modifier = Modifier.height(22.dp))
            repeat(5) {
                SongRowSkeleton(shimmerBrush = brush)
            }
        }
    }
}

@Composable
fun ArtistStudioSkeleton(modifier: Modifier = Modifier) {
    val brush = rememberAlaktraShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AlaktraShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp),
            cornerRadius = 16.dp,
            shimmerBrush = brush
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AlaktraShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(78.dp),
                cornerRadius = 14.dp,
                shimmerBrush = brush
            )
            AlaktraShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(78.dp),
                cornerRadius = 14.dp,
                shimmerBrush = brush
            )
        }
        AlaktraShimmerBox(
            modifier = Modifier
                .width(140.dp)
                .height(18.dp),
            cornerRadius = 6.dp,
            shimmerBrush = brush
        )
        repeat(4) {
            SongRowSkeleton(shimmerBrush = brush)
        }
    }
}

@Composable
fun PinsListSkeleton(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    val brush = rememberAlaktraShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AlaktraSurface)
                    .border(1.dp, AlaktraBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                AlaktraShimmerBox(
                    modifier = Modifier.size(56.dp),
                    cornerRadius = 10.dp,
                    shimmerBrush = shimmerBrushOrNull(brush)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    AlaktraShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(15.dp),
                        cornerRadius = 6.dp,
                        shimmerBrush = brush
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    AlaktraShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(12.dp),
                        cornerRadius = 5.dp,
                        shimmerBrush = brush
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    AlaktraShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(11.dp),
                        cornerRadius = 5.dp,
                        shimmerBrush = brush
                    )
                }
            }
        }
    }
}

private fun shimmerBrushOrNull(brush: Brush): Brush = brush
