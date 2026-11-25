package com.example.soundcapsule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.ListeningHistoryEntity
import com.example.model.Song
import com.example.ui.screens.profile.AnchoredTodayHistoryBar
import com.example.ui.screens.profile.TodayListeningHistoryScreen
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ---- design tokens (Spotify Sound Capsule palette) ----
private val Bg = Color(0xFF121212)
private val CardBg = Color(0xFF1F1F1F)
private val Track = Color(0xFF2A2A2A)
private val Green = Color(0xFF1ED760)
private val DarkGreen = Color(0xFF2F8F53)
private val Muted = Color(0xFFB3B3B3)
private val YearMuted = Color(0xFF9E9E9E)
private val ArtistAccentBlue = Color(0xFF69B4FF)
private val SongAccentYellow = Color(0xFFFFD54F)

private val Gradients = listOf(
    listOf(Color(0xFF1ED760), Color(0xFF0B6B34)),
    listOf(Color(0xFFFF6B6B), Color(0xFF7A1F3D)),
    listOf(Color(0xFF6C8CFF), Color(0xFF26327A)),
    listOf(Color(0xFFFFC94A), Color(0xFFA35A00)),
    listOf(Color(0xFFB36CFF), Color(0xFF4A1F7A)),
    listOf(Color(0xFF4AD8D0), Color(0xFF0F5A63)),
    listOf(Color(0xFFFF8AD8), Color(0xFF7A2060)),
    listOf(Color(0xFF9AA5B1), Color(0xFF3A4450)),
)

private enum class CapsuleScreen { Home, Time, Artists, Songs, History }

/**
 * Entry point. Displays an anchored Today's Listening History bar at the top and all Sound Capsule
 * months in a vertical Spotify-style scrollable feed below without a month dropdown.
 */
@Composable
fun SoundCapsuleHost(
    stats: SoundCapsuleStats,
    availableMonths: List<YearMonth>,
    onMonthSelected: (YearMonth) -> Unit,
    onExit: () -> Unit,
    monthlyFeed: List<SoundCapsuleStats> = listOf(stats),
    historyList: List<ListeningHistoryEntity> = emptyList(),
    onPlayTodayQueue: (songs: List<Song>, startIndex: Int) -> Unit = { _, _ -> },
    onOpenArtist: (artistId: String, artistName: String) -> Unit = { _, _ -> },
    backHandlingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var screen by rememberSaveable { mutableStateOf(CapsuleScreen.Home) }
    val feedListState = rememberLazyListState()
    val effectiveFeed = remember(monthlyFeed, stats) {
        if (monthlyFeed.isNotEmpty()) monthlyFeed else listOf(stats)
    }

    BackHandler(enabled = backHandlingEnabled && screen != CapsuleScreen.Home) { screen = CapsuleScreen.Home }

    Box(modifier.fillMaxSize().background(Bg)) {
        when (screen) {
            CapsuleScreen.Home -> HomeScreen(
                monthlyFeed = effectiveFeed,
                historyList = historyList,
                listState = feedListState,
                onBack = onExit,
                onOpenHistory = { screen = CapsuleScreen.History },
                onTime = { ym ->
                    onMonthSelected(ym)
                    screen = CapsuleScreen.Time
                },
                onArtists = { ym ->
                    onMonthSelected(ym)
                    screen = CapsuleScreen.Artists
                },
                onSongs = { ym ->
                    onMonthSelected(ym)
                    screen = CapsuleScreen.Songs
                },
            )
            CapsuleScreen.History -> TodayListeningHistoryScreen(
                historyList = historyList,
                onBack = { screen = CapsuleScreen.Home },
                onPlayTodayQueue = onPlayTodayQueue,
            )
            CapsuleScreen.Time -> TimeScreen(
                stats = stats,
                onBack = { screen = CapsuleScreen.Home },
            )
            CapsuleScreen.Artists -> ArtistsScreen(
                stats = stats,
                onBack = { screen = CapsuleScreen.Home },
                onOpenArtist = onOpenArtist,
            )
            CapsuleScreen.Songs -> SongsScreen(
                stats = stats,
                onBack = { screen = CapsuleScreen.Home },
            )
        }
    }
}

