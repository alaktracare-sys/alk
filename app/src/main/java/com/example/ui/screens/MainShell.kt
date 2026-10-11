package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.screens.playlist.AddSongToPlaylistScreen
import com.example.ui.screens.playlist.PlaylistScreen
import com.example.ui.screens.profile.FullListeningHistorySheet
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.search.SearchScreen
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class Screen {
    HOME, SEARCH, LIBRARY, PLAYLIST, ADD_SONGS, PROFILE, EQUALIZER, HISTORY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    repository: MusicRepository,
    audioController: AudioController,
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

    // Playlist selector sheet (for "Add to Playlist" action)
    var songForPlaylistSelection by remember { mutableStateOf<Song?>(null) }
    var availablePlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val playbackState by audioController.playbackState.collectAsState()
    val isOnline by remember { com.example.util.NetworkMonitor.observeNetwork(context) }
        .collectAsState(initial = com.example.util.NetworkMonitor.isOnline(context))

    LaunchedEffect(isOnline) {
        if (isOnline && repository.authPreferences.hasPendingOfflineActions()) {
            val count = repository.syncOfflineActions()
            if (count > 0) {
                Toast.makeText(context, "Synced $count offline change(s) with server", Toast.LENGTH_SHORT).show()
            }
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
        if (currentScreen != Screen.PLAYLIST && currentScreen != Screen.ADD_SONGS) {
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
        Screen.PLAYLIST,
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
        // Bottom navigation gradient scrim (opaque at bottom to transparent at top)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, AlaktraBackground)
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
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
                                val isForward = (targetState == Screen.PLAYLIST || targetState == Screen.PROFILE || targetState == Screen.EQUALIZER || targetState == Screen.ADD_SONGS) &&
                                        (initialState == Screen.HOME || initialState == Screen.SEARCH || initialState == Screen.LIBRARY)
                                val isBackward = (initialState == Screen.PLAYLIST || initialState == Screen.PROFILE || initialState == Screen.EQUALIZER || initialState == Screen.ADD_SONGS) &&
                                        (targetState == Screen.HOME || targetState == Screen.SEARCH || targetState == Screen.LIBRARY)

                                when {
                                    isForward -> {
                                        (slideInHorizontally(
                                            initialOffsetX = { (it * 0.12f).toInt() },
                                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                                        ) + fadeIn(tween(240))).togetherWith(
                                            slideOutHorizontally(
                                                targetOffsetX = { (-it * 0.12f).toInt() },
                                                animationSpec = tween(240, easing = FastOutSlowInEasing)
                                            ) + fadeOut(tween(200))
                                        )
                                    }
                                    isBackward -> {
                                        (slideInHorizontally(
                                            initialOffsetX = { (-it * 0.12f).toInt() },
                                            animationSpec = tween(260, easing = FastOutSlowInEasing)
                                        ) + fadeIn(tween(240))).togetherWith(
                                            slideOutHorizontally(
                                                targetOffsetX = { (it * 0.12f).toInt() },
                                                animationSpec = tween(240, easing = FastOutSlowInEasing)
                                            ) + fadeOut(tween(200))
                                        )
                                    }
                                    else -> {
                                        (fadeIn(tween(200)) + scaleIn(
                                            initialScale = 0.985f,
                                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                                        )).togetherWith(
                                            fadeOut(tween(160))
                                        )
                                    }
                                }
                            },
                            label = "ScreenTransition",
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
                                        onNavigateToDownloadedSongs = { openPlaylist(Playlist.ID_DOWNLOADED, "Downloaded Songs") }
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

        // 🎵 Apple Music Style Floating Dock (Floating MiniPlayer + Circular Translucent Capsule Navigation Bar)
        val showMiniPlayer = !showFullPlayer && playbackState.currentSong != null && currentScreen in screensWithBottomNav
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
                MiniPlayer(
                    playbackState = playbackState,
                    onTogglePlay = { audioController.togglePlayPause() },
                    onNext = { audioController.next() },
                    onPrevious = { audioController.previous() },
                    onLikeToggle = {
                        playbackState.currentSong?.let { song ->
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
                        .widthIn(max = 240.dp)
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
                    color = Color(0xFF161822).copy(alpha = 0.99f),
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.16f)),
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isHomeSelected = (currentScreen == Screen.HOME && currentTab == 0) ||
                            (currentScreen == Screen.PLAYLIST && playlistOriginTab == 0) ||
                            (currentScreen == Screen.PROFILE && (playlistOriginTab == 0 || currentTab == 0))

                        val isSearchSelected = (currentScreen == Screen.SEARCH && currentTab == 1) ||
                            (currentScreen == Screen.PLAYLIST && playlistOriginTab == 1)

                        val isLibrarySelected = (currentScreen == Screen.LIBRARY && currentTab == 2) ||
                            (currentScreen == Screen.PLAYLIST && playlistOriginTab == 2)

                        CapsuleNavItem(
                            selected = isHomeSelected,
                            onClick = {
                                currentTab = 0
                                currentScreen = Screen.HOME
                            },
                            icon = Icons.Outlined.Home,
                            selectedIcon = Icons.Default.Home,
                            label = "Home"
                        )

                        CapsuleNavItem(
                            selected = isSearchSelected,
                            onClick = {
                                currentTab = 1
                                currentScreen = Screen.SEARCH
                            },
                            icon = Icons.Outlined.Search,
                            selectedIcon = Icons.Default.Search,
                            label = "Search"
                        )

                        CapsuleNavItem(
                            selected = isLibrarySelected,
                            onClick = {
                                currentTab = 2
                                currentScreen = Screen.LIBRARY
                            },
                            icon = Icons.Outlined.LibraryMusic,
                            selectedIcon = Icons.Default.LibraryMusic,
                            label = "Your Library"
                        )
                    }
                }
            }
        }

        // Full Screen Player Modal / Overlay
        AnimatedVisibility(
            visible = showFullPlayer,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val downloadProgressMap by repository.downloadManager.downloadProgress.collectAsState()
            var isDownloaded by remember(playbackState.currentSong?.id) { mutableStateOf(false) }
            LaunchedEffect(playbackState.currentSong?.id) {
                playbackState.currentSong?.let { s ->
                    isDownloaded = repository.downloadManager.isDownloaded(s.id)
                }
            }
            val currentDownloadProgress = playbackState.currentSong?.let { downloadProgressMap[it.id] }

            PlayerScreen(
                audioController = audioController,
                playbackState = playbackState,
                isDownloaded = isDownloaded,
                downloadProgress = currentDownloadProgress,
                onNavigateToEqualizer = {
                    showFullPlayer = false
                    currentScreen = Screen.EQUALIZER
                },
                onToggleDownload = {
                    playbackState.currentSong?.let { song ->
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
                    playbackState.currentSong?.let { song ->
                        scope.launch {
                            availablePlaylists = repository.getPlaylists()
                            songForPlaylistSelection = song
                        }
                    }
                },
                onClose = { showFullPlayer = false },
                onLikeToggle = {
                    playbackState.currentSong?.let { song ->
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
                            items(availablePlaylists) { pl ->
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
    val pillBg by animateColorAsState(
        targetValue = if (selected) activeColor.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(220),
        label = "pill_bg"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(pillBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
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
                modifier = Modifier.size(20.dp)
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(200)) + expandHorizontally(tween(200)),
                exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150))
            ) {
                Row {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}


