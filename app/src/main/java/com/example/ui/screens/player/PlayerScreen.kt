package com.example.ui.screens.player

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.example.audio.AudioController
import com.example.audio.PlaybackState
import com.example.audio.RepeatMode
import com.example.ui.components.SongCover
import com.example.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    audioController: AudioController,
    playbackState: PlaybackState,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onNavigateToEqualizer: () -> Unit = {},
    onToggleDownload: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onClose: () -> Unit,
    onLikeToggle: () -> Unit
) {
    val song = playbackState.currentSong ?: run {
        onClose()
        return
    }

    val effectiveDurationMs = if (playbackState.durationMs > 0L) {
        playbackState.durationMs
    } else if (song.duration > 0.0) {
        (song.duration * 1000).toLong()
    } else {
        0L
    }

    val context = LocalContext.current
    var showQueueSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    val sleepTimerSeconds by audioController.sleepTimerRemainingSeconds.collectAsState()
    val isSleepTimerActive by audioController.isSleepTimerActive.collectAsState()
    val stopAtEndOfTrack by audioController.stopAtEndOfTrack.collectAsState()

    // Dynamic atmospheric gradient extracted from song picture (matching screenshot):
    // Pure pitch black at top (behind header and upper artwork),
    // blooming into the rich song-dependent dominant color across the middle (track info, seekbar, controls),
    // then smoothly deepening into a dark shade at the bottom.
    val dominant = playbackState.dominantColor
    val midTone = Color(
        red = (dominant.red * 0.72f).coerceIn(0f, 1f),
        green = (dominant.green * 0.72f).coerceIn(0f, 1f),
        blue = (dominant.blue * 0.72f).coerceIn(0f, 1f),
        alpha = 1f
    )
    val bottomTone = Color(
        red = (dominant.red * 0.22f).coerceIn(0f, 1f),
        green = (dominant.green * 0.22f).coerceIn(0f, 1f),
        blue = (dominant.blue * 0.22f).coerceIn(0f, 1f),
        alpha = 1f
    )

    val backgroundBrush = Brush.verticalGradient(
        0.0f to Color.Black,
        0.32f to Color.Black,
        0.50f to midTone.copy(alpha = 0.65f),
        0.68f to midTone,
        0.88f to bottomTone,
        1.0f to Color(0xFF080808)
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(backgroundBrush)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // Absorb all taps to guarantee zero ghost clicks to background views
            )
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val isLandscape = screenWidth > screenHeight

        // Fully variable fluid scales based on actual phone height and width:
        // Reference baseline: 390dp x 800dp. We normalize scale factors between small phones (e.g. 560dp) and tall devices.
        val heightScale = (screenHeight.value / 800f).coerceIn(0.72f, 1.25f)
        val widthScale = (screenWidth.value / 390f).coerceIn(0.75f, 1.25f)

        // Dynamic, variable artwork size directly calculated from screen dimensions:
        // In portrait, the artwork is the prominent hero element that fills the width between margins,
        // scaled dynamically to fit all controls on shorter and taller screens alike.
        // In landscape, height is the constraining dimension.
        val horizontalPadding = (20.dp * widthScale).coerceIn(16.dp, 28.dp)

        val portraitArtMaxWidth = (screenWidth - (horizontalPadding * 2) - 8.dp).coerceAtLeast(140.dp)
        val portraitArtMaxHeight = (screenHeight - 290.dp).coerceAtLeast(140.dp)
        val portraitArtworkSize = min(portraitArtMaxWidth, portraitArtMaxHeight).coerceIn(160.dp, 400.dp)

        val landscapeArtMaxHeight = (screenHeight - 88.dp).coerceAtLeast(140.dp)
        val landscapeArtMaxWidth = (screenWidth * 0.40f).coerceAtLeast(140.dp)
        val landscapeArtworkSize = min(landscapeArtMaxWidth, landscapeArtMaxHeight).coerceIn(140.dp, 360.dp)

        val artworkSize = if (isLandscape) landscapeArtworkSize else portraitArtworkSize

        // Proportional control sizing - enlarged playback controls below artwork
        val playButtonSize = (76.dp * heightScale).coerceIn(64.dp, 88.dp)
        val playIconSize = (44.dp * heightScale).coerceIn(36.dp, 52.dp)
        val skipButtonSize = (56.dp * heightScale).coerceIn(46.dp, 64.dp)
        val skipIconSize = (42.dp * heightScale).coerceIn(34.dp, 48.dp)
        val sideControlSize = (48.dp * heightScale).coerceIn(40.dp, 56.dp)
        val sideIconSize = (28.dp * heightScale).coerceIn(24.dp, 34.dp)
        val topActionSize = (38.dp * heightScale).coerceIn(32.dp, 42.dp)
        val topDownArrowSize = (30.dp * heightScale).coerceIn(24.dp, 34.dp)
        val topMoreIconSize = (23.dp * heightScale).coerceIn(18.dp, 26.dp)

        // Variable paddings and vertical spacing
        val topBarVerticalPadding = (6.dp * heightScale).coerceIn(2.dp, 10.dp)
        val pelletVerticalPadding = (6.dp * heightScale).coerceIn(4.dp, 8.dp)
        val pelletOuterVerticalPadding = (3.dp * heightScale).coerceIn(1.dp, 5.dp)
        val artworkVerticalPadding = (4.dp * heightScale).coerceIn(2.dp, 8.dp)
        val controlsVerticalPadding = (4.dp * heightScale).coerceIn(2.dp, 8.dp)
        val bottomRowVerticalPadding = (4.dp * heightScale).coerceIn(2.dp, 8.dp)
        val titleSectionBottomSpacing = (8.dp * heightScale).coerceIn(4.dp, 14.dp)
        val sliderBottomSpacing = (6.dp * heightScale).coerceIn(3.dp, 10.dp)
        val bottomExtraSpacing = (6.dp * heightScale).coerceIn(4.dp, 12.dp)

        // Variable typography scaled to device size
        val titleFontSize = (24.sp * heightScale.coerceIn(0.85f, 1.15f))
        val artistFontSize = (17.sp * heightScale.coerceIn(0.85f, 1.15f))
        val topSubheaderFontSize = (11.sp * heightScale.coerceIn(0.85f, 1.15f))
        val topHeaderFontSize = (13.sp * heightScale.coerceIn(0.85f, 1.15f))
        val pelletHeaderFontSize = (11.sp * heightScale.coerceIn(0.85f, 1.15f))
        val pelletSubFontSize = (10.sp * heightScale.coerceIn(0.85f, 1.15f))
        val likeBadgeSize = (40.dp * heightScale).coerceIn(34.dp, 46.dp)
        val likeIconContainerSize = (28.dp * heightScale).coerceIn(24.dp, 32.dp)
        val likeIconSize = (16.dp * heightScale).coerceIn(14.dp, 19.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = if (isLandscape) 960.dp else 600.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = horizontalPadding)
            ) {
            // Top Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = topBarVerticalPadding)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(topActionSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize Player",
                        tint = Color.White,
                        modifier = Modifier.size(topDownArrowSize)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Playing from Playlist",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = topSubheaderFontSize,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = playbackState.playbackSource.ifBlank { if (song.isLiked) "Liked Songs" else "Alaktra Stream" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = topHeaderFontSize
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 3-dot menu button at top right
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(topActionSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = Color.White,
                            modifier = Modifier.size(topMoreIconSize)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(AlaktraSurface)
                    ) {
                        // Download
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isDownloaded) "Remove download" else "Download",
                                    color = AlaktraTextPrimary
                                )
                            },
                            leadingIcon = {
                                if (downloadProgress != null) {
                                    CircularProgressIndicator(
                                        progress = { downloadProgress },
                                        color = AlaktraMint,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                                        contentDescription = null,
                                        tint = if (isDownloaded) AlaktraMint else AlaktraTextPrimary
                                    )
                                }
                            },
                            onClick = {
                                showMenu = false
                                onToggleDownload()
                            }
                        )

                        // Add to playlist
                        DropdownMenuItem(
                            text = { Text("Add to playlist", color = AlaktraTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = null,
                                    tint = AlaktraTextPrimary
                                )
                            },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            }
                        )

                        // Queue
                        DropdownMenuItem(
                            text = { Text("Queue", color = AlaktraTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = AlaktraTextPrimary
                                )
                            },
                            onClick = {
                                showMenu = false
                                showQueueSheet = true
                            }
                        )

                        // Equalizer
                        DropdownMenuItem(
                            text = { Text("Equalizer", color = AlaktraTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = AlaktraMint
                                )
                            },
                            onClick = {
                                showMenu = false
                                onNavigateToEqualizer()
                            }
                        )

                        // Sleep timer
                        DropdownMenuItem(
                            text = {
                                val label = if (isSleepTimerActive && sleepTimerSeconds != null) {
                                    if (stopAtEndOfTrack) "Sleep timer (End of track)" else "Sleep timer (${(sleepTimerSeconds!! + 59) / 60}m)"
                                } else {
                                    "Sleep timer"
                                }
                                Text(label, color = AlaktraTextPrimary)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = if (isSleepTimerActive) AlaktraMint else AlaktraTextPrimary
                                )
                            },
                            onClick = {
                                showMenu = false
                                showSleepTimerDialog = true
                            }
                        )

                        // Share
                        DropdownMenuItem(
                            text = { Text("Share", color = AlaktraTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = AlaktraTextPrimary
                                )
                            },
                            onClick = {
                                showMenu = false
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Listening to ${song.title} by ${song.artist} on Alaktra Stream"
                                    )
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share song via"))
                            }
                        )
                    }
                }
            }

            if (isLandscape) {
                // Two-pane side-by-side layout for landscape mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Left Pane: Artwork
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(artworkSize)
                                .padding(vertical = artworkVerticalPadding)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shadow(16.dp, RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .background(AlaktraCard)
                            ) {
                                SongCover(
                                    imageUrl = song.coverUrl,
                                    size = artworkSize,
                                    cornerRadius = 10.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // Right Pane: Info, Seeker, Controls, Actions
                    Column(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Title, Artist, and Green Like Checkmark
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = titleFontSize
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = artistFontSize,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = onLikeToggle,
                                modifier = Modifier.size(likeBadgeSize)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(likeIconContainerSize)
                                        .clip(CircleShape)
                                        .background(if (song.isLiked) Color(0xFF1DB954) else Color.White.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = if (song.isLiked) Icons.Default.Check else Icons.Default.FavoriteBorder,
                                        contentDescription = if (song.isLiked) "Liked" else "Like",
                                        tint = if (song.isLiked) Color.Black else Color.White,
                                        modifier = Modifier.size(likeIconSize)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        CustomDurationBar(
                            positionMs = playbackState.currentPositionMs,
                            durationMs = effectiveDurationMs,
                            onSeek = { targetMs -> audioController.seekTo(targetMs) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { audioController.toggleShuffle() },
                                modifier = Modifier.size(sideControlSize)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = "Shuffle",
                                        tint = if (playbackState.shuffle) Color(0xFF1DB954) else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(sideIconSize)
                                    )
                                    if (playbackState.shuffle) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF1DB954))
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { audioController.previous() },
                                modifier = Modifier.size(skipButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Track",
                                    tint = Color.White,
                                    modifier = Modifier.size(skipIconSize)
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(playButtonSize)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { audioController.togglePlayPause() }
                            ) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(playIconSize)
                                )
                            }

                            IconButton(
                                onClick = { audioController.next() },
                                modifier = Modifier.size(skipButtonSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Track",
                                    tint = Color.White,
                                    modifier = Modifier.size(skipIconSize)
                                )
                            }

                            IconButton(
                                onClick = { audioController.cycleRepeatMode() },
                                modifier = Modifier.size(sideControlSize)
                            ) {
                                val isRepeatActive = playbackState.repeat != RepeatMode.OFF
                                val loopIcon = if (playbackState.repeat == RepeatMode.TRACK) {
                                    Icons.Default.RepeatOne
                                } else {
                                    Icons.Default.Repeat
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = loopIcon,
                                        contentDescription = "Repeat Mode",
                                        tint = if (isRepeatActive) Color(0xFF1DB954) else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(sideIconSize)
                                    )
                                    if (isRepeatActive) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF1DB954))
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // Bottom Actions
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { showSleepTimerDialog = true },
                                modifier = Modifier.size(sideControlSize)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Sleep Timer",
                                        tint = if (isSleepTimerActive) AlaktraMint else Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(sideIconSize)
                                    )
                                    if (isSleepTimerActive) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .align(Alignment.TopEnd)
                                                .clip(CircleShape)
                                                .background(AlaktraMint)
                                        )
                                    }
                                }
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                                    .clickable { showCreditsDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Credits",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(sideIconSize * 0.60f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Credits",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = Color.White.copy(alpha = 0.95f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            IconButton(
                                onClick = { showQueueSheet = true },
                                modifier = Modifier.size(sideControlSize)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = "View Queue",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(sideIconSize)
                                )
                            }
                        }
                    }
                }
            } else {
                // Portrait Layout
                Spacer(modifier = Modifier.weight(1f))

                // Artwork with definite auto-adjusted proportion
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(artworkSize)
                        .padding(vertical = artworkVerticalPadding)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(16.dp, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .background(AlaktraCard)
                    ) {
                        SongCover(
                            imageUrl = song.coverUrl,
                            size = artworkSize,
                            cornerRadius = 10.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1.2f))

                // Title, Artist, and Green Like Checkmark
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = titleFontSize
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = artistFontSize,
                                fontWeight = FontWeight.Normal
                            ),
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Compact circular like badge (smaller checkmark button)
                    IconButton(
                        onClick = onLikeToggle,
                        modifier = Modifier.size(likeBadgeSize)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(likeIconContainerSize)
                                .clip(CircleShape)
                                .background(if (song.isLiked) Color(0xFF1DB954) else Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                imageVector = if (song.isLiked) Icons.Default.Check else Icons.Default.FavoriteBorder,
                                contentDescription = if (song.isLiked) "Liked" else "Like",
                                tint = if (song.isLiked) Color.Black else Color.White,
                                modifier = Modifier.size(likeIconSize)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(titleSectionBottomSpacing))

                // Custom Duration Bar (matching uploaded screenshot)
                CustomDurationBar(
                    positionMs = playbackState.currentPositionMs,
                    durationMs = effectiveDurationMs,
                    onSeek = { targetMs -> audioController.seekTo(targetMs) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(sliderBottomSpacing))

                // Playback Controls Row: Shuffle, Prev, Play/Pause, Next, Loop
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = controlsVerticalPadding)
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { audioController.toggleShuffle() },
                        modifier = Modifier.size(sideControlSize)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (playbackState.shuffle) Color(0xFF1DB954) else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(sideIconSize)
                            )
                            if (playbackState.shuffle) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1DB954))
                                )
                            }
                        }
                    }

                    // Previous
                    IconButton(
                        onClick = { audioController.previous() },
                        modifier = Modifier.size(skipButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = Color.White,
                            modifier = Modifier.size(skipIconSize)
                        )
                    }

                    // Play / Pause: Solid White Circle with Solid Black Icon (matching screenshot)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(playButtonSize)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { audioController.togglePlayPause() }
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(playIconSize)
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { audioController.next() },
                        modifier = Modifier.size(skipButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = Color.White,
                            modifier = Modifier.size(skipIconSize)
                        )
                    }

                    // Loop / Repeat button
                    IconButton(
                        onClick = { audioController.cycleRepeatMode() },
                        modifier = Modifier.size(sideControlSize)
                    ) {
                        val isRepeatActive = playbackState.repeat != RepeatMode.OFF
                        val loopIcon = if (playbackState.repeat == RepeatMode.TRACK) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = loopIcon,
                                contentDescription = "Repeat Mode",
                                tint = if (isRepeatActive) Color(0xFF1DB954) else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(sideIconSize)
                            )
                            if (isRepeatActive) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1DB954))
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom row: Sleep Timer in left corner, Credits pellet in center, Queue in right corner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = bottomRowVerticalPadding)
                ) {
                    // Sleep Timer button in left corner (replaces Share)
                    IconButton(
                        onClick = { showSleepTimerDialog = true },
                        modifier = Modifier.size(sideControlSize)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Sleep Timer",
                                tint = if (isSleepTimerActive) AlaktraMint else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(sideIconSize)
                            )
                            if (isSleepTimerActive) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(AlaktraMint)
                                )
                            }
                        }
                    }

                    // Credits pellet shifted to the center of the bottom row, level with sleep timer and queue
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                            .clickable { showCreditsDialog = true }
                            .padding(horizontal = 14.dp, vertical = (pelletVerticalPadding + 2.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Credits",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(sideIconSize * 0.60f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Credits",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White.copy(alpha = 0.95f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Queue button in right bottom corner
                    IconButton(
                        onClick = { showQueueSheet = true },
                        modifier = Modifier.size(sideControlSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "View Queue",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(sideIconSize)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(bottomExtraSpacing))
            }
        }
    }

        // Sleep Timer Selection Dialog
        if (showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { showSleepTimerDialog = false },
                containerColor = AlaktraCard,
                titleContentColor = AlaktraTextPrimary,
                textContentColor = AlaktraTextSecondary,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = AlaktraMint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        // If an active timer is running, show countdown card and quick extension buttons
                        if (isSleepTimerActive && sleepTimerSeconds != null) {
                            val remSec = sleepTimerSeconds ?: 0
                            val min = remSec / 60
                            val sec = remSec % 60
                            val displayTime = if (stopAtEndOfTrack) {
                                "Playback stops at the end of this track"
                            } else {
                                "Audio will stop in %d min %02d sec".format(Locale.US, min, sec)
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = AlaktraMint.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 14.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = displayTime,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = AlaktraMint
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                audioController.addMinutesToSleepTimer(5)
                                                Toast.makeText(context, "+5 minutes added to sleep timer", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.6f))
                                        ) {
                                            Text("+5 min", color = AlaktraMint, fontSize = 12.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                audioController.addMinutesToSleepTimer(15)
                                                Toast.makeText(context, "+15 minutes added to sleep timer", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.6f))
                                        ) {
                                            Text("+15 min", color = AlaktraMint, fontSize = 12.sp)
                                        }

                                        TextButton(
                                            onClick = {
                                                audioController.cancelSleepTimer()
                                                showSleepTimerDialog = false
                                                Toast.makeText(context, "Sleep timer turned off", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Turn off", color = Color(0xFFFF5252), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Stop playback after:",
                            style = MaterialTheme.typography.labelMedium,
                            color = AlaktraTextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        val timerOptions = listOf(
                            "End of this track" to -1,
                            "5 minutes" to 5,
                            "10 minutes" to 10,
                            "15 minutes" to 15,
                            "30 minutes" to 30,
                            "45 minutes" to 45,
                            "60 minutes" to 60
                        )

                        timerOptions.forEach { (label, minutes) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (minutes == -1) {
                                            audioController.setSleepTimer(0, endOfTrack = true)
                                            val msg = "Playback will stop at the end of this track"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        } else {
                                            audioController.setSleepTimer(minutes, endOfTrack = false)
                                            val msg = "Sleep timer set for $minutes minutes"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                        showSleepTimerDialog = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (minutes == -1) Icons.Default.SkipNext else Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = if ((minutes == -1 && stopAtEndOfTrack) || (minutes > 0 && !stopAtEndOfTrack && sleepTimerSeconds != null && sleepTimerSeconds!! / 60 == minutes)) AlaktraMint else AlaktraTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = label,
                                    color = if ((minutes == -1 && stopAtEndOfTrack) || (minutes > 0 && !stopAtEndOfTrack && sleepTimerSeconds != null && sleepTimerSeconds!! / 60 == minutes)) AlaktraMint else AlaktraTextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if ((minutes == -1 && stopAtEndOfTrack) || (minutes > 0 && !stopAtEndOfTrack && sleepTimerSeconds != null && sleepTimerSeconds!! / 60 == minutes)) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        if (isSleepTimerActive) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        audioController.cancelSleepTimer()
                                        showSleepTimerDialog = false
                                        Toast.makeText(context, "Sleep timer turned off", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Turn off timer",
                                    color = Color(0xFFFF5252),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSleepTimerDialog = false }) {
                        Text("Close", color = AlaktraMint)
                    }
                }
            )
        }

        // Song Credits Dialog
        if (showCreditsDialog) {
            AlertDialog(
                onDismissRequest = { showCreditsDialog = false },
                containerColor = AlaktraCard,
                titleContentColor = AlaktraTextPrimary,
                textContentColor = AlaktraTextSecondary,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = AlaktraMint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Song Credits", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column {
                            Text(
                                text = "Title",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                        }
                        Column {
                            Text(
                                text = "Performed by",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                        }
                        Column {
                            Text(
                                text = "Platform & Quality",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "Alaktra Stream • Lossless / Hi-Fi Audio",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraMint
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCreditsDialog = false }) {
                        Text("Close", color = AlaktraMint)
                    }
                }
            )
        }

        // Layered Queue Modal Bottom Sheet (Spotify & Apple Music style)
        if (showQueueSheet) {
            LayeredQueueBottomSheet(
                playbackState = playbackState,
                audioController = audioController,
                onDismiss = { showQueueSheet = false }
            )
        }
    }
}

/**
 * Custom duration seek bar exactly matching the uploaded screenshot:
 * - Thin 3.5dp rounded bar
 * - Inactive: translucent white
 * - Active: crisp solid white
 * - Thumb: small white circle dot at current position
 * - Direct tap & scrub gestures
 * - Timestamps directly below in clean 12sp typography
 */
@Composable
private fun CustomDurationBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // When track/duration changes, cleanly reset dragging state
    LaunchedEffect(durationMs) {
        isDragging = false
    }

    val currentFraction = if (isDragging) {
        dragFraction
    } else {
        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    val displayPositionMs = if (isDragging) {
        (dragFraction * durationMs).toLong()
    } else {
        positionMs
    }

    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth()) {
        var barWidthPx by remember { mutableFloatStateOf(1f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .testTag("player_seek_bar")
                .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0L && barWidthPx > 0f) {
                            val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            dragFraction = frac
                            onSeek((frac * durationMs).toLong())
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (durationMs > 0L && barWidthPx > 0f) {
                                isDragging = true
                                dragFraction = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = {
                            if (durationMs > 0L) {
                                onSeek((dragFraction * durationMs).toLong())
                            }
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (durationMs > 0L && barWidthPx > 0f) {
                                dragFraction = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val totalWidthPx = barWidthPx
            val activeWidthPx = totalWidthPx * currentFraction

            // Inactive track line (centered vertically)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )

            // Active track line (centered vertically)
            Box(
                modifier = Modifier
                    .fillMaxWidth(currentFraction)
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )

            // Small white thumb circle dot
            val thumbRadiusDp = 5.dp
            val thumbRadiusPx = with(density) { thumbRadiusDp.toPx() }
            val thumbOffsetDp = with(density) {
                ((activeWidthPx - thumbRadiusPx).coerceIn(0f, (totalWidthPx - thumbRadiusPx * 2f).coerceAtLeast(0f))).toDp()
            }

            Box(
                modifier = Modifier
                    .offset(x = thumbOffsetDp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Timestamps below
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = formatTime(displayPositionMs),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