// ================= screens =================

@Composable
private fun HomeScreen(
    monthlyFeed: List<SoundCapsuleStats>,
    historyList: List<ListeningHistoryEntity>,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onTime: (YearMonth) -> Unit,
    onArtists: (YearMonth) -> Unit,
    onSongs: (YearMonth) -> Unit,
) {
    val bottomDockClearance = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 168.dp
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Fixed top header bar matching Spotify's "Your Sound Capsule" header
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .testTag("sound_capsule_back"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Label(
                text = "Your Sound Capsule",
                size = 16,
                weight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Anchored Today-only Listening History bar pinned at the top of Sound Capsule
        AnchoredTodayHistoryBar(
            historyList = historyList,
            onClick = onOpenHistory,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("sound_capsule_months_list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = bottomDockClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            itemsIndexed(
                items = monthlyFeed,
                key = { _, item -> "${item.month.year}-${item.month.monthValue}" },
            ) { index, monthStats ->
                MonthCapsuleSection(
                    stats = monthStats,
                    isPrimary = index == 0,
                    onTimeClick = { onTime(monthStats.month) },
                    onArtistClick = { onArtists(monthStats.month) },
                    onSongClick = { onSongs(monthStats.month) },
                )
            }

            item(key = "sound_capsule_final_note") {
                FinalNoteSection()
            }
        }
    }
}

@Composable
private fun MonthCapsuleSection(
    stats: SoundCapsuleStats,
    isPrimary: Boolean,
    onTimeClick: () -> Unit,
    onArtistClick: () -> Unit,
    onSongClick: () -> Unit,
) {
    val monthTagSuffix = "${stats.month.year}_${stats.month.monthValue}"
    val timeTag = if (isPrimary) "sound_capsule_time_card" else "sound_capsule_time_card_$monthTagSuffix"
    val artistTag = if (isPrimary) "sound_capsule_top_artist_card" else "sound_capsule_top_artist_card_$monthTagSuffix"
    val songTag = if (isPrimary) "sound_capsule_top_song_card" else "sound_capsule_top_song_card_$monthTagSuffix"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpotifyMonthHeading(month = stats.month)

        Column(
            Modifier
                .fillMaxWidth()
                .testTag(timeTag)
                .card(onTimeClick)
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Label("Time listened", size = 12, weight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Label("${fmt(stats.totalMinutes)} minutes", 32, FontWeight.ExtraBold, Green)
        }

        val artist = stats.topArtists.firstOrNull()
        val song = stats.topSongs.firstOrNull()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HighlightCard(
                label = "Top artist",
                name = artist?.name ?: "–",
                nameColor = if (artist != null) ArtistAccentBlue else Muted,
                url = artist?.artworkUrl,
                shape = CircleShape,
                seed = stats.month.monthValue,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(artistTag),
                onClick = onArtistClick,
            )
            HighlightCard(
                label = "Top song",
                name = song?.title ?: "–",
                nameColor = if (song != null) SongAccentYellow else Muted,
                url = song?.artworkUrl,
                shape = RoundedCornerShape(6.dp),
                seed = stats.month.monthValue + 3,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(songTag),
                onClick = onSongClick,
            )
        }
    }
}

