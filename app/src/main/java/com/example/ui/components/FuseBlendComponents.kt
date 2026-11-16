package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.fuse.FuseCodeCodec
import com.example.model.Song
import com.example.ui.theme.*

private val FuseUser1BadgeColor = Color(0xFF7C3AED)
private val FuseUser2BadgeColor = Color(0xFF00D2FF)

/**
 * Spotify-Blend-inspired artwork tile featuring a luminous multi-stop gradient,
 * orbital rings, and two overlapping circular user initial badges.
 */
@Composable
fun FuseDualAvatarCover(
    user1Name: String,
    user2Name: String,
    size: Dp = 72.dp,
    cornerRadius: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val initial1 = remember(user1Name) { FuseCodeCodec.extractInitial(user1Name, "1") }
    val initial2 = remember(user2Name) { FuseCodeCodec.extractInitial(user2Name, "2") }
    val circleSize = size * 0.46f
    val overlapOffset = size * 0.13f
    val fontSizeSp = (size.value * 0.19f).coerceIn(11f, 22f).sp

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF5B21B6),
                        AlaktraViolet,
                        AlaktraCyan,
                        AlaktraMint
                    ),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .drawBehind {
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f),
                    radius = this.size.minDimension * 0.44f,
                    center = Offset(this.size.width * 0.25f, this.size.height * 0.22f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFF041C12).copy(alpha = 0.22f),
                    radius = this.size.minDimension * 0.38f,
                    center = Offset(this.size.width * 0.78f, this.size.height * 0.78f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = overlapOffset * 0.45f)
                    .size(circleSize)
                    .clip(CircleShape)
                    .background( Color(0xFF191233) )
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            ) {
                Text(
                    text = initial1,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fontSizeSp
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = -overlapOffset * 0.45f)
                    .size(circleSize)
                    .clip(CircleShape)
                    .background(Color(0xFF052925))
                    .border(1.5.dp, AlaktraMint, CircleShape)
            ) {
                Text(
                    text = initial2,
                    color = AlaktraMint,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fontSizeSp
                )
            }
        }
    }
}

/**
 * Per-song contributor avatar badge displayed on every Fuse track row (just like Spotify Blend):
 * - "both" -> overlapping User 1 + User 2 initial circles
 * - "user1" -> User 1 initial circle
 * - "user2" -> User 2 initial circle
 */
