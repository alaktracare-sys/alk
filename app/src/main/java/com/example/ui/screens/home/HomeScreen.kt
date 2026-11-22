package com.example.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.AudioController
import com.example.audio.ContextType
import com.example.audio.PlaybackContext
import com.example.data.repository.MusicRepository
import com.example.model.DailyPlaylist
import com.example.model.Playlist
import com.example.model.Song
import com.example.ui.components.DailyPlaylistCollageCover
import com.example.ui.components.HomeLoadingSkeleton
import com.example.ui.components.LocalCacheIndexWarmed
import com.example.ui.components.LocalIsOnline
import com.example.ui.components.LocalOfflinePlayableIds
import com.example.ui.components.SongCover
import com.example.ui.components.SongItemRow
import com.example.ui.components.SongListSkeleton
import com.example.ui.components.SongMenuBottomSheet
import com.example.ui.components.isSongPlayableInUi
import com.example.ui.components.parseDailyAccentColor
import com.example.ui.components.showOfflineUnplayableToast
import com.example.ui.responsive.WindowWidthSize
import com.example.ui.responsive.rememberWindowSizeInfo
import com.example.ui.screens.profile.AVATAR_PRESETS
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Calendar

private val LikedShortcutBrush = Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF06B6D4)))
private val DownloadedShortcutBrush = Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF14B8A6)))
private val MadeForYouCardBrush = Brush.verticalGradient(listOf(Color(0xFF1F2236), AlaktraCard))
private val PlaylistPlaceholderBrush = Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF1E1B4B)))
private val DailyCardScrimBrush = Brush.verticalGradient(
    colors = listOf(
        Color.Black.copy(alpha = 0.25f),
        Color.Transparent,
        Color.Black.copy(alpha = 0.55f)
    )
)
private val QuickTileShape = RoundedCornerShape(8.dp)
private val ShortcutCardShape = RoundedCornerShape(10.dp)
private val CardCornerShape11 = RoundedCornerShape(11.dp)
private val CardCornerShape14 = RoundedCornerShape(14.dp)
private val CardCornerShape16 = RoundedCornerShape(16.dp)
private val BadgeShape6 = RoundedCornerShape(6.dp)
private val HeaderLogoShape = RoundedCornerShape(20.dp)