@Composable
private fun FinalNoteSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 28.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "The final note",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Every song comes to an end. So, do these listening stats.",
            color = Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun ArtistsScreen(
    stats: SoundCapsuleStats,
    onBack: () -> Unit,
    onOpenArtist: (artistId: String, artistName: String) -> Unit = { _, _ -> },
) {
    CapsuleScaffold("Top 5 artists", onBack) {
        DetailMonthHeader(stats.month, "Top 5")
        val top = stats.topArtists
        if (top.isEmpty()) { EmptyNote(); return@CapsuleScaffold }
        val first = top.first()
        HeroCard(
            label = "#1 · Top artist",
            title = first.name,
            big = "${fmt(first.minutes)} minutes",
            sub = "${first.plays} plays",
            onClick = { onOpenArtist(first.artistId, first.name) },
            testTag = "sound_capsule_hero_artist",
        ) {
            CapsuleArtwork(first.artworkUrl, 0, CircleShape, Modifier.size(80.dp))
        }
        if (top.size > 1) {
            RankedList(top.drop(1).mapIndexed { i, a ->
                RankRow(
                    rank = i + 2,
                    title = a.name,
                    sub = "${fmt(a.minutes)} min",
                    right = "${a.plays} plays",
                    fraction = a.minutes.toFloat() / first.minutes.coerceAtLeast(1),
                    url = a.artworkUrl,
                    round = true,
                    onClick = { onOpenArtist(a.artistId, a.name) },
                )
            })
        }
        val sum = top.sumOf { it.minutes }
        val totalPlays = top.sumOf { it.plays }
        Footer("Together your top ${top.size} artists were played ${fmt(totalPlays)} times for ${fmt(sum)} minutes.")
    }
}

@Composable
private fun SongsScreen(
    stats: SoundCapsuleStats,
    onBack: () -> Unit,
) {
    CapsuleScaffold("Top 5 songs", onBack) {
        DetailMonthHeader(stats.month, "Top 5")
        val top = stats.topSongs
        if (top.isEmpty()) { EmptyNote(); return@CapsuleScaffold }
        val first = top.first()
        HeroCard(
            label = "#1 · Top song",
            title = first.title,
            big = "${first.plays} plays",
            sub = "${first.artistName} · ${fmt(first.minutes)} minutes",
        ) {
            CapsuleArtwork(first.artworkUrl, 2, RoundedCornerShape(6.dp), Modifier.size(80.dp))
        }
        if (top.size > 1) {
            RankedList(top.drop(1).mapIndexed { i, s ->
                RankRow(
                    rank = i + 2,
                    title = s.title,
                    sub = "${s.artistName} · ${fmt(s.minutes)} min",
                    right = "${s.plays} plays",
                    fraction = s.plays.toFloat() / first.plays.coerceAtLeast(1),
                    url = s.artworkUrl,
                    round = false,
                )
            })
        }
        Footer("Your top ${top.size} songs were played ${fmt(top.sumOf { it.plays })} times for ${fmt(top.sumOf { it.minutes })} minutes.")
    }
}

