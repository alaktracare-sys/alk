package com.example.ui.screens.player

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.audio.AudioController
import com.example.audio.PlaybackState
import com.example.audio.RepeatMode
import com.example.model.Song
import com.example.ui.components.SongCover
import kotlinx.coroutines.launch

// Existing Spotify Theme Tokens
private val SpotifyBlack = Color(0xFF121212)
private val SpotifySurface = Color(0xFF181818)
private val SpotifyElevated = Color(0xFF282828)
private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifyRed = Color(0xFFE91429)
private val SpotifyTextPrimary = Color(0xFFFFFFFF)
private val SpotifyTextSecondary = Color(0xFFB3B3B3)
private val SpotifyTextSubtle = Color(0xFF727272)
private val SpotifyDivider = Color(0xFF282828)

/**
 * Spotify-accurate layered Queue Panel.
 *
 * Flattens the three distinct playback layers at render time:
 * 1. "Now playing" - Pinned, accent-tinted, animated equalizer bars, not draggable/removable.
 * 2. "Next in queue" - Rendered ONLY when userQueue.length > 0. Header has "Clear queue" scoped to this section alone.
 *    Rows are draggable to reorder and removable.
 * 3. "Next from: {context.name}" - Rows come from contextOrder.slice(contextIndex + 1) (POST-SHUFFLE order).
 *    Read-only context layer: dragging a row upwards promotes it into userQueue (does NOT mutate contextOrder).
 * 4. "Autoplay" - Dimmed rows when autoplayTracks is non-empty.
 *
 * Includes a collapsible live Layer Debug Strip at the bottom.
 */
