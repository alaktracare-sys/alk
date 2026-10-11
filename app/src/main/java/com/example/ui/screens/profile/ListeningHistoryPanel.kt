package com.example.ui.screens.profile

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.audio.AudioController
import com.example.data.local.ListeningHistoryEntity
import com.example.data.repository.MusicRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class DaywiseGroup(
    val date: LocalDate,
    val dayTitle: String,
    val fullDateFormatted: String,
    val songs: List<ListeningHistoryEntity>,
    val totalSeconds: Long,
    val uniqueSongCount: Int
)

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

fun formatTimeShort(timestampMs: Long): String {
    return try {
        Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) {
        ""
    }
}

@Composable
fun ListeningHistorySummaryCard(
    historyList: List<ListeningHistoryEntity>,
    onOpenFullHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = remember { LocalDate.now() }

    val todayItems = remember(historyList) {
        historyList.filter {
            val d = Instant.ofEpochMilli(it.playedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            d == today
        }
    }

    val todaySeconds = remember(todayItems) {
        todayItems.sumOf { it.effectiveListenedSeconds }
    }

    val monthItems = remember(historyList) {
        historyList.filter {
            val d = Instant.ofEpochMilli(it.playedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            d.year == today.year && d.monthValue == today.monthValue
        }
    }

    val monthName = remember {
        today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenFullHistory() },
        colors = CardDefaults.cardColors(containerColor = AlaktraCard),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AlaktraSurface)
                        .border(1.dp, AlaktraBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = AlaktraMint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Listening History",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AlaktraMint.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (todayItems.isNotEmpty()) "Today" else monthName,
                                color = AlaktraMint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = if (todayItems.isNotEmpty()) {
                            "${todayItems.size} tracks played today • ${formatListeningSeconds(todaySeconds)} listened"
                        } else if (monthItems.isNotEmpty()) {
                            "${monthItems.size} tracks this month • ${formatListeningSeconds(monthItems.sumOf { it.effectiveListenedSeconds })} listened"
                        } else {
                            "Track songs listened to day by day with real listen time"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View History",
                    tint = AlaktraTextSecondary
                )
            }

            // Preview of recent played tracks
            if (historyList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = AlaktraBorder.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (todayItems.isNotEmpty()) "PLAYED TODAY" else "RECENT PLAYS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AlaktraTextMuted,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = "Full Day Stats →",
                        color = AlaktraMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                val previewItems = if (todayItems.isNotEmpty()) todayItems.take(2) else historyList.take(2)
                previewItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        AsyncImage(
                            model = item.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AlaktraSurface)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = AlaktraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullListeningHistorySheet(
    historyList: List<ListeningHistoryEntity>,
    repository: MusicRepository,
    audioController: AudioController,
    onDismiss: () -> Unit
) {
    val today = remember { LocalDate.now() }
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems = remember(historyList, searchQuery) {
        if (searchQuery.isBlank()) {
            historyList
        } else {
            historyList.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Group items daywise, newest day first
    val daywiseGroups = remember(filteredItems) {
        filteredItems.groupBy { item ->
            Instant.ofEpochMilli(item.playedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }.entries
            .sortedByDescending { it.key }
            .map { (date, songList) ->
                val dayTitle = when {
                    date == today -> "Today"
                    date == today.minusDays(1) -> "Yesterday"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                }
                val fullDate = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
                val totalSec = songList.sumOf { it.effectiveListenedSeconds }
                val uniqueSongs = songList.map { it.songId }.distinct().size
                DaywiseGroup(
                    date = date,
                    dayTitle = dayTitle,
                    fullDateFormatted = fullDate,
                    songs = songList.sortedByDescending { it.playedAt },
                    totalSeconds = totalSec,
                    uniqueSongCount = uniqueSongs
                )
            }
    }

    // Today specific stats
    val todayStats = remember(historyList) {
        val items = historyList.filter {
            val d = Instant.ofEpochMilli(it.playedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            d == today
        }
        val totalSec = items.sumOf { it.effectiveListenedSeconds }
        val uniqueCount = items.map { it.songId }.distinct().size
        Triple(items.size, uniqueCount, totalSec)
    }

    Scaffold(
        containerColor = AlaktraBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Listening History",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AlaktraTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AlaktraBackground)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
            ) {
                // Today's Live Listening Banner (Always visible at top)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan)))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = Color(0xFF041C12),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Today's Stats",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )
                                    Text(
                                        text = today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AlaktraTextSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Stats Pillars (Songs listened to, time listened for the whole day, unique songs)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Total Songs Played Today
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AlaktraSurface)
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "SONGS TODAY",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AlaktraTextMuted,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${todayStats.first}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = AlaktraTextPrimary
                                    )
                                    Text(
                                        text = "${todayStats.second} unique",
                                        fontSize = 11.sp,
                                        color = AlaktraTextSecondary
                                    )
                                }
                            }

                            // Total Time Listened for the Whole Day
                            Box(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AlaktraSurface)
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "LISTEN TIME TODAY",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AlaktraTextMuted,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatListeningSeconds(todayStats.third),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = AlaktraMint
                                    )
                                    Text(
                                        text = if (todayStats.first > 0) "Updates after each hour" else "No plays yet today",
                                        fontSize = 11.sp,
                                        color = AlaktraTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Main Content: Daywise List or Empty State
                if (daywiseGroups.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraSurface)
                                    .border(1.dp, AlaktraBorder, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = if (searchQuery.isNotBlank()) "No songs match \"$searchQuery\"" else "No listening history recorded yet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = if (searchQuery.isNotBlank()) "Try checking the spelling or searching another artist or song title." else "Play songs from your library — each played track and your exact listening time will be recorded here automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        daywiseGroups.forEach { dayGroup ->
                            // Sticky Day Header showing: Date, Songs count for the day, and Total listen time for the whole day!
                            item(key = "header_${dayGroup.date}") {
                                DaywiseHeader(
                                    group = dayGroup,
                                    onPlayAllDay = {
                                        val daySongs = dayGroup.songs.map { it.toSong() }
                                        audioController.playQueue(daySongs, 0, dayGroup.dayTitle)
                                    }
                                )
                            }

                            // Individual songs listened to during that day
                            items(dayGroup.songs, key = { it.id }) { historyItem ->
                                HistorySongRow(
                                    item = historyItem,
                                    onPlay = {
                                        val song = historyItem.toSong()
                                        val allSongs = dayGroup.songs.map { it.toSong() }
                                        val index = allSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                                        audioController.playQueue(allSongs, index, dayGroup.dayTitle)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DaywiseHeader(
    group: DaywiseGroup,
    onPlayAllDay: () -> Unit
) {
    val durationText = formatListeningSeconds(group.totalSeconds)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(AlaktraBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(AlaktraSurface)
                    .border(1.dp, AlaktraBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = AlaktraMint,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = group.dayTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary
                )
                Text(
                    text = group.fullDateFormatted,
                    fontSize = 11.sp,
                    color = AlaktraTextMuted
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Day Summary Pill: exact song count and total listen time for the day
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AlaktraSurface)
                    .border(1.dp, AlaktraBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${group.songs.size} ${if (group.songs.size == 1) "song" else "songs"} • $durationText",
                    color = AlaktraMint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@Composable
fun HistorySongRow(
    item: ListeningHistoryEntity,
    onPlay: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        // Cover Art
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AlaktraSurface)
                .border(1.dp, AlaktraBorder, RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist (Clean pellet: no played time or duration badge)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AlaktraTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = AlaktraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
    }
}