@Composable
private fun TimeScreen(
    stats: SoundCapsuleStats,
    onBack: () -> Unit,
) {
    CapsuleScaffold("Time listened", onBack) {
        DetailMonthHeader(stats.month, null)
        Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Label("Time listened")
            Label("${fmt(stats.totalMinutes)} minutes", 32, FontWeight.ExtraBold, Green)
        }
        if (stats.totalMinutes == 0) { EmptyNote(); return@CapsuleScaffold }

        val longest = if (stats.longestDay > 0) {
            val d = stats.month.atDay(stats.longestDay)
            "${d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault())} ${stats.longestDay}"
        } else "–"
        val dailyVal = "${fmt(stats.dailyAverage)} min"
        val weeklyVal = "${fmt(stats.weeklyAverage)} min"
        val longestVal = "${fmt(stats.longestDayMinutes)} min"
        val tileValueSize = if (maxOf(dailyVal.length, weeklyVal.length, longestVal.length) > 9) 14 else 16
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile("Daily average", dailyVal, "per day", Modifier.weight(1f), tileValueSize)
            StatTile("Weekly average", weeklyVal, "per week", Modifier.weight(1f), tileValueSize)
            StatTile("Longest day", longestVal, longest, Modifier.weight(1f), tileValueSize)
        }

        // Day by day bars with dynamic vertical Y-axis
        val maxBar = stats.dailyMinutes.maxOrNull() ?: 0
        val axisMax = maxOf(maxBar, 2)
        val midTick = (axisMax / 2.0).roundToInt()
        val yAxisWidth = 36.dp
        val yAxisGap = 6.dp
        Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
            Label("Day by day", 16, FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(yAxisWidth), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = "mins",
                    color = Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().height(110.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Vertical Y-axis labels (top, mid, 0) adjusted to longest bar
                Column(
                    modifier = Modifier
                        .width(yAxisWidth)
                        .fillMaxHeight()
                        .testTag("sound_capsule_day_chart_yaxis"),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    YAxisLabel(formatYAxisMinutes(axisMax))
                    YAxisLabel(formatYAxisMinutes(midTick))
                    YAxisLabel("0")
                }
                Spacer(Modifier.width(yAxisGap))
                // Vertical Y-axis line
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Track)
                )
                Spacer(Modifier.width(yAxisGap))
                // Daily bars
                Row(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    stats.dailyMinutes.forEach { m ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight((m.toFloat() / axisMax).coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (m == maxBar && maxBar > 0) Green else DarkGreen)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(yAxisWidth + yAxisGap * 2 + 1.dp))
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(1, 8, 15, 22, 29)
                        .filter { it <= stats.dailyMinutes.size }
                        .forEach { Label("$it", 11, FontWeight.Normal, Muted) }
                }
            }
        }

        // Time of day
        val maxBlock = (stats.timeBlocks.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Label("When you listened", 16, FontWeight.ExtraBold)
            }
            stats.timeBlocks.forEach { b ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Label(b.block.label, 15)
                        Label("${fmt(b.minutes)} min", 14, FontWeight.ExtraBold)
                    }
                    Bar(b.minutes.toFloat() / maxBlock, if (b.minutes == maxBlock) Green else DarkGreen, 8)
                    Label("${b.sharePercent}% of listening · ${b.minutesPerDay} min a day", 12, FontWeight.Normal, Muted)
                }
            }
        }

        // Every day
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("Every day", 16, FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            stats.dailyMinutes.withIndex().chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    pair.forEach { (i, m) ->
                        val day = i + 1
                        val wd = stats.month.atDay(day).dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault())
                        Row(
                            Modifier.weight(1f).height(28.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Label("$wd $day", 13, FontWeight.Normal, Muted)
                            Label("$m min", 13)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ================= building blocks =================

@Composable
fun CapsuleArtwork(url: String?, seed: Int, shape: Shape, modifier: Modifier = Modifier) {
    val colors = Gradients[seed.mod(Gradients.size)]
    val context = LocalContext.current
    Box(modifier.clip(shape).background(Brush.linearGradient(colors))) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = remember(url) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .crossfade(true)
                        .build()
                },
                contentDescription = "Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CapsuleScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val bottomDockClearance = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 168.dp
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .testTag("sound_capsule_back"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Label(title, 16, FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = bottomDockClearance,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SpotifyMonthHeading(
    month: YearMonth,
    modifier: Modifier = Modifier,
) {
    val monthName = month.month.getDisplayName(JTextStyle.FULL, Locale.getDefault())
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) {
                append(monthName)
            }
            append(" ")
            withStyle(SpanStyle(color = YearMuted, fontWeight = FontWeight.Bold)) {
                append("${month.year}")
            }
        },
        fontSize = 20.sp,
        modifier = modifier,
    )
}

@Composable
private fun DetailMonthHeader(
    month: YearMonth,
    subtitle: String?,
) {
    var showInfo by remember { mutableStateOf(false) }
    Row(
        Modifier.heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpotifyMonthHeading(month = month)
        Spacer(Modifier.width(8.dp))
        if (subtitle != null) {
            Label(subtitle, 14, FontWeight.SemiBold, Muted)
        } else {
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About Sound Capsule",
                    tint = Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = CardBg,
            title = { Label("Your Sound Capsule", 18, FontWeight.ExtraBold) },
            text = {
                Label(
                    "Tracks your active listening time (pauses excluded). A song counts as a play after 30 seconds of active playback.",
                    13,
                    FontWeight.Normal,
                    Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it", color = Green, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

@Composable
private fun HighlightCard(
    label: String,
    name: String,
    nameColor: Color,
    url: String?,
    shape: Shape,
    seed: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.card(onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Label(label, 12, weight = FontWeight.SemiBold, color = Color.White)
            Label(
                text = name,
                size = 15,
                weight = FontWeight.ExtraBold,
                color = nameColor,
                maxLines = 2,
                modifier = Modifier.heightIn(min = 38.dp).padding(top = 3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            CapsuleArtwork(url, seed, shape, Modifier.size(104.dp))
        }
    }
}

@Composable
private fun HeroCard(
    label: String,
    title: String,
    big: String,
    sub: String,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    art: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (testTag != null) it.testTag(testTag) else it }
            .card(onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        art()
        Column(Modifier.weight(1f)) {
            Label(label, 12, color = Muted)
            Label(title, 20, FontWeight.ExtraBold, maxLines = 2)
            Label(big, 24, FontWeight.ExtraBold, Green)
            Label(sub, 13, FontWeight.Normal, Muted)
        }
    }
}

private data class RankRow(
    val rank: Int,
    val title: String,
    val sub: String,
    val right: String,
    val fraction: Float,
    val url: String?,
    val round: Boolean,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun RankedList(rows: List<RankRow>) {
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (r.onClick != null) {
                            mod.clip(RoundedCornerShape(8.dp)).clickable(onClick = r.onClick).padding(vertical = 2.dp)
                        } else mod
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("${r.rank}", 16, FontWeight.ExtraBold, Muted, Modifier.width(18.dp))
                    Spacer(Modifier.width(12.dp))
                    CapsuleArtwork(
                        r.url,
                        r.rank,
                        if (r.round) CircleShape else RoundedCornerShape(4.dp),
                        Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Label(r.title, 15, maxLines = 1)
                        Label(r.sub, 13, FontWeight.Normal, Muted, maxLines = 1)
                    }
                    Label(r.right, 14, FontWeight.ExtraBold, Green)
                }
                Bar(r.fraction, Green)
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier,
    valueFontSizeSp: Int = 16,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .card()
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.2).sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = valueFontSizeSp.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.3).sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = sub.ifEmpty { "–" },
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.SansSerif,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun YAxisLabel(text: String) {
    Text(
        text = text,
        color = Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = (-0.2).sp,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun Bar(fraction: Float, fill: Color, heightDp: Int = 4) {
    val h = heightDp.dp
    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h / 2)).background(Track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(h).background(fill))
    }
}

@Composable
private fun Footer(text: String) {
    Label(
        text,
        13,
        FontWeight.Normal,
        Muted,
        Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun EmptyNote() {
    Label(
        "No listening recorded for this month yet.",
        14,
        FontWeight.Normal,
        Muted,
        Modifier.fillMaxWidth().card().padding(16.dp),
    )
}

@Composable
private fun Label(
    text: String,
    size: Int = 13,
    weight: FontWeight = FontWeight.SemiBold,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun Modifier.card(onClick: (() -> Unit)? = null): Modifier {
    val base = this.clip(RoundedCornerShape(10.dp)).background(CardBg)
    return if (onClick != null) base.clickable(onClick = onClick) else base
}

private fun fmt(n: Int): String = "%,d".format(n)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SoundCapsulePreview() {
    val oct = sampleSoundCapsule()
    val sep = oct.copy(month = YearMonth.of(2026, 9), totalMinutes = 9952)
    SoundCapsuleHost(
        stats = oct,
        availableMonths = listOf(oct.month, sep.month),
        monthlyFeed = listOf(oct, sep),
        onMonthSelected = {},
        onExit = {},
    )
}