@Composable
fun QueuePanel(
    playbackState: PlaybackState,
    audioController: AudioController,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onNavigateToSource: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isPromotingDragActive by remember { mutableStateOf(false) }
    var promotedTrackTitle by remember { mutableStateOf<String?>(null) }
    var isDebugExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(playbackState.repeat, playbackState.shuffle, playbackState.contextIndex) {
        if (playbackState.repeat == RepeatMode.CONTEXT) {
            audioController.ensureBuffer()
        }
    }

    // Rows from contextOrder starting strictly after contextIndex, showing up to the limit of 30 songs (excluding user-added songs)
    val upcomingContextTracks = remember(
        playbackState.contextOrder,
        playbackState.contextIndex,
        playbackState.repeat,
        playbackState.shuffle
    ) {
        val start = playbackState.contextIndex + 1
        if (start in 0 until playbackState.contextOrder.size) {
            val end = minOf(start + 30, playbackState.contextOrder.size)
            playbackState.contextOrder.subList(start, end)
        } else {
            emptyList()
        }
    }

    val contextName = playbackState.context?.name ?: playbackState.playbackSource

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SpotifyBlack)
    ) {
        // Header with Title & Quick Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close Queue",
                        tint = SpotifyTextPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }

            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = SpotifyTextPrimary
            )

            // Shuffle & Repeat Quick Actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        audioController.toggleShuffle()
                        val msg = if (!playbackState.shuffle) "Shuffle enabled" else "Shuffle disabled"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle Queue",
                        tint = if (playbackState.shuffle) SpotifyGreen else SpotifyTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val nextMode = audioController.cycleRepeatMode()
                        val msg = when (nextMode) {
                            RepeatMode.OFF -> "Repeat off"
                            RepeatMode.CONTEXT -> "Repeating context"
                            RepeatMode.TRACK -> "Repeating track"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    val isRepeatActive = playbackState.repeat != RepeatMode.OFF
                    val repeatIcon = if (playbackState.repeat == RepeatMode.TRACK) {
                        Icons.Default.RepeatOne
                    } else {
                        Icons.Default.Repeat
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = "Repeat Mode",
                            tint = if (isRepeatActive) SpotifyGreen else SpotifyTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        if (isRepeatActive) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(SpotifyGreen)
                            )
                        }
                    }
                }
            }
        }

        // Drop indicator line during drag-to-promote interaction
        AnimatedVisibility(
            visible = isPromotingDragActive,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SpotifyGreen.copy(alpha = 0.2f))
                    .border(1.5.dp, SpotifyGreen, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Release to promote \"${promotedTrackTitle ?: "track"}\" to User Queue",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        color = SpotifyGreen
                    )
                }
            }
        }

        // Main flattened projection list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // ==========================================
            // 1. NOW PLAYING (Pinned, Accent-tinted, Equalizer)
            // ==========================================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = SpotifyTextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                )
            }

            item {
                playbackState.currentSong?.let { song ->
                    NowPlayingRow(
                        song = song,
                        isPlaying = playbackState.isPlaying,
                        repeatMode = playbackState.repeat
                    )
                } ?: run {
                    Text(
                        text = "No track currently playing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpotifyTextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            // ==========================================
            // 2. NEXT IN QUEUE (Rendered ONLY when userQueue > 0)
            // ==========================================
            if (playbackState.userQueue.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Next in queue",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = SpotifyTextPrimary
                        )

                        TextButton(
                            onClick = {
                                audioController.clearUserQueue()
                                Toast.makeText(context, "User queue cleared", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Clear queue",
                                color = SpotifyTextSecondary,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = playbackState.userQueue,
                    key = { idx, s -> "user_q_${s.id}_$idx" }
                ) { index, song ->
                    QueueTrackRow(
                        song = song,
                        isUserQueue = true,
                        onPlayNow = {
                            audioController.playUserQueueItem(index)
                        },
                        onRemove = {
                            audioController.removeFromUserQueue(index)
                            Toast.makeText(context, "Removed from queue", Toast.LENGTH_SHORT).show()
                        },
                        onPromote = {
                            audioController.promoteToUserQueue(song)
                            Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                        },
                        onReorder = { from, to ->
                            audioController.reorderUserQueue(from, to)
                        },
                        totalUserQueueCount = playbackState.userQueue.size,
                        currentIndexInUserQueue = index
                    )
                }
            }

            // ==========================================
            // 3. NEXT FROM: {context.name} (POST-SHUFFLE ORDER)
            // ==========================================
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable(enabled = onNavigateToSource != null) {
                                onNavigateToSource?.invoke()
                            }
                    ) {
                        Text(
                            text = "Next from: $contextName",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = if (onNavigateToSource != null) SpotifyGreen else SpotifyTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onNavigateToSource != null) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Go to source",
                                tint = SpotifyGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (playbackState.repeat == RepeatMode.CONTEXT) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (playbackState.shuffle) Icons.Default.Shuffle else Icons.Default.Repeat,
                                contentDescription = null,
                                tint = SpotifyGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (playbackState.shuffle) "Infinite Shuffle" else "Repeat Context",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = SpotifyGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (upcomingContextTracks.isEmpty()) {
                item {
                    Text(
                        text = if (playbackState.repeat == RepeatMode.TRACK) {
                            "Currently looping this track"
                        } else {
                            "End of context reached"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpotifyTextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                itemsIndexed(
                    items = upcomingContextTracks,
                    key = { idx, s -> "ctx_q_${s.id}_${playbackState.contextIndex + 1 + idx}" }
                ) { relIndex, song ->
                    val actualContextIndex = playbackState.contextIndex + 1 + relIndex
                    QueueTrackRow(
                        song = song,
                        isUserQueue = false,
                        onPlayNow = {
                            audioController.playContextQueueItem(actualContextIndex)
                        },
                        onRemove = {
                            audioController.removeFromQueue(actualContextIndex)
                            Toast.makeText(context, "Removed from queue", Toast.LENGTH_SHORT).show()
                        },
                        onPromote = {
                            audioController.promoteToUserQueue(song)
                            Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                        },
                        onDragPromotionStart = {
                            isPromotingDragActive = true
                            promotedTrackTitle = song.title
                        },
                        onDragPromotionCommit = {
                            isPromotingDragActive = false
                            audioController.promoteToUserQueue(song)
                            Toast.makeText(context, "Promoted \"${song.title}\" to User Queue", Toast.LENGTH_SHORT).show()
                            promotedTrackTitle = null
                        },
                        onDragPromotionCancel = {
                            isPromotingDragActive = false
                            promotedTrackTitle = null
                        }
                    )
                }
            }

            // ==========================================
            // 4. AUTOPLAY (Dimmed rows)
            // ==========================================
            if (playbackState.autoplayTracks.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Autoplay",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = SpotifyTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                itemsIndexed(
                    items = playbackState.autoplayTracks,
                    key = { idx, s -> "autoplay_${s.id}_$idx" }
                ) { _, song ->
                    AutoplayRow(song = song)
                }
            }
        }

        // ==========================================
        // COLLAPSIBLE LIVE LAYER DEBUG STRIP
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SpotifyElevated)
                .border(width = 1.dp, color = SpotifyDivider)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isDebugExpanded = !isDebugExpanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SpotifyGreen)
                    )
                    Text(
                        text = "Layer Architecture State",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = SpotifyTextPrimary
                    )
                }

                Icon(
                    imageVector = if (isDebugExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle Debug Strip",
                    tint = SpotifyTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = isDebugExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DebugPropertyRow(label = "shuffle", value = "${playbackState.shuffle}")
                    DebugPropertyRow(label = "repeat mode", value = playbackState.repeat.name.lowercase())
                    DebugPropertyRow(label = "userQueue.length", value = "${playbackState.userQueue.size}")
                    DebugPropertyRow(
                        label = "contextIndex / contextOrder.length",
                        value = "${playbackState.contextIndex} / ${playbackState.contextOrder.size}"
                    )
                    DebugPropertyRow(label = "history.length", value = "${playbackState.history.size}")
                    DebugPropertyRow(
                        label = "currentTrackId",
                        value = playbackState.currentTrackId ?: "null"
                    )
                    DebugPropertyRow(
                        label = "context uri",
                        value = playbackState.context?.uri ?: "null"
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugPropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = SpotifyTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            color = SpotifyGreen
        )
    }
}

/**
 * Pinned Now Playing row with animated equalizer bars and accent tinting.
 */
@Composable
private fun NowPlayingRow(
    song: Song,
    isPlaying: Boolean,
    repeatMode: RepeatMode
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SpotifyGreen.copy(alpha = 0.12f))
            .border(1.dp, SpotifyGreen.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        SongCover(
            imageUrl = song.coverUrl,
            size = 48.dp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = SpotifyGreen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = SpotifyTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Animated Equalizer Bars while playing
        if (isPlaying) {
            AnimatedEqualizerBars(tint = SpotifyGreen)
        } else {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = "Paused",
                tint = SpotifyGreen.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Animated equalizer visualizer with 3 fluctuating vertical bars.
 */
@Composable
private fun AnimatedEqualizerBars(
    tint: Color = SpotifyGreen
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")

    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "b1"
    )

    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 18f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "b2"
    )

    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "b3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = Modifier
            .height(24.dp)
            .padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(bar1Height.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(bar2Height.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(tint)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(bar3Height.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(tint)
        )
    }
}

/**
 * Queue row with horizontal swipe gestures:
 * - Swipe towards left from right: Adds to queue (green indicator)
 * - Swipe towards right from left: Removes from queue (red indicator)
 *
 * 3 dots menu and time duration are removed as requested.
 */
@Composable
private fun QueueTrackRow(
    song: Song,
    isUserQueue: Boolean,
    onPlayNow: () -> Unit,
    onRemove: (() -> Unit)?,
    onPromote: (() -> Unit)?,
    onReorder: ((from: Int, to: Int) -> Unit)? = null,
    totalUserQueueCount: Int = 0,
    currentIndexInUserQueue: Int = 0,
    onDragPromotionStart: (() -> Unit)? = null,
    onDragPromotionCommit: (() -> Unit)? = null,
    onDragPromotionCancel: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeOffset = remember { Animatable(0f) }
    val swipeThresholdPx = with(density) { 60.dp.toPx() }
    val maxSwipePx = with(density) { 110.dp.toPx() }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp)
    ) {
        // BACKGROUND INDICATOR REVEAL
        val currentOffset = swipeOffset.value
        if (currentOffset < -8f) {
            // Swiping towards left from right: ADD TO QUEUE (Green)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SpotifyGreen.copy(alpha = 0.90f))
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Add to queue",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Add to queue",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else if (currentOffset > 8f) {
            // Swiping towards right from left (opposite): REMOVE FROM QUEUE (Red)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SpotifyRed.copy(alpha = 0.90f))
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Remove from queue",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }

        // FOREGROUND TRACK ROW
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffset.value }
                .clip(RoundedCornerShape(8.dp))
                .background(SpotifyBlack)
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val next = (swipeOffset.value + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                                swipeOffset.snapTo(next)
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                val finalOffset = swipeOffset.value
                                if (finalOffset <= -swipeThresholdPx) {
                                    // Swipe towards left from right: ADD TO QUEUE
                                    onPromote?.invoke()
                                } else if (finalOffset >= swipeThresholdPx) {
                                    // Swipe towards right from left: REMOVE FROM QUEUE
                                    onRemove?.invoke()
                                }
                                swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    )
                }
                .clickable { onPlayNow() }
                .padding(vertical = 4.dp, horizontal = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SongCover(
                    imageUrl = song.coverUrl,
                    size = 44.dp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.5.sp
                        ),
                        color = SpotifyTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = SpotifyTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Drag handle for reordering inside userQueue
                if (isUserQueue && onReorder != null) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Reorder",
                        tint = SpotifyTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(36.dp)
                            .padding(6.dp)
                            .pointerInput(currentIndexInUserQueue) {
                                detectDragGestures(
                                    onDragStart = { dragOffsetY = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                    },
                                    onDragEnd = {
                                        val rowHeight = 56.dp.toPx()
                                        val steps = (dragOffsetY / rowHeight).toInt()
                                        if (steps != 0) {
                                            val target = (currentIndexInUserQueue + steps).coerceIn(0, totalUserQueueCount - 1)
                                            onReorder(currentIndexInUserQueue, target)
                                        }
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = { dragOffsetY = 0f }
                                )
                            }
                    )
                }
            }
        }
    }
}

/**
 * Dimmed Autoplay recommendation row.
 */
@Composable
private fun AutoplayRow(song: Song) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = 0.5f)
            .padding(vertical = 4.dp)
    ) {
        SongCover(
            imageUrl = song.coverUrl,
            size = 44.dp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp
                ),
                color = SpotifyTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = SpotifyTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = "Autoplay recommendation",
            tint = SpotifyTextSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun formatDuration(seconds: Double): String {
    val secInt = seconds.toInt()
    val m = secInt / 60
    val s = secInt % 60
    return String.format("%d:%02d", m, s)
}
