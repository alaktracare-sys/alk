package com.example.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.ListeningHistoryEntity
import com.example.model.Song
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CapsuleBg = Color(0xFF121212)
private val CapsuleCardBg = Color(0xFF1F1F1F)
private val CapsuleSubSurface = Color(0xFF282828)
private val CapsuleGreen = Color(0xFF1ED760)
private val CapsuleDarkGreen = Color(0xFF15803D)
private val CapsuleMuted = Color(0xFFB3B3B3)
private val CapsuleBorder = Color(0xFF2E2E2E)

fun formatListeningSeconds(seconds: Long): String {
    val safeSec = seconds.coerceAtLeast(0L)
    val hrs = safeSec / 3600L
    val mins = (safeSec % 3600L) / 60L
    return if (hrs > 0L) {
        "${hrs}h ${mins}m"
    } else {
        "${mins}m"
    }
}

fun formatTimeShort(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    return try {
        Instant.ofEpochMilli(timestampMs)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) {
        ""
    }
}

/**
 * Filters listening history strictly to items played on [today] in [zone], sorted newest-first.
 * Past days (Yesterday or older) are excluded so Listening History shows ONLY the current day.
 */
fun filterTodayHistory(
    historyList: List<ListeningHistoryEntity>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()
): List<ListeningHistoryEntity> {
    return historyList
        .asSequence()
        .filter { item ->
            item.playedAt > 0L &&
                Instant.ofEpochMilli(item.playedAt).atZone(zone).toLocalDate() == today
        }
        .sortedByDescending { it.playedAt }
        .toList()
}

/**
 * Compact anchored Listening History bar pinned to the top of the Sound Capsule screen
 * (above the scrolling monthly feed). Displays ONLY today's track count & listen time.
 */
@Composable
fun AnchoredTodayHistoryBar(
    historyList: List<ListeningHistoryEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)

    val todayItems = remember(historyList, today) {
        filterTodayHistory(historyList, today, zone)
    }
    val todaySeconds = remember(todayItems) {
        todayItems.sumOf { it.effectiveListenedSeconds }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CapsuleBg)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .testTag("sound_capsule_listening_history_card"),
            colors = CardDefaults.cardColors(containerColor = CapsuleCardBg),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CapsuleBorder)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(CapsuleGreen, CapsuleDarkGreen)))
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.listening_history_title),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (todayItems.isNotEmpty()) {
                            val label = if (todayItems.size == 1) "1 track today" else "${todayItems.size} tracks today"
                            "$label • ${formatListeningSeconds(todaySeconds)} listened"
                        } else {
                            stringResource(R.string.listening_history_empty_anchored)
                        },
                        color = CapsuleMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (todayItems.isNotEmpty()) {
                    val latestCover = todayItems.first().coverUrl
                    if (latestCover.isNotBlank()) {
                        AsyncImage(
                            model = latestCover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CapsuleSubSurface)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.listening_history_open_cd),
                    tint = CapsuleGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Today-only Listening History page opened from the anchored top bar in Sound Capsule.
 * Shows strictly the current day's played tracks and listen time.
 */
@Composable
fun TodayListeningHistoryScreen(
    historyList: List<ListeningHistoryEntity>,
    onBack: () -> Unit,
    onPlayTodayQueue: (songs: List<Song>, startIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val todayFormatted = remember(today) {
        today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
    }

    val todayItems = remember(historyList, today) {
        filterTodayHistory(historyList, today, zone)
    }

    val totalSecondsToday = remember(todayItems) {
        todayItems.sumOf { it.effectiveListenedSeconds }
    }
    val uniqueSongsToday = remember(todayItems) {
        todayItems.map { it.songId }.distinct().size
    }

    val bottomDockClearance = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 168.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CapsuleBg)
            .statusBarsPadding()
    ) {
        // Top Header Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .testTag("today_history_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.playlist_back),
                    tint = Color.White
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = stringResource(R.string.listening_history_title),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = todayFormatted,
                    color = CapsuleMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
            ) {
                // Today's Tracks List & Scrollable Summary Card (ONLY Today — no Yesterday or older days)
                if (todayItems.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomDockClearance / 2)
                    ) {
                        TodayStatsSummaryCard(
                            todayFormatted = todayFormatted,
                            todayItemsCount = 0,
                            uniqueSongsToday = 0,
                            totalSecondsToday = totalSecondsToday
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(CapsuleCardBg)
                                        .border(1.dp, CapsuleBorder, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = CapsuleGreen,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = stringResource(R.string.listening_history_empty_today_title),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = stringResource(R.string.listening_history_empty_today_body),
                                    color = CapsuleMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("today_history_songs_list"),
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            bottom = bottomDockClearance
                        )
                    ) {
                        item(key = "today_stats_summary_card") {
                            TodayStatsSummaryCard(
                                todayFormatted = todayFormatted,
                                todayItemsCount = todayItems.size,
                                uniqueSongsToday = uniqueSongsToday,
                                totalSecondsToday = totalSecondsToday
                            )
                        }

                        items(
                            items = todayItems,
                            key = { "${it.id}_${it.playedAt}" },
                            contentType = { "today_history_song_row" }
                        ) { historyItem ->
                            HistorySongRow(
                                item = historyItem,
                                zone = zone,
                                onPlay = {
                                    val queue = todayItems.map { it.toSong() }
                                    val idx = todayItems.indexOfFirst {
                                        it.id == historyItem.id && it.playedAt == historyItem.playedAt
                                    }.coerceAtLeast(0)
                                    onPlayTodayQueue(queue, idx)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySongRow(
    item: ListeningHistoryEntity,
    zone: ZoneId = ZoneId.systemDefault(),
    onPlay: () -> Unit
) {
    val timeLabel = remember(item.playedAt, zone) { formatTimeShort(item.playedAt, zone) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .testTag("today_history_song_${item.id}")
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CapsuleSubSurface)
                .border(1.dp, CapsuleBorder, RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = item.artist,
                color = CapsuleMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (timeLabel.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeLabel,
                color = CapsuleMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun TodayStatsSummaryCard(
    todayFormatted: String,
    todayItemsCount: Int,
    uniqueSongsToday: Int,
    totalSecondsToday: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CapsuleCardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CapsuleBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(CapsuleGreen, CapsuleDarkGreen)))
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.listening_history_today_stats_title),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = todayFormatted,
                        color = CapsuleMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CapsuleSubSurface)
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.listening_history_songs_today_label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CapsuleMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$todayItemsCount",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "$uniqueSongsToday unique",
                            fontSize = 11.sp,
                            color = CapsuleMuted
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CapsuleSubSurface)
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.listening_history_listen_time_today_label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CapsuleMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatListeningSeconds(totalSecondsToday),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CapsuleGreen
                        )
                        Text(
                            text = if (todayItemsCount > 0) "Current day only" else "No plays yet today",
                            fontSize = 11.sp,
                            color = CapsuleMuted
                        )
                    }
                }
            }
        }
    }
}
