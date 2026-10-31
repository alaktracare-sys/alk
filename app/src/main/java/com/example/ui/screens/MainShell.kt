package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.Playlist
import com.example.model.Song
import com.example.ui.components.MiniPlayer
import com.example.ui.responsive.rememberWindowSizeInfo
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.equalizer.EqualizerScreen
import com.example.ui.screens.fuse.FuseScreen
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.screens.playlist.AddSongToPlaylistScreen
import com.example.ui.screens.playlist.PlaylistScreen
import com.example.ui.screens.profile.FullListeningHistorySheet
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.search.SearchScreen
import com.example.ui.screens.pins.LocationPinsScreen
import com.example.ui.screens.pins.PinsSessionCache
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class Screen {
    HOME, SEARCH, LIBRARY, PINS, PLAYLIST, ADD_SONGS, FUSE, PROFILE, EQUALIZER, HISTORY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    repository: MusicRepository,
    audioController: AudioController,
    pendingFuseJoinCode: String? = null,
    onConsumePendingFuseJoinCode: () -> Unit = {},
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var playlistOriginScreen by remember { mutableStateOf(Screen.HOME) }
    var playlistOriginTab by remember { mutableIntStateOf(0) }
    var activePlaylistId by remember { mutableIntStateOf(1) }
    var activePlaylistName by remember { mutableStateOf("Playlist") }
    var showFullPlayer by remember { mutableStateOf(false) }
    var pinsRefreshTrigger by remember { mutableIntStateOf(0) }

    // Playlist selector sheet (for "Add to Playlist" action)
    var songForPlaylistSelection by remember { mutableStateOf<Song?>(null) }
    var availablePlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val playbackStateHolder = audioController.playbackState.collectAsState()
    val hasCurrentSong by remember {
        derivedStateOf { playbackStateHolder.value.currentSong != null }
    }
    val isOnline by remember { com.example.util.NetworkMonitor.observeNetwork(context) }
        .collectAsState(initial = com.example.util.NetworkMonitor.isOnline(context))

    LaunchedEffect(Unit) {
        PinsSessionCache.prewarm(context, repository)
    }

    LaunchedEffect(isOnline) {
        if (isOnline && repository.authPreferences.hasPendingOfflineActions()) {
            val count = repository.syncOfflineActions()
            if (count > 0) {
                Toast.makeText(context, "Synced $count offline change(s) with server", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(pendingFuseJoinCode) {
        if (!pendingFuseJoinCode.isNullOrBlank()) {
            showFullPlayer = false
            currentTab = 2
            currentScreen = Screen.FUSE
        }
    }

    val navigateBackFromPlaylist: () -> Unit = {
        currentScreen = playlistOriginScreen
        currentTab = playlistOriginTab
    }

    // Back handling
    BackHandler(enabled = showFullPlayer || currentScreen != Screen.HOME || currentTab != 0) {
        when {
            showFullPlayer -> showFullPlayer = false
            currentScreen == Screen.ADD_SONGS -> currentScreen = Screen.PLAYLIST
            currentScreen == Screen.PLAYLIST -> navigateBackFromPlaylist()
            currentScreen == Screen.FUSE -> {
                currentTab = 2
                currentScreen = Screen.LIBRARY
            }
            currentScreen == Screen.HISTORY -> {
                currentScreen = Screen.HOME
                currentTab = 0
            }
            currentScreen == Screen.PROFILE -> {
                currentScreen = Screen.HOME
                currentTab = 0
            }
            currentScreen == Screen.EQUALIZER -> {
                currentScreen = Screen.HOME
                currentTab = 0
            }
            currentTab != 0 -> {
                currentTab = 0
                currentScreen = Screen.HOME
            }
        }
    }

    fun openPlaylist(id: Int, name: String) {
        if (currentScreen == Screen.FUSE) {
            playlistOriginScreen = Screen.LIBRARY
            playlistOriginTab = 2
        } else if (currentScreen != Screen.PLAYLIST && currentScreen != Screen.ADD_SONGS) {
            playlistOriginScreen = currentScreen
            playlistOriginTab = currentTab
        }
        activePlaylistId = id
        activePlaylistName = name
        currentScreen = Screen.PLAYLIST
    }

    val windowSize = rememberWindowSizeInfo()
    val useNavRail = !windowSize.isCompact

    val screensWithBottomNav = listOf(
        Screen.HOME,
        Screen.SEARCH,
        Screen.LIBRARY,
        Screen.PINS,
        Screen.PLAYLIST,
        Screen.FUSE,
        Screen.PROFILE,
        Screen.EQUALIZER,
        Screen.HISTORY
    )
    val hasBottomBar = !useNavRail && !showFullPlayer && currentScreen in screensWithBottomNav

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlaktraBackground)
    ) {
        Scaffold(
            containerColor = Color.Transparent
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .consumeWindowInsets(PaddingValues(top = padding.calculateTopPadding()))
            ) {
                if (useNavRail && !showFullPlayer && currentScreen in screensWithBottomNav) {
                    NavigationRail(
                        containerColor = AlaktraSurface.copy(alpha = 0.25f),
                        contentColor = AlaktraTextPrimary,
                        header = {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraMint.copy(alpha = 0.15f))
                                    .border(1.dp, AlaktraMint.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Alaktra",
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .border(width = 0.5.dp, color = AlaktraBorder.copy(alpha = 0.30f))
                    ) {
                        NavigationRailItem(
                            selected = currentTab == 0 && currentScreen == Screen.HOME,
                            onClick = {
                                currentTab = 0
                                currentScreen = Screen.HOME
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 0 && currentScreen == Screen.HOME) Icons.Default.Home else Icons.Outlined.Home,
                                    contentDescription = "Home"
                                )
                            },
                            label = {
                                Text(
                                    "Home",
                                    fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = AlaktraMint,
                                selectedTextColor = AlaktraMint,
                                unselectedIconColor = AlaktraTextMuted,
                                unselectedTextColor = AlaktraTextMuted,
                                indicatorColor = AlaktraMint.copy(alpha = 0.15f)
                            )
                        )

                        NavigationRailItem(
                            selected = currentTab == 1 && currentScreen == Screen.SEARCH,
                            onClick = {
                                currentTab = 1
                                currentScreen = Screen.SEARCH
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 1 && currentScreen == Screen.SEARCH) Icons.Default.Search else Icons.Outlined.Search,
                                    contentDescription = "Search"
                                )
                            },
                            label = {
                                Text(
                                    "Search",
                                    fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = AlaktraMint,
                                selectedTextColor = AlaktraMint,
                                unselectedIconColor = AlaktraTextMuted,
                                unselectedTextColor = AlaktraTextMuted,
                                indicatorColor = AlaktraMint.copy(alpha = 0.15f)
                            )
                        )

                        NavigationRailItem(
                            selected = currentTab == 2 && currentScreen == Screen.LIBRARY,
                            onClick = {
                                currentTab = 2
                                currentScreen = Screen.LIBRARY
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 2 && currentScreen == Screen.LIBRARY) Icons.Default.LibraryMusic else Icons.Outlined.LibraryMusic,
                                    contentDescription = "Library"
                                )
                            },
                            label = {
                                Text(
                                    "Library",
                                    fontWeight = if (currentTab == 2) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = AlaktraMint,
                                selectedTextColor = AlaktraMint,
                                unselectedIconColor = AlaktraTextMuted,
                                unselectedTextColor = AlaktraTextMuted,
                                indicatorColor = AlaktraMint.copy(alpha = 0.15f)
                            )
                        )

                        NavigationRailItem(
                            selected = currentTab == 3 && currentScreen == Screen.PINS,
                            onClick = {
                                if (currentScreen == Screen.PINS && currentTab == 3) {
                                    pinsRefreshTrigger++
                                } else {
                                    currentTab = 3
                                    currentScreen = Screen.PINS
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == 3 && currentScreen == Screen.PINS) Icons.Default.Place else Icons.Outlined.Place,
                                    contentDescription = "Pins"
                                )
                            },
                            label = {
                                Text(
                                    "Pins",
                                    fontWeight = if (currentTab == 3) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = AlaktraMint,
                                selectedTextColor = AlaktraMint,
                                unselectedIconColor = AlaktraTextMuted,
                                unselectedTextColor = AlaktraTextMuted,
                                indicatorColor = AlaktraMint.copy(alpha = 0.15f)
                            )
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (slideInHorizontally(
                                    initialOffsetX = { (it * 0.2f * direction).toInt() },
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 320f
                                    )
                                ) + fadeIn(animationSpec = tween(200)) + scaleIn(
                                    initialScale = 0.95f,
                                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
                                )).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { (-it * 0.2f * direction).toInt() },
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = 320f
                                        )
                                    ) + fadeOut(animationSpec = tween(180))
                                ).using(SizeTransform(clip = false))
                            },
                            label = "PinterestScreenTransition",
                            modifier = Modifier.fillMaxSize()
                        ) { targetScreen ->
                            when (targetScreen) {
                                Screen.HOME -> {
                                    HomeScreen(
                                        repository = repository,
                                        audioController = audioController,
                                        onNavigateToProfile = { currentScreen = Screen.PROFILE },
                                        onNavigateToEqualizer = { currentScreen = Screen.EQUALIZER },
                                        onNavigateToPlaylistSelect = { song ->
                                            scope.launch {
                                                availablePlaylists = repository.getPlaylists()
                                                songForPlaylistSelection = song
                                            }
                                        },
                                        onNavigateToPlaylist = { id, name -> openPlaylist(id, name) },
                                        onNavigateToLikedSongs = { openPlaylist(Playlist.ID_LIKED, "Liked Songs") },
                                        onNavigateToDownloadedSongs = { openPlaylist(Playlist.ID_DOWNLOADED, "Downloaded Songs") }
                                    )
                                }

                                Screen.SEARCH -> {
                                    SearchScreen(
                                        repository = repository,
                                        audioController = audioController,
                                        onNavigateToPlaylistSelect = { song ->
                                            scope.launch {
                                                availablePlaylists = repository.getPlaylists()
                                                songForPlaylistSelection = song
                                            }
                                        }
                                    )
                                }

                                Screen.LIBRARY -> {
                                    LibraryScreen(
                                        repository = repository,
                                        onNavigateToPlaylist = { id, name -> openPlaylist(id, name) },
                                        onNavigateToLikedSongs = { openPlaylist(Playlist.ID_LIKED, "Liked Songs") },
                                        onNavigateToDownloadedSongs = { openPlaylist(Playlist.ID_DOWNLOADED, "Downloaded Songs") },
                                        onNavigateToFuse = {
                                            currentTab = 2
                                            currentScreen = Screen.FUSE
                                        }
                                    )
                                }

                                Screen.PINS -> {
                                    LocationPinsScreen(
                                        repository = repository,
                                        audioController = audioController,
                                        refreshTrigger = pinsRefreshTrigger
                                    )
                                }

                                Screen.PLAYLIST -> {
                                    PlaylistScreen(
                                        playlistId = activePlaylistId,
                                        playlistName = activePlaylistName,
                                        repository = repository,
                                        audioController = audioController,
                                        onBack = navigateBackFromPlaylist,
                                        onNavigateToAddSongs = { currentScreen = Screen.ADD_SONGS }
                                    )
                                }

                                Screen.ADD_SONGS -> {
                                    AddSongToPlaylistScreen(
                                        playlistId = activePlaylistId,
                                        repository = repository,
                                        onBack = { currentScreen = Screen.PLAYLIST }
                                    )
                                }

                                Screen.FUSE -> {
                                    FuseScreen(
                                        repository = repository,
                                        initialJoinCode = pendingFuseJoinCode,
                                        onConsumedInitialJoinCode = onConsumePendingFuseJoinCode,
                                        onBack = {
                                            currentTab = 2
                                            currentScreen = Screen.LIBRARY
                                        },
                                        onOpenPlaylist = { id, name -> openPlaylist(id, name) }
                                    )
                                }

                                Screen.PROFILE -> {
                                    ProfileScreen(
                                        repository = repository,
                                        audioController = audioController,
                                        onBack = {
                                            currentScreen = Screen.HOME
                                            currentTab = 0
                                        },
                                        onLogout = onLogout
                                    )
                                }

                                Screen.EQUALIZER -> {
                                    EqualizerScreen(
                                        audioController = audioController,
                                        onBack = {
                                            currentScreen = Screen.HOME
                                            currentTab = 0
                                        }
                                    )
                                }

                                Screen.HISTORY -> {
                                    val historyList by repository.listeningHistory.collectAsState(initial = emptyList())
                                    FullListeningHistorySheet(
                                        historyList = historyList,
                                        repository = repository,
                                        audioController = audioController,
                                        onDismiss = {
                                            currentScreen = Screen.HOME
                                            currentTab = 0
                                        }
                                    )
                                }
                            }
                        }

                    }
                }
            }
        }

        // Bottom navigation gradient scrim (transparent at top to pitch black at bottom)
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomScrimHeight = if (hasBottomBar) {
            navBarBottom + 84.dp
        } else {
            (navBarBottom + 48.dp).coerceAtLeast(72.dp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomScrimHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black)
                    )
                )
        )

        // 🎵 Apple Music Style Floating Dock (Floating MiniPlayer + Circular Translucent Capsule Navigation Bar)
        val showMiniPlayer = !showFullPlayer && hasCurrentSong && currentScreen in screensWithBottomNav
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Floating MiniPlayer Pill
            androidx.compose.animation.AnimatedVisibility(
                visible = showMiniPlayer,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(200)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(160))
            ) {
                val miniPlaybackState = playbackStateHolder.value
                MiniPlayer(
                    playbackState = miniPlaybackState,
                    onTogglePlay = { audioController.togglePlayPause() },
                    onNext = { audioController.next() },
                    onPrevious = { audioController.previous() },
                    onLikeToggle = {
                        miniPlaybackState.currentSong?.let { song ->
                            scope.launch {
                                val isLiked = repository.toggleLike(song)
                                audioController.updateSongLiked(song.id, isLiked)
                            }
                        }
                    },
                    onExpand = { showFullPlayer = true },
                    modifier = Modifier
                        .widthIn(max = 550.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // 2. Circular Translucent Capsule Navigation Bar
            if (hasBottomBar) {
                val navPillShape = RoundedCornerShape(36.dp)
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 3.dp)
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .height(58.dp)
                        .shadow(
                            elevation = 20.dp,
                            shape = navPillShape,
                            spotColor = Color.Black.copy(alpha = 0.70f),
                            ambientColor = Color.Black.copy(alpha = 0.50f)
                        ),
                    shape = navPillShape,
                    // Bottom Navigation Bar Transparency:
                    // Modify the alpha value below (0.0f = completely transparent, 1.0f = completely solid/opaque, 0.82f = translucent frosted)
                    color = Color(0xFF000000).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    tonalElevation = 0.dp
                ) {
                    val isHomeSelected = (currentScreen == Screen.HOME && currentTab == 0) ||
                        (currentScreen == Screen.PLAYLIST && playlistOriginTab == 0) ||
                        (currentScreen == Screen.PROFILE && (playlistOriginTab == 0 || currentTab == 0))

                    val isSearchSelected = (currentScreen == Screen.SEARCH && currentTab == 1) ||
                        (currentScreen == Screen.PLAYLIST && playlistOriginTab == 1)

                    val isLibrarySelected = (currentScreen == Screen.LIBRARY && currentTab == 2) ||
                        currentScreen == Screen.FUSE ||
                        (currentScreen == Screen.PLAYLIST && playlistOriginTab == 2)

                    val isPinsSelected = (currentScreen == Screen.PINS && currentTab == 3) ||
                        (currentScreen == Screen.PLAYLIST && playlistOriginTab == 3)

                    val activeIndex = when {
                        isHomeSelected -> 0
                        isSearchSelected -> 1
                        isLibrarySelected -> 2
                        isPinsSelected -> 3
                        else -> 0
                    }

                    val animatedIndex by animateFloatAsState(
                        targetValue = activeIndex.toFloat(),
                        animationSpec = spring(
                            dampingRatio = 0.80f,
                            stiffness = 280f
                        ),
                        label = "gel_pill_slide"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // The selected tab gets a wider slot (it must fit icon + label);
                        // the other tabs share the rest. The SAME weights drive both the Row
                        // and the pill, so the pill always sits exactly behind the selected tab.
                        val selectedWeight = 1.7f
                        val idx = animatedIndex.coerceIn(0f, 3f)
                        val w0 = 1f + (selectedWeight - 1f) * (1f - kotlin.math.abs(idx - 0f)).coerceIn(0f, 1f)
                        val w1 = 1f + (selectedWeight - 1f) * (1f - kotlin.math.abs(idx - 1f)).coerceIn(0f, 1f)
                        val w2 = 1f + (selectedWeight - 1f) * (1f - kotlin.math.abs(idx - 2f)).coerceIn(0f, 1f)
                        val w3 = 1f + (selectedWeight - 1f) * (1f - kotlin.math.abs(idx - 3f)).coerceIn(0f, 1f)
                        val weightSum = w0 + w1 + w2 + w3

                        fun weightAt(i: Int): Float = when (i) {
                            0 -> w0
                            1 -> w1
                            2 -> w2
                            else -> w3
                        }

                        fun slotStart(i: Int): Float = when (i) {
                            0 -> 0f
                            1 -> w0
                            2 -> w0 + w1
                            else -> w0 + w1 + w2
                        }

                        val from = idx.toInt().coerceIn(0, 3)
                        val to = (from + 1).coerceAtMost(3)
                        val fraction = idx - from

                        val startW = slotStart(from) * (1f - fraction) + slotStart(to) * fraction
                        val endW = (slotStart(from) + weightAt(from)) * (1f - fraction) +
                            (slotStart(to) + weightAt(to)) * fraction

                        // Sliding green pill (single-pass layout without SubcomposeLayout)
                        Box(
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val totalWidthPx = constraints.maxWidth.toFloat()
                                    val unitWidthPx = totalWidthPx / weightSum
                                    val insetPx = 2.dp.toPx()
                                    val pillOffsetPx = (unitWidthPx * startW + insetPx).toInt()
                                    val pillWidthPx = (unitWidthPx * (endW - startW) - insetPx * 2f).toInt().coerceAtLeast(0)
                                    val pillHeightPx = 46.dp.roundToPx().coerceAtMost(constraints.maxHeight)
                                    val placeable = measurable.measure(
                                        Constraints.fixed(pillWidthPx, pillHeightPx)
                                    )
                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                        placeable.placeRelative(
                                            x = pillOffsetPx,
                                            y = ((constraints.maxHeight - pillHeightPx) / 2).coerceAtLeast(0)
                                        )
                                    }
                                }
                                .clip(RoundedCornerShape(23.dp))
                                .background(AlaktraMint.copy(alpha = 0.22f))
                                .border(1.dp, AlaktraMint.copy(alpha = 0.45f), RoundedCornerShape(23.dp))
                        )

                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CapsuleNavItem(
                                selected = isHomeSelected,
                                onClick = {
                                    if (currentScreen != Screen.HOME || currentTab != 0) {
                                        currentTab = 0
                                        currentScreen = Screen.HOME
                                    }
                                },
                                icon = Icons.Outlined.Home,
                                selectedIcon = Icons.Default.Home,
                                label = "Home",
                                modifier = Modifier.weight(w0)
                            )

                            CapsuleNavItem(
                                selected = isSearchSelected,
                                onClick = {
                                    if (currentScreen != Screen.SEARCH || currentTab != 1) {
                                        currentTab = 1
                                        currentScreen = Screen.SEARCH
                                    }
                                },
                                icon = Icons.Outlined.Search,
                                selectedIcon = Icons.Default.Search,
                                label = "Search",
                                modifier = Modifier.weight(w1)
                            )

                            CapsuleNavItem(
                                selected = isLibrarySelected,
                                onClick = {
                                    if (currentScreen != Screen.LIBRARY || currentTab != 2) {
                                        currentTab = 2
                                        currentScreen = Screen.LIBRARY
                                    }
                                },
                                icon = Icons.Outlined.LibraryMusic,
                                selectedIcon = Icons.Default.LibraryMusic,
                                label = "Library",
                                modifier = Modifier.weight(w2)
                            )

                            CapsuleNavItem(
                                selected = isPinsSelected,
                                onClick = {
                                    if (currentScreen == Screen.PINS && currentTab == 3) {
                                        pinsRefreshTrigger++
                                    } else {
                                        currentTab = 3
                                        currentScreen = Screen.PINS
                                    }
                                },
                                icon = Icons.Outlined.Place,
                                selectedIcon = Icons.Default.Place,
                                label = "Pins",
                                modifier = Modifier.weight(w3)
                            )
                        }
                    }
                }
            }
        }

        // Pre-composite the active song's small shape wallpaper on a background CPU thread
        // (0 GPU VRAM used while closed, instant cache hit on Frame 1 when PlayerScreen opens)
        SongShapePrewarmer(
            context = context,
            playbackStateHolder = playbackStateHolder
        )

        // Full Screen Player Modal / Overlay
        AnimatedVisibility(
            visible = showFullPlayer,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val playerPlaybackState by remember {
                derivedStateOf { playbackStateHolder.value.copy(currentPositionMs = 0L) }
            }
            val downloadProgressMap by repository.downloadManager.downloadProgress.collectAsState()
            var isDownloaded by remember(playerPlaybackState.currentSong?.id) { mutableStateOf(false) }
            LaunchedEffect(playerPlaybackState.currentSong?.id) {
                playerPlaybackState.currentSong?.let { s ->
                    isDownloaded = repository.downloadManager.isDownloaded(s.id)
                }
            }
            val currentDownloadProgress = playerPlaybackState.currentSong?.let { downloadProgressMap[it.id] }

            PlayerScreen(
                audioController = audioController,
                playbackState = playerPlaybackState,
                isDownloaded = isDownloaded,
                downloadProgress = currentDownloadProgress,
                onNavigateToEqualizer = {
                    showFullPlayer = false
                    currentScreen = Screen.EQUALIZER
                },
                onToggleDownload = {
                    playerPlaybackState.currentSong?.let { song ->
                        scope.launch {
                            if (isDownloaded) {
                                repository.downloadManager.removeDownload(song.id)
                                isDownloaded = false
                                android.widget.Toast.makeText(context, "Download removed", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Downloading ${song.title}...", android.widget.Toast.LENGTH_SHORT).show()
                                repository.downloadManager.downloadSong(song)
                                isDownloaded = repository.downloadManager.isDownloaded(song.id)
                            }
                        }
                    }
                },
                onAddToPlaylist = {
                    playerPlaybackState.currentSong?.let { song ->
                        scope.launch {
                            availablePlaylists = repository.getPlaylists()
                            songForPlaylistSelection = song
                        }
                    }
                },
                onClose = { showFullPlayer = false },
                onLikeToggle = {
                    playerPlaybackState.currentSong?.let { song ->
                        scope.launch {
                            val isLiked = repository.toggleLike(song)
                            audioController.updateSongLiked(song.id, isLiked)
                        }
                    }
                }
            )
        }

        // Add to Playlist Selection Modal Bottom Sheet
        songForPlaylistSelection?.let { song ->
            ModalBottomSheet(
                onDismissRequest = { songForPlaylistSelection = null },
                containerColor = AlaktraCard,
                contentColor = AlaktraTextPrimary,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Add to playlist",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        ) {
                            items(
                                items = availablePlaylists,
                                key = { it.id },
                                contentType = { "playlist_picker_row" }
                            ) { pl ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                repository.addSongToPlaylist(pl.id, song.id)
                                                songForPlaylistSelection = null
                                            }
                                        }
                                        .padding(vertical = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = null,
                                        tint = AlaktraMint,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = pl.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = AlaktraTextPrimary
                                        )
                                        Text(
                                            text = "${pl.songCount} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AlaktraTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    val activeColor = AlaktraMint
    val inactiveColor = Color.White.copy(alpha = 0.60f)

    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = 280f
        ),
        label = "icon_scale"
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 260f
        ),
        label = "text_alpha"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else icon,
                contentDescription = label,
                tint = if (selected) activeColor else inactiveColor,
                modifier = Modifier.size(22.dp)
            )
            if (textAlpha > 0.01f) {
                Row(
                    modifier = Modifier.graphicsLayer {
                        alpha = textAlpha
                        scaleX = textAlpha
                    }
                ) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun SongShapePrewarmer(
    context: android.content.Context,
    playbackStateHolder: State<com.example.audio.PlaybackState>
) {
    val prewarmTarget by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            val song = s.currentSong ?: return@derivedStateOf null
            Triple(song.id, song.title, s.dominantColor)
        }
    }
    LaunchedEffect(prewarmTarget) {
        val (id, title, dominantColor) = prewarmTarget ?: return@LaunchedEffect
        com.example.ui.screens.player.ShapeAtlas.prewarmWallpaper(
            context = context,
            seedKey = "${id}_${title}",
            palette = com.example.ui.screens.player.buildShapePalette(dominantColor)
        )
    }
}