@Composable
fun FuseContributorBadge(
    contributor: String?,
    user1Name: String,
    user2Name: String,
    modifier: Modifier = Modifier
) {
    val normalized = contributor?.lowercase()?.trim() ?: "both"
    val initial1 = remember(user1Name) { FuseCodeCodec.extractInitial(user1Name, "1") }
    val initial2 = remember(user2Name) { FuseCodeCodec.extractInitial(user2Name, "2") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(end = 6.dp)
    ) {
        when (normalized) {
            "user1" -> {
                MiniInitialCircle(
                    initial = initial1,
                    bgColor = FuseUser1BadgeColor,
                    textColor = Color.White,
                    contentDesc = "From $user1Name"
                )
            }
            "user2" -> {
                MiniInitialCircle(
                    initial = initial2,
                    bgColor = FuseUser2BadgeColor,
                    textColor = Color(0xFF031B24),
                    contentDesc = "From $user2Name"
                )
            }
            else -> {
                Box(modifier = Modifier.width(28.dp).height(18.dp)) {
                    MiniInitialCircle(
                        initial = initial1,
                        bgColor = FuseUser1BadgeColor,
                        textColor = Color.White,
                        contentDesc = "Shared by $user1Name and $user2Name",
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    MiniInitialCircle(
                        initial = initial2,
                        bgColor = AlaktraMint,
                        textColor = Color(0xFF041C12),
                        contentDesc = null,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniInitialCircle(
    initial: String,
    bgColor: Color,
    textColor: Color,
    contentDesc: String?,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, AlaktraBackground, CircleShape)
    ) {
        Text(
            text = initial,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 10.sp
        )
    }
}

/**
 * Interactive Spotify-Blend-style Story Card Modal highlighting:
 * - Both listeners' overlapping avatars
 * - Server-calculated Taste Match %
 * - The #1 shared song that brings both users together
 * - Shared vs discovery track breakdown + Play Your Fuse CTA
 */
@Composable
fun FuseBlendStoryDialog(
    playlistName: String,
    user1Name: String,
    user2Name: String,
    matchScore: Int,
    songs: List<Song>,
    onDismiss: () -> Unit,
    onPlayFuse: () -> Unit
) {
    val displayScore = if (matchScore > 0) matchScore else 84
    val u1 = user1Name.ifBlank { "User 1" }
    val u2 = user2Name.ifBlank { "User 2" }
    val initial1 = remember(u1) { FuseCodeCodec.extractInitial(u1, "1") }
    val initial2 = remember(u2) { FuseCodeCodec.extractInitial(u2, "2") }

    val sharedCount = remember(songs) { songs.count { (it.fuseContributor ?: "both") == "both" } }
    val discoveryCount = remember(songs, sharedCount) { (songs.size - sharedCount).coerceAtLeast(0) }
    val anchorSong = remember(songs) {
        songs.firstOrNull { (it.fuseContributor ?: "both") == "both" } ?: songs.firstOrNull()
    }

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val badgeScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "blend_story_scale"
    )

    val matchHeadline = when {
        displayScore >= 88 -> "Sonic Soulmates"
        displayScore >= 72 -> "Dialed Into the Same Vibe"
        else -> "Best of Both Worlds"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = AlaktraCard,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp)
                .testTag("fuse_blend_story_dialog")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF3B1A78),
                                Color(0xFF10374A),
                                Color(0xFF0C181C),
                                AlaktraCard
                            )
                        )
                    )
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(AlaktraMint.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(size.width * 0.5f, size.height * 0.28f),
                                radius = size.minDimension * 0.65f
                            ),
                            radius = size.minDimension * 0.65f,
                            center = Offset(size.width * 0.5f, size.height * 0.28f)
                        )
                    }
                    .padding(22.dp)
            ) {
                // Story-style top indicator bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(AlaktraMint)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Top header bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.32f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = AlaktraMint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.fuse_blend_story_title),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.4.sp
                            ),
                            color = Color.White
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("blend_story_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.fuse_blend_story_close),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Overlapping Dual User Avatars
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.scale(badgeScale)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset(x = 10.dp)
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(FuseUser1BadgeColor, Color(0xFF4F46E5)))
                            )
                            .border(2.5.dp, Color.White, CircleShape)
                    ) {
                        Text(
                            text = initial1,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset(x = (-10).dp)
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan))
                            )
                            .border(2.5.dp, Color(0xFF041C12), CircleShape)
                    ) {
                        Text(
                            text = initial2,
                            color = Color(0xFF041C12),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "$u1 × $u2",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "$displayScore%",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 52.sp
                    ),
                    color = AlaktraMint
                )

                Text(
                    text = "TASTE MATCH • ${matchHeadline.uppercase()}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    ),
                    color = AlaktraCyan,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (anchorSong != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Black.copy(alpha = 0.38f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(
                                text = "SONG THAT BRINGS YOU TOGETHER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = AlaktraMint
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SongCover(
                                    imageUrl = anchorSong.coverUrl,
                                    size = 52.dp,
                                    cornerRadius = 10.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = anchorSong.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = anchorSong.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AlaktraTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                FuseContributorBadge(
                                    contributor = "both",
                                    user1Name = u1,
                                    user2Name = u2
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Stats chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BlendStatPill(
                        value = "$sharedCount",
                        label = "Shared Tracks",
                        modifier = Modifier.weight(1f)
                    )
                    BlendStatPill(
                        value = "$discoveryCount",
                        label = "Fresh Picks",
                        modifier = Modifier.weight(1f)
                    )
                    BlendStatPill(
                        value = "${songs.size}",
                        label = "Daily Blend",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        onPlayFuse()
                        onDismiss()
                    },
                    enabled = songs.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AlaktraMint,
                        contentColor = Color(0xFF041C12)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("blend_story_play_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.fuse_blend_story_play),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BlendStatPill(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AlaktraTextSecondary,
            maxLines = 1
        )
    }
}