@Composable
fun HomeScreen(
    repository: MusicRepository,
    audioController: AudioController,
    onNavigateToProfile: () -> Unit,
    onNavigateToEqualizer: () -> Unit = {},
    onNavigateToPlaylistSelect: (Song) -> Unit,
    onNavigateToPlaylist: (Int, String) -> Unit = { _, _ -> },
    onNavigateToLikedSongs: () -> Unit = {},
    onNavigateToDownloadedSongs: () -> Unit = {},
    onViewSongArtist: (Song) -> Unit = {}
) {
    val context = LocalContext.current
    val isOnline = LocalIsOnline.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val oneScreenTrackCount = remember(maxHeight) {
            (maxHeight / 72.dp).toInt().coerceIn(8, 18)
        }
        val listState = rememberLazyListState()
        var songs by remember { mutableStateOf(repository.peekCachedSongsPage(oneScreenTrackCount)) }
        var discoverSongs by remember { mutableStateOf(repository.peekDiscoverSongs(6)) }
        var hasMoreSongs by remember { mutableStateOf(true) }
        var isLoadingMore by remember { mutableStateOf(false) }
        var playlists by remember { mutableStateOf(repository.peekCachedPlaylists()) }
        var dailyPlaylists by remember { mutableStateOf(repository.peekCachedDailyPlaylists()) }
        var isLoading by remember { mutableStateOf(songs.isEmpty() && dailyPlaylists.isEmpty() && isOnline) }
        var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
        val scope = rememberCoroutineScope()

        val playbackState by audioController.stablePlaybackState.collectAsState()
        val downloadProgress by repository.downloadManager.downloadProgress.collectAsState()

        val user = remember { repository.authPreferences.getUser() }

        val greeting = remember {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            when (hour) {
                in 5..11 -> "Good morning"
                in 12..17 -> "Good afternoon"
                else -> "Good evening"
            }
        }

        fun refreshFromServerInBackground(forceRefresh: Boolean = false) {
            if (forceRefresh) {
                repository.markHomeRefreshed()
            }
            scope.launch {
                try {
                    val freshDaily = repository.getDailyPlaylists(forceRefresh = forceRefresh)
                    if (freshDaily.isNotEmpty() && freshDaily != dailyPlaylists) {
                        dailyPlaylists = freshDaily
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            scope.launch {
                try {
                    val windowLimit = songs.size.coerceAtLeast(oneScreenTrackCount)
                    val freshPage = repository.getSongsPage(
                        limit = windowLimit,
                        offset = 0,
                        forceRefresh = forceRefresh
                    )
                    if (freshPage.songs.isNotEmpty() && freshPage.songs != songs) {
                        songs = freshPage.songs
                        hasMoreSongs = freshPage.hasMore
                    } else if (freshPage.songs.isNotEmpty()) {
                        hasMoreSongs = freshPage.hasMore
                    }
                    if (discoverSongs.isEmpty()) {
                        val freshDiscover = repository.getCachedDiscoverSongs(6)
                        if (freshDiscover.isNotEmpty()) {
                            discoverSongs = freshDiscover
                        }
                    }
                    if (dailyPlaylists.isEmpty() && freshPage.songs.isNotEmpty()) {
                        val regenerated = repository.getDailyPlaylists(forceRefresh = false)
                        if (regenerated != dailyPlaylists) {
                            dailyPlaylists = regenerated
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                } finally {
                    isLoading = false
                }
            }
            scope.launch {
                try {
                    val freshPlaylists = repository.getPlaylists(forceRefresh = forceRefresh)
                    if (freshPlaylists != playlists) {
                        playlists = freshPlaylists
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }

        var wasOnline by remember { mutableStateOf(isOnline) }
        LaunchedEffect(isOnline) {
            if (!wasOnline && isOnline) {
                refreshFromServerInBackground(forceRefresh = true)
            }
            wasOnline = isOnline
        }

        LaunchedEffect(Unit) {
            if (songs.size < oneScreenTrackCount || playlists.isEmpty() || dailyPlaylists.isEmpty() || discoverSongs.isEmpty()) {
                try {
                    coroutineScope {
                        val cachedSongsDeferred = async { repository.getCachedSongsPage(limit = oneScreenTrackCount, offset = 0) }
                        val cachedDiscoverDeferred = async { repository.getCachedDiscoverSongs(limit = 6) }
                        val cachedPlaylistsDeferred = async { repository.getCachedPlaylists() }
                        val cachedDailyDeferred = async { repository.getCachedDailyPlaylists() }

                        val initialCached = cachedSongsDeferred.await()
                        val initialDiscover = cachedDiscoverDeferred.await()
                        val initialPlaylists = cachedPlaylistsDeferred.await()
                        val initialDaily = cachedDailyDeferred.await()

                        if (initialCached.isNotEmpty() && initialCached != songs) {
                            songs = initialCached
                        }
                        if (initialDiscover.isNotEmpty() && initialDiscover != discoverSongs) {
                            discoverSongs = initialDiscover
                        }
                        if (initialPlaylists.isNotEmpty() && initialPlaylists != playlists) {
                            playlists = initialPlaylists
                        }
                        if (initialDaily.isNotEmpty() && initialDaily != dailyPlaylists) {
                            dailyPlaylists = initialDaily
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            isLoading = songs.isEmpty() && dailyPlaylists.isEmpty() && isOnline

            if (!com.example.util.NetworkMonitor.isOnline(context)) {
                isLoading = false
                return@LaunchedEffect
            }

            // Let the screen transition finish before starting background network/Room sync
            delay(250L)
            refreshFromServerInBackground(forceRefresh = repository.shouldRefreshHomeInBackground())
        }

        val currentSongs by rememberUpdatedState(songs)
        val currentHasMore by rememberUpdatedState(hasMoreSongs)
        val currentLoadingMore by rememberUpdatedState(isLoadingMore)

        LaunchedEffect(listState, oneScreenTrackCount) {
            snapshotFlow {
                val list = currentSongs
                var maxIdx = -1
                for (info in listState.layoutInfo.visibleItemsInfo) {
                    val idKey = info.key as? Int ?: continue
                    val idx = list.indexOfFirst { it.id == idKey }
                    if (idx > maxIdx) maxIdx = idx
                }
                maxIdx to list.size
            }
                .distinctUntilChanged()
                .collect { (lastVisibleIdx, loadedSize) ->
                    if (!currentHasMore || currentLoadingMore || loadedSize <= 0) return@collect
                    val desiredCount = MusicRepository.computeTargetHomeLoadedCount(
                        lastVisibleSongIndex = lastVisibleIdx,
                        oneScreenTrackCount = oneScreenTrackCount
                    )
                    if (loadedSize < desiredCount) {
                        val needed = (desiredCount - loadedSize).coerceAtLeast(oneScreenTrackCount)
                        isLoadingMore = true
                        try {
                            val nextPage = repository.getSongsPage(
                                limit = needed,
                                offset = loadedSize,
                                forceRefresh = false
                            )
                            val latest = currentSongs
                            if (nextPage.songs.isNotEmpty()) {
                                val existingIds = latest.mapTo(HashSet()) { it.id }
                                val appended = nextPage.songs.filter { it.id !in existingIds }
                                if (appended.isNotEmpty()) {
                                    songs = latest + appended
                                }
                            }
                            hasMoreSongs = nextPage.hasMore && nextPage.songs.isNotEmpty()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        } finally {
                            isLoadingMore = false
                        }
                    }
                }
        }

        val windowSize = rememberWindowSizeInfo()
        val horizontalPad = windowSize.horizontalPadding
        val cardWidth = when (windowSize.widthSize) {
            WindowWidthSize.COMPACT -> 140.dp
            WindowWidthSize.MEDIUM -> 160.dp
            WindowWidthSize.EXPANDED -> 180.dp
        }
        val mfyCardWidth = (cardWidth * 1.15f).coerceIn(160.dp, 220.dp)
        val madeForYouSongs = remember(discoverSongs, songs) {
            if (discoverSongs.size > 2) discoverSongs.take(6) else songs.asReversed().take(6)
        }

        Scaffold(
            containerColor = AlaktraBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1000.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 140.dp)
                ) {
            // Alaktra Brand Header & Greeting
            item(key = "home_header", contentType = "home_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = horizontalPad, end = horizontalPad, bottom = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Alaktra Logo Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(HeaderLogoShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_app_icon_foreground),
                                contentDescription = "Alaktra Logo",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ALAKTRA",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 2.sp
                                ),
                                color = AlaktraTextPrimary
                            )
                        }

                        // Header Actions: History, Equalizer & Profile Buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            // Equalizer Button
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("equalizer_button")
                                    .clip(CircleShape)
                                    .background(AlaktraSurface)
                                    .border(1.dp, AlaktraBorder, CircleShape)
                                    .clickable(onClick = onNavigateToEqualizer)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Equalizer",
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Profile / Account Button
                            val userAvatarPreset = remember(user?.avatarUrl) {
                                AVATAR_PRESETS.firstOrNull { it.id == user?.avatarUrl }
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("profile_button")
                                    .clip(CircleShape)
                                    .background(
                                        if (userAvatarPreset != null) {
                                            Brush.linearGradient(userAvatarPreset.colors)
                                        } else {
                                            Brush.linearGradient(listOf(AlaktraSurface, AlaktraSurface))
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (userAvatarPreset != null) Color.White.copy(alpha = 0.3f) else AlaktraBorder,
                                        CircleShape
                                    )
                                    .clickable(onClick = onNavigateToProfile)
                            ) {
                                Icon(
                                    imageVector = userAvatarPreset?.icon ?: Icons.Default.Person,
                                    contentDescription = "Profile & Account",
                                    tint = if (userAvatarPreset != null) Color.White else AlaktraMint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 26.sp
                        ),
                        color = AlaktraTextPrimary
                    )

                    if (user != null && user.username.isNotBlank()) {
                        Text(
                            text = user.username,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = AlaktraMint
                        )
                    }
                }
            }

            // Loading Skeleton
            if (isLoading) {
                item(key = "home_loading", contentType = "home_loading") {
                    HomeLoadingSkeleton(horizontalPad = horizontalPad)
                }
            } else if (songs.isEmpty()) {
                // Empty state
                item(key = "home_empty", contentType = "home_empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(CardCornerShape16)
                                .background(AlaktraSurface)
                                .border(1.dp, AlaktraBorder, CardCornerShape16)
                                .padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = AlaktraMint,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No tracks available yet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Connect to your music server to stream your music library.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // Permanent System Playlists Shortcuts (Liked & Downloaded)
                item(key = "home_shortcuts", contentType = "home_shortcuts") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPad, vertical = 6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                onClick = onNavigateToLikedSongs,
                                shape = ShortcutCardShape,
                                color = AlaktraSurface,
                                border = BorderStroke(1.dp, AlaktraBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(LikedShortcutBrush)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = "Liked Songs",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Liked Songs",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Surface(
                                onClick = onNavigateToDownloadedSongs,
                                shape = ShortcutCardShape,
                                color = AlaktraSurface,
                                border = BorderStroke(1.dp, AlaktraBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(DownloadedShortcutBrush)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DownloadDone,
                                            contentDescription = "Downloaded Songs",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Downloaded",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Quick Access Adaptive Grid
                item(key = "home_quick_access", contentType = "home_quick_access") {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPad, vertical = 6.dp)
                    ) {
                        val columns = when {
                            maxWidth < 480.dp -> 2
                            maxWidth < 750.dp -> 3
                            maxWidth < 1000.dp -> 4
                            else -> 5
                        }
                        val rows = remember(songs, columns) {
                            songs.take(columns * 2).chunked(columns)
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            rows.forEach { rowItems ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    rowItems.forEach { songItem ->
                                        val isCurrent = playbackState.currentSong?.id == songItem.id
                                        QuickAccessTile(
                                            song = songItem,
                                            isPlaying = isCurrent && playbackState.isPlaying,
                                            onClick = { audioController.playSong(songItem) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    for (i in rowItems.size until columns) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Daily Mixes: 5 randomly generated 30-song playlists (saved per user per day)
                if (dailyPlaylists.isNotEmpty()) {
                    item(key = "home_daily_mixes", contentType = "home_daily_mixes") {
                        SectionHeader(
                            title = stringResource(R.string.daily_mixes_title),
                            subtitle = stringResource(R.string.daily_mixes_subtitle),
                            horizontalPad = horizontalPad
                        )

                        val dailyCardWidth = (cardWidth * 1.12f).coerceIn(154.dp, 200.dp)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPad),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(
                                items = dailyPlaylists,
                                key = { "daily_${it.date}_${it.slotIndex}" },
                                contentType = { "daily_playlist_card" }
                            ) { daily ->
                                val contextUri = "alaktra:daily:${daily.id}"
                                val isCurrentDaily = playbackState.context?.uri == contextUri
                                DailyPlaylistCard(
                                    dailyPlaylist = daily,
                                    isPlaying = isCurrentDaily && playbackState.isPlaying,
                                    onCardClick = { onNavigateToPlaylist(daily.id, daily.name) },
                                    onQuickPlayClick = {
                                        if (isCurrentDaily) {
                                            audioController.togglePlayPause()
                                        } else if (daily.songs.isNotEmpty()) {
                                            val ctx = PlaybackContext(
                                                uri = contextUri,
                                                type = ContextType.PLAYLIST,
                                                name = daily.name,
                                                trackIds = daily.songs.map { it.id },
                                                tracks = daily.songs
                                            )
                                            audioController.playContext(ctx, startIndex = 0, autoShuffle = false)
                                        }
                                    },
                                    cardWidth = dailyCardWidth
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // Made For You (Gradient Card Highlights)
                if (madeForYouSongs.size > 2) {
                    item(key = "home_made_for_you", contentType = "home_made_for_you") {
                        SectionHeader(title = "Made For You", subtitle = "Fresh discovery everyday", horizontalPad = horizontalPad)

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPad),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(
                                items = madeForYouSongs,
                                key = { "mfy_${it.id}" },
                                contentType = { "made_for_you_card" }
                            ) { song ->
                                MadeForYouCard(
                                    song = song,
                                    isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                                    onClick = { audioController.playSong(song) },
                                    cardWidth = mfyCardWidth
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // Playlists carousel if available
                if (playlists.isNotEmpty()) {
                    item(key = "home_playlists", contentType = "home_playlists") {
                        SectionHeader(title = "Your Playlists", horizontalPad = horizontalPad)

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPad),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(
                                items = playlists,
                                key = { "playlist_${it.id}" },
                                contentType = { "playlist_card" }
                            ) { pl ->
                                PlaylistCard(
                                    playlist = pl,
                                    onClick = { onNavigateToPlaylist(pl.id, pl.name) },
                                    cardWidth = cardWidth
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // Trending & All Tracks List
                item(key = "home_tracks_header", contentType = "home_tracks_header") {
                    SectionHeader(title = "All Tracks", horizontalPad = horizontalPad)
                }

                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id },
                    contentType = { _, _ -> "song_row" }
                ) { index, song ->
                    val isCurrent = playbackState.currentSong?.id == song.id
                    SongItemRow(
                        song = song,
                        isPlaying = isCurrent && playbackState.isPlaying,
                        downloadProgress = downloadProgress[song.id],
                        onSongClick = { audioController.playHomeAllTracks(songs, index, repository) },
                        onLikeToggle = {
                            scope.launch {
                                val isLiked = repository.toggleLike(song)
                                audioController.updateSongLiked(song.id, isLiked)
                                songs = songs.map { if (it.id == song.id) it.copy(isLiked = isLiked) else it }
                            }
                        },
                        onMoreClick = { selectedSongForMenu = song }
                    )
                }

                if (isLoadingMore) {
                    item(key = "home_tracks_loading_more", contentType = "home_tracks_loading_more") {
                        SongListSkeleton(
                            count = 2,
                            horizontalPadding = horizontalPad,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

    // Bottom sheet for more menu
    selectedSongForMenu?.let { song ->
        val isDownloaded = song.isDownloaded || (downloadProgress[song.id] == null && song.localPath != null)
        SongMenuBottomSheet(
            song = song,
            isDownloaded = isDownloaded,
            onDismiss = { selectedSongForMenu = null },
            onPlayNext = {
                audioController.playNext(song)
                Toast.makeText(context, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = {
                audioController.addToQueue(song)
                Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = { onNavigateToPlaylistSelect(song) },
            onViewArtist = { onViewSongArtist(song) },
            onToggleDownload = {
                scope.launch {
                    if (isDownloaded) {
                        repository.downloadManager.removeDownload(song.id)
                        songs = songs.map { if (it.id == song.id) it.copy(isDownloaded = false, localPath = null) else it }
                    } else {
                        repository.downloadManager.downloadSong(song)
                        val path = repository.downloadManager.getLocalPath(song.id)
                        songs = songs.map { if (it.id == song.id) it.copy(isDownloaded = true, localPath = path) else it }
                    }
                }
            }
        )
    }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    horizontalPad: androidx.compose.ui.unit.Dp = 20.dp
) {
    Column(modifier = Modifier.padding(start = horizontalPad, end = horizontalPad, bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            ),
            color = AlaktraTextPrimary
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AlaktraTextSecondary
            )
        }
    }
}

@Composable
private fun QuickAccessTile(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPlayableNow = isPlaying || isSongPlayableInUi(
        song = song,
        isOnline = LocalIsOnline.current,
        offlinePlayableIds = LocalOfflinePlayableIds.current,
        cacheIndexWarmed = LocalCacheIndexWarmed.current
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(58.dp)
            .clip(QuickTileShape)
            .background(if (isPlaying) Color(0xFF222436) else AlaktraSurface)
            .border(1.dp, if (isPlaying) AlaktraMint.copy(alpha = 0.5f) else AlaktraBorder, QuickTileShape)
            .clickable {
                if (isPlayableNow) onClick() else showOfflineUnplayableToast(context)
            }
            .graphicsLayer { alpha = if (isPlayableNow) 1f else 0.42f }
    ) {
        SongCover(
            imageUrl = song.coverUrl,
            size = 58.dp,
            cornerRadius = 8.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = song.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 13.sp
            ),
            color = if (isPlaying) AlaktraMint else AlaktraTextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isPlayableNow) 8.dp else 4.dp)
        )
        if (!isPlayableNow) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = "Unavailable offline",
                tint = AlaktraTextMuted,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(13.dp)
            )
        }
    }
}

@Composable
private fun DailyPlaylistCard(
    dailyPlaylist: DailyPlaylist,
    isPlaying: Boolean,
    onCardClick: () -> Unit,
    onQuickPlayClick: () -> Unit,
    cardWidth: androidx.compose.ui.unit.Dp = 156.dp
) {
    val accentColor = remember(dailyPlaylist.accentColorHex) {
        parseDailyAccentColor(dailyPlaylist.accentColorHex, AlaktraMint)
    }
    val trackCount = if (dailyPlaylist.songs.isNotEmpty()) dailyPlaylist.songs.size else dailyPlaylist.songCount

    val cardBrush = remember(accentColor) {
        Brush.verticalGradient(colors = listOf(accentColor.copy(alpha = 0.12f), AlaktraSurface))
    }

    Column(
        modifier = Modifier
            .width(cardWidth)
            .testTag("daily_playlist_card_${dailyPlaylist.slotIndex}")
            .clip(CardCornerShape16)
            .background(cardBrush)
            .border(
                width = 1.dp,
                color = if (isPlaying) accentColor.copy(alpha = 0.75f) else AlaktraBorder,
                shape = CardCornerShape16
            )
            .clickable(onClick = onCardClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            DailyPlaylistCollageCover(
                coverUrls = dailyPlaylist.previewCovers,
                accentColor = accentColor,
                cornerRadius = 11.dp,
                modifier = Modifier.fillMaxSize()
            )

            // Bottom subtle gradient scrim for crisp contrast on badges/buttons
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardCornerShape11)
                    .background(DailyCardScrimBrush)
            )

            // Top-left Daily Mix index pill
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(BadgeShape6)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .border(0.75.dp, accentColor.copy(alpha = 0.65f), BadgeShape6)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "MIX 0${dailyPlaylist.slotIndex}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    ),
                    color = accentColor
                )
            }

            // Bottom-right quick play button (48.dp touch target)
            IconButton(
                onClick = onQuickPlayClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(accentColor)
                    .testTag("daily_playlist_play_${dailyPlaylist.slotIndex}")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.daily_mix_play_cd, dailyPlaylist.name),
                    tint = Color(0xFF041C12),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = dailyPlaylist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = if (isPlaying) accentColor else AlaktraTextPrimary
        )

        Text(
            text = dailyPlaylist.subtitle.ifBlank { stringResource(R.string.daily_mix_refreshes_tomorrow) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = AlaktraTextSecondary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = "$trackCount songs • Daily",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = accentColor
            )
        }
    }
}

@Composable
private fun MadeForYouCard(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    cardWidth: androidx.compose.ui.unit.Dp = 160.dp
) {
    val context = LocalContext.current
    val isPlayableNow = isPlaying || isSongPlayableInUi(
        song = song,
        isOnline = LocalIsOnline.current,
        offlinePlayableIds = LocalOfflinePlayableIds.current,
        cacheIndexWarmed = LocalCacheIndexWarmed.current
    )
    Box(
        modifier = Modifier
            .width(cardWidth)
            .heightIn(min = 180.dp, max = 220.dp)
            .clip(CardCornerShape16)
            .background(MadeForYouCardBrush)
            .border(1.dp, AlaktraBorder, CardCornerShape16)
            .clickable {
                if (isPlayableNow) onClick() else showOfflineUnplayableToast(context)
            }
            .graphicsLayer { alpha = if (isPlayableNow) 1f else 0.42f }
            .padding(12.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .clip(BadgeShape6)
                        .background(AlaktraMint.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "DISCOVER",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = AlaktraMint
                    )
                }
                if (!isPlayableNow) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = "Unavailable offline",
                        tint = AlaktraTextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            SongCover(
                imageUrl = song.coverUrl,
                size = 72.dp,
                cornerRadius = 10.dp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isPlaying) AlaktraMint else AlaktraTextPrimary
            )
            Text(
                text = song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = AlaktraTextSecondary
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    cardWidth: androidx.compose.ui.unit.Dp = 130.dp
) {
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clip(CardCornerShape14)
            .background(AlaktraSurface)
            .border(1.dp, AlaktraBorder, CardCornerShape14)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ShortcutCardShape)
                .background(PlaylistPlaceholderBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.QueueMusic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = AlaktraTextPrimary
        )
        Text(
            text = "${playlist.songCount} tracks",
            style = MaterialTheme.typography.bodySmall,
            color = AlaktraTextSecondary
        )
    }
}

