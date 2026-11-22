package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.zIndex
import com.example.audio.AudioCache
import com.example.audio.AudioController
import com.example.data.fuse.FuseCodeCodec
import com.example.data.repository.MusicRepository
import com.example.model.Playlist
import com.example.model.Song
import com.example.ui.components.AlaktraModalBottomSheet
import com.example.ui.components.ArtistCreditsBottomSheet
import com.example.ui.components.BottomNavGradientScrim
import com.example.ui.components.LocalCacheIndexWarmed
import com.example.ui.components.LocalIsOnline
import com.example.ui.components.LocalOfflinePlayableIds
import com.example.ui.components.MiniPlayer
import com.example.ui.responsive.rememberWindowSizeInfo
import com.example.ui.screens.artist.ArtistProfileScreen
import com.example.ui.screens.artist.ArtistStudioScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.equalizer.EqualizerScreen
import com.example.ui.screens.fuse.FuseScreen
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.screens.playlist.AddSongToPlaylistScreen
import com.example.ui.screens.playlist.PlaylistScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.search.SearchScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.soundcapsule.SoundCapsuleHost
import com.example.soundcapsule.SoundCapsuleViewModel
import com.example.ui.screens.pins.LocationPinsScreen
import com.example.ui.screens.pins.PinsSessionCache
import com.example.ui.theme.*
import kotlinx.coroutines.launch

const val ROUTE_SOUND_CAPSULE = "sound_capsule"
const val ROUTE_ARTIST_PROFILE = "artist_profile"
const val ROUTE_ARTIST_STUDIO = "artist_studio"

enum class Screen {
    HOME, SEARCH, LIBRARY, PINS, PLAYLIST, ADD_SONGS, FUSE, CLUB, PROFILE, EQUALIZER, SOUND_CAPSULE, ARTIST_PROFILE, ARTIST_STUDIO
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
    var soundCapsuleOriginScreen by remember { mutableStateOf(Screen.LIBRARY) }
    var soundCapsuleOriginTab by remember { mutableIntStateOf(2) }
    var activePlaylistId by remember { mutableIntStateOf(1) }
    var activePlaylistName by remember { mutableStateOf("Playlist") }
    var activeClubId by remember { mutableStateOf<Int?>(null) }
    var activeArtistId by remember { mutableIntStateOf(0) }
    var activeArtistName by remember { mutableStateOf("") }
    var artistOriginScreen by remember { mutableStateOf(Screen.HOME) }
    var artistOriginTab by remember { mutableIntStateOf(0) }
    var artistStudioOriginScreen by remember { mutableStateOf(Screen.PROFILE) }
    var artistStudioOriginTab by remember { mutableIntStateOf(0) }
    var songForArtistCreditsSheet by remember { mutableStateOf<Song?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var pinsRefreshTrigger by remember { mutableIntStateOf(0) }

    // Playlist selector sheet (for "Add to Playlist" action — excludes non-editable Fuse playlists)
    var songForPlaylistSelection by remember { mutableStateOf<Song?>(null) }
    var availablePlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    val scope = rememberCoroutineScope()

    suspend fun loadEditableUserPlaylists(): List<Playlist> {
        return repository.getPlaylists().filter { pl ->
            pl.isEditable && !pl.isFuse && !FuseCodeCodec.isFusePlaylistName(pl.name)
        }
    }

    val stablePlaybackStateHolder = audioController.stablePlaybackState.collectAsState()
    val playbackStateHolder = audioController.playbackState.collectAsState()
    val hasCurrentSong by remember {
        derivedStateOf { stablePlaybackStateHolder.value.currentSong != null }
    }
    val isOnline by remember { com.example.util.NetworkMonitor.observeNetwork(context) }
        .collectAsStateWithLifecycle(initialValue = com.example.util.NetworkMonitor.isOnline(context))
    val offlinePlayableIds by AudioCache.playableOfflineSongIds.collectAsStateWithLifecycle()
    val cacheIndexWarmed by AudioCache.cacheIndexWarmedState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        AudioCache.warmUpCacheIndexAsync(context)
    }

    LaunchedEffect(isOnline) {
        if (isOnline && repository.authPreferences.hasPendingOfflineActions()) {
            try {
                val count = repository.syncOfflineActions()
                if (count > 0) {
                    Toast.makeText(context, "Synced $count offline change(s) with server", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            currentScreen == Screen.CLUB -> {
                currentTab = 2
                currentScreen = Screen.LIBRARY
            }
            currentScreen == Screen.PROFILE -> {
                currentScreen = Screen.HOME
                currentTab = 0
            }
            currentScreen == Screen.EQUALIZER -> {
                currentScreen = Screen.HOME
                currentTab = 0
            }
            currentScreen == Screen.SOUND_CAPSULE -> {
                currentScreen = soundCapsuleOriginScreen
                currentTab = soundCapsuleOriginTab
            }
            currentScreen == Screen.ARTIST_STUDIO -> {
                currentScreen = artistStudioOriginScreen
                currentTab = artistStudioOriginTab
            }
            currentScreen == Screen.ARTIST_PROFILE -> {
                currentScreen = artistOriginScreen
                currentTab = artistOriginTab
            }
            currentTab != 0 -> {
                currentTab = 0
                currentScreen = Screen.HOME
            }
        }
    }

    fun openSoundCapsule() {
        audioController.flushLiveSessionForUi()
        if (currentScreen != Screen.SOUND_CAPSULE) {
            soundCapsuleOriginScreen = currentScreen
            soundCapsuleOriginTab = currentTab
        }
        currentScreen = Screen.SOUND_CAPSULE
    }

    fun openArtistProfile(artistId: Int, artistName: String = "") {
        showFullPlayer = false
        if (currentScreen != Screen.ARTIST_PROFILE && currentScreen != Screen.ARTIST_STUDIO) {
            artistOriginScreen = currentScreen
            artistOriginTab = currentTab
        }
        activeArtistId = artistId
        activeArtistName = artistName
        currentScreen = Screen.ARTIST_PROFILE
    }

    fun openArtistByHint(artistIdHint: Int?, artistName: String) {
        scope.launch {
            val resolved = try {
                repository.resolveArtistId(artistIdHint = artistIdHint, artistNameHint = artistName) ?: (artistIdHint ?: 0)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                artistIdHint ?: 0
            }
            openArtistProfile(resolved, artistName)
        }
    }

    fun openArtistForSong(song: Song) {
        if (song.hasMultipleArtists) {
            songForArtistCreditsSheet = song
        } else {
            val credit = song.primaryArtistCredit
            openArtistByHint(credit?.id ?: song.primaryArtistId, credit?.name ?: song.primaryArtistName)
        }
    }

    fun openArtistStudio() {
        showFullPlayer = false
        if (currentScreen != Screen.ARTIST_STUDIO) {
            artistStudioOriginScreen = currentScreen
            artistStudioOriginTab = currentTab
        }
        currentScreen = Screen.ARTIST_STUDIO
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
        Screen.CLUB,
        Screen.PROFILE,
        Screen.EQUALIZER,
        Screen.SOUND_CAPSULE,
        Screen.ARTIST_PROFILE,
        Screen.ARTIST_STUDIO
    )
    val hasBottomBar = !useNavRail && !showFullPlayer && currentScreen in screensWithBottomNav

    CompositionLocalProvider(
        LocalIsOnline provides isOnline,
        LocalOfflinePlayableIds provides offlinePlayableIds,
        LocalCacheIndexWarmed provides cacheIndexWarmed
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlaktraBackground)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.statusBars
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .consumeWindowInsets(PaddingValues(top = padding.calculateTopPadding()))
            ) {
                if (useNavRail && !showFullPlayer && currentScreen in screensWithBottomNav) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(start = 12.dp, end = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val navPillShape = RoundedCornerShape(32.dp)
                        Surface(
                            modifier = Modifier
                                .width(70.dp)
                                .wrapContentHeight()
                                .shadow(
                                    elevation = 20.dp,
                                    shape = navPillShape,
                                    spotColor = Color.Black.copy(alpha = 0.70f),
                                    ambientColor = Color.Black.copy(alpha = 0.50f)
                                ),
                            shape = navPillShape,
                            color = Color(0xFF000000).copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            tonalElevation = 0.dp
                        ) {
                            val isRailHomeSelected = (currentScreen == Screen.HOME && currentTab == 0) ||
                                (currentScreen == Screen.PLAYLIST && playlistOriginTab == 0) ||
                                (currentScreen == Screen.PROFILE && (playlistOriginTab == 0 || currentTab == 0)) ||
                                (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 0)

                            val isRailSearchSelected = (currentScreen == Screen.SEARCH && currentTab == 1) ||
                                (currentScreen == Screen.PLAYLIST && playlistOriginTab == 1) ||
                                (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 1)

                            val isRailLibrarySelected = (currentScreen == Screen.LIBRARY && currentTab == 2) ||
                                currentScreen == Screen.FUSE ||
                                currentScreen == Screen.CLUB ||
                                (currentScreen == Screen.PLAYLIST && playlistOriginTab == 2) ||
                                (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 2)

                            val isRailPinsSelected = (currentScreen == Screen.PINS && currentTab == 3) ||
                                (currentScreen == Screen.PLAYLIST && playlistOriginTab == 3) ||
                                (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 3)

                            val activeRailIndex = when {
                                isRailHomeSelected -> 0
                                isRailSearchSelected -> 1
                                isRailLibrarySelected -> 2
                                isRailPinsSelected -> 3
                                else -> 0
                            }

                            val animatedRailIndex by animateFloatAsState(
                                targetValue = activeRailIndex.toFloat(),
                                animationSpec = spring(
                                    dampingRatio = 0.80f,
                                    stiffness = 280f
                                ),
                                label = "landscape_rail_slide"
                            )

                            val slotHeight = 58.dp
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 6.dp, horizontal = 5.dp),
                                contentAlignment = Alignment.TopStart
                            ) {
                                // Sliding mint gel pill
                                Box(
                                    modifier = Modifier
                                        .layout { measurable, constraints ->
                                            val slotHeightPx = slotHeight.toPx()
                                            val pillHeightPx = 52.dp.roundToPx()
                                            val pillWidthPx = constraints.maxWidth - 4.dp.roundToPx()
                                            val topOffsetPx = ((slotHeightPx * animatedRailIndex) + (slotHeightPx - pillHeightPx) / 2f).toInt()
                                            val placeable = measurable.measure(
                                                Constraints.fixed(pillWidthPx.coerceAtLeast(0), pillHeightPx)
                                            )
                                            layout(constraints.maxWidth, (slotHeightPx * 4).toInt()) {
                                                placeable.placeRelative(
                                                    x = 2.dp.roundToPx(),
                                                    y = topOffsetPx
                                                )
                                            }
                                        }
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(AlaktraMint.copy(alpha = 0.22f))
                                        .border(1.dp, AlaktraMint.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                                )

                                Column(
                                    modifier = Modifier.width(60.dp)
                                ) {
                                    LandscapePelletNavItem(
                                        selected = isRailHomeSelected,
                                        onClick = {
                                            if (currentScreen != Screen.HOME || currentTab != 0) {
                                                currentTab = 0
                                                currentScreen = Screen.HOME
                                            }
                                        },
                                        icon = Icons.Outlined.Home,
                                        selectedIcon = Icons.Default.Home,
                                        label = "Home",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(slotHeight)
                                    )

                                    LandscapePelletNavItem(
                                        selected = isRailSearchSelected,
                                        onClick = {
                                            if (currentScreen != Screen.SEARCH || currentTab != 1) {
                                                currentTab = 1
                                                currentScreen = Screen.SEARCH
                                            }
                                        },
                                        icon = Icons.Outlined.Search,
                                        selectedIcon = Icons.Default.Search,
                                        label = "Search",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(slotHeight)
                                    )

                                    LandscapePelletNavItem(
                                        selected = isRailLibrarySelected,
                                        onClick = {
                                            if (currentScreen != Screen.LIBRARY || currentTab != 2) {
                                                currentTab = 2
                                                currentScreen = Screen.LIBRARY
                                            }
                                        },
                                        icon = Icons.Outlined.LibraryMusic,
                                        selectedIcon = Icons.Default.LibraryMusic,
                                        label = "Library",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(slotHeight)
                                    )

                                    LandscapePelletNavItem(
                                        selected = isRailPinsSelected,
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(slotHeight)
                                    )
                                }
                            }
                        }
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
                        val saveableStateHolder = rememberSaveableStateHolder()
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                val isPrimaryNav = (targetState == Screen.HOME || targetState == Screen.SEARCH ||
                                    targetState == Screen.LIBRARY || targetState == Screen.PINS) &&
                                    (initialState == Screen.HOME || initialState == Screen.SEARCH ||
                                        initialState == Screen.LIBRARY || initialState == Screen.PINS ||
                                        targetState == Screen.HOME || targetState == Screen.SEARCH)
                                if (isPrimaryNav) {
                                    fadeIn(
                                        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing)
                                    ).togetherWith(
                                        fadeOut(
                                            animationSpec = tween(durationMillis = 90, easing = LinearOutSlowInEasing)
                                        )
                                    ).using(SizeTransform(clip = false))
                                } else {
                                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                    (slideInHorizontally(
                                        initialOffsetX = { (it * 0.10f * direction).toInt() },
                                        animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing)
                                    ) + fadeIn(
                                        animationSpec = tween(durationMillis = 140)
                                    )).togetherWith(
                                        fadeOut(
                                            animationSpec = tween(durationMillis = 110, easing = LinearOutSlowInEasing)
                                        )
                                    ).using(SizeTransform(clip = false))
                                }
                            },
                            label = "AlaktraScreenTransition",
                            modifier = Modifier.fillMaxSize()
                        ) { targetScreen ->
                            when (targetScreen) {
                                Screen.HOME -> {
                                    saveableStateHolder.SaveableStateProvider(key = "tab_home") {
                                        HomeScreen(
                                            repository = repository,
                                            audioController = audioController,
                                            onNavigateToProfile = { currentScreen = Screen.PROFILE },
                                            onNavigateToEqualizer = { currentScreen = Screen.EQUALIZER },
                                            onNavigateToPlaylistSelect = { song ->
                                                scope.launch {
                                                    availablePlaylists = loadEditableUserPlaylists()
                                                    songForPlaylistSelection = song
                                                }
                                            },
                                            onNavigateToPlaylist = { id, name -> openPlaylist(id, name) },
                                            onNavigateToLikedSongs = { openPlaylist(Playlist.ID_LIKED, "Liked Songs") },
                                            onNavigateToDownloadedSongs = { openPlaylist(Playlist.ID_DOWNLOADED, "Downloaded Songs") },
                                            onViewSongArtist = { song -> openArtistForSong(song) }
                                        )
                                    }
                                }

                                Screen.SEARCH -> {
                                    saveableStateHolder.SaveableStateProvider(key = "tab_search") {
                                        SearchScreen(
                                            repository = repository,
                                            audioController = audioController,
                                            onNavigateToPlaylistSelect = { song ->
                                                scope.launch {
                                                    availablePlaylists = loadEditableUserPlaylists()
                                                    songForPlaylistSelection = song
                                                }
                                            },
                                            onOpenArtist = { id, name -> openArtistProfile(id, name) },
                                            onViewSongArtist = { song -> openArtistForSong(song) }
                                        )
                                    }
                                }

                                Screen.LIBRARY -> {
                                    saveableStateHolder.SaveableStateProvider(key = "tab_library") {
                                        LibraryScreen(
                                            repository = repository,
                                            onNavigateToPlaylist = { id, name -> openPlaylist(id, name) },
                                            onNavigateToLikedSongs = { openPlaylist(Playlist.ID_LIKED, "Liked Songs") },
                                            onNavigateToDownloadedSongs = { openPlaylist(Playlist.ID_DOWNLOADED, "Downloaded Songs") },
                                            onNavigateToFuse = {
                                                currentTab = 2
                                                currentScreen = Screen.FUSE
                                            },
                                            onNavigateToClub = { clubId ->
                                                activeClubId = clubId
                                                currentTab = 2
                                                currentScreen = Screen.CLUB
                                            },
                                            onNavigateToSoundCapsule = { openSoundCapsule() }
                                        )
                                    }
                                }

                                Screen.PINS -> {
                                    saveableStateHolder.SaveableStateProvider(key = "tab_pins") {
                                        LocationPinsScreen(
                                            repository = repository,
                                            audioController = audioController,
                                            refreshTrigger = pinsRefreshTrigger
                                        )
                                    }
                                }

                                Screen.PLAYLIST -> {
                                    PlaylistScreen(
                                        playlistId = activePlaylistId,
                                        playlistName = activePlaylistName,
                                        repository = repository,
                                        audioController = audioController,
                                        onBack = navigateBackFromPlaylist,
                                        onNavigateToAddSongs = {
                                            if (!FuseCodeCodec.isFusePlaylistName(activePlaylistName) &&
                                                !Playlist.isDailyPlaylistId(activePlaylistId)
                                            ) {
                                                currentScreen = Screen.ADD_SONGS
                                            }
                                        },
                                        onNavigateToPlaylistSelect = { song ->
                                            scope.launch {
                                                availablePlaylists = loadEditableUserPlaylists()
                                                songForPlaylistSelection = song
                                            }
                                        },
                                        onViewSongArtist = { song -> openArtistForSong(song) }
                                    )
                                }

                                Screen.ADD_SONGS -> {
                                    if (FuseCodeCodec.isFusePlaylistName(activePlaylistName)) {
                                        LaunchedEffect(activePlaylistId) {
                                            currentScreen = Screen.PLAYLIST
                                        }
                                    } else {
                                        AddSongToPlaylistScreen(
                                            playlistId = activePlaylistId,
                                            repository = repository,
                                            onBack = { currentScreen = Screen.PLAYLIST }
                                        )
                                    }
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

                                Screen.CLUB -> {
                                    com.example.ui.screens.club.ClubScreen(
                                        repository = repository,
                                        audioController = audioController,
                                        initialClubId = activeClubId,
                                        onBack = {
                                            currentTab = 2
                                            currentScreen = Screen.LIBRARY
                                        }
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
                                        onLogout = onLogout,
                                        onNavigateToSoundCapsule = { openSoundCapsule() }
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

                                Screen.SOUND_CAPSULE -> {
                                    val capsuleVm: SoundCapsuleViewModel = viewModel(
                                        factory = remember(repository) { SoundCapsuleViewModel.Factory(repository) }
                                    )
                                    remember(capsuleVm) {
                                        audioController.flushLiveSessionForUi()
                                        capsuleVm.onScreenOpened()
                                        true
                                    }
                                    val capsuleStats by capsuleVm.stats.collectAsStateWithLifecycle()
                                    val availableMonths by capsuleVm.availableMonths.collectAsStateWithLifecycle()
                                    val monthlyFeed by capsuleVm.monthlyFeed.collectAsStateWithLifecycle()
                                    val historyList by repository.listeningHistory.collectAsStateWithLifecycle(initialValue = emptyList())
                                    SoundCapsuleHost(
                                        stats = capsuleStats,
                                        availableMonths = availableMonths,
                                        monthlyFeed = monthlyFeed,
                                        historyList = historyList,
                                        onPlayTodayQueue = { songs, startIndex ->
                                            audioController.playQueue(songs, startIndex, "Today's History")
                                        },
                                        onMonthSelected = capsuleVm::selectMonth,
                                        onExit = {
                                            currentScreen = soundCapsuleOriginScreen
                                            currentTab = soundCapsuleOriginTab
                                        },
                                        onOpenArtist = { artistIdStr, artistName ->
                                            openArtistByHint(artistIdStr.toIntOrNull(), artistName)
                                        },
                                        backHandlingEnabled = !showFullPlayer
                                    )
                                }

                                Screen.ARTIST_PROFILE -> {
                                    ArtistProfileScreen(
                                        artistId = activeArtistId,
                                        initialArtistName = activeArtistName,
                                        repository = repository,
                                        audioController = audioController,
                                        onBack = {
                                            currentScreen = artistOriginScreen
                                            currentTab = artistOriginTab
                                        },
                                        onNavigateToPlaylistSelect = { song ->
                                            scope.launch {
                                                availablePlaylists = loadEditableUserPlaylists()
                                                songForPlaylistSelection = song
                                            }
                                        },
                                        onNavigateToArtistStudio = { openArtistStudio() },
                                        onViewSongArtist = { song -> openArtistForSong(song) }
                                    )
                                }

                                Screen.ARTIST_STUDIO -> {
                                    ArtistStudioScreen(
                                        repository = repository,
                                        audioController = audioController,
                                        onBack = {
                                            currentScreen = artistStudioOriginScreen
                                            currentTab = artistStudioOriginTab
                                        },
                                        onOpenArtistProfile = { id, name -> openArtistProfile(id, name) }
                                    )
                                }
                            }
                        }

                    }
                }
            }
        }

        // Bottom navigation gradient scrim (transparent at top to pitch black at bottom)
        if (!showFullPlayer) {
            BottomNavGradientScrim(
                modifier = Modifier
                    .zIndex(1f)
                    .align(Alignment.BottomCenter),
                extraHeight = if (hasBottomBar) 84.dp else 48.dp,
                minHeight = 72.dp
            )
        }

        // 🎵 Apple Music Style Floating Dock (Floating MiniPlayer + Circular Translucent Capsule Navigation Bar)
        val showMiniPlayer = !showFullPlayer && hasCurrentSong && currentScreen in screensWithBottomNav
        Column(
            modifier = Modifier
                .zIndex(2f)
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
                val miniPlaybackState = stablePlaybackStateHolder.value
                MiniPlayer(
                    playbackState = miniPlaybackState,
                    currentPositionProvider = { playbackStateHolder.value.currentPositionMs },
                    onTogglePlay = { audioController.togglePlayPause() },
                    onNext = { audioController.next() },
                    onPrevious = { audioController.previous() },
                    onLikeToggle = {
                        miniPlaybackState.currentSong?.let { song ->
                            scope.launch {
                                try {
                                    val isLiked = repository.toggleLike(song)
                                    audioController.updateSongLiked(song.id, isLiked)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                }
                            }
                        }
                    },
                    onExpand = { showFullPlayer = true },
                    onArtworkLoaded = { songId, coverUrl ->
                        audioController.onCurrentSongArtworkLoaded(songId, coverUrl)
                    },
                    modifier = Modifier
                        .widthIn(max = 550.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // 2. Circular Translucent Capsule Navigation Bar (isolated recomposition scope)
            if (hasBottomBar) {
                val isHomeSelected = (currentScreen == Screen.HOME && currentTab == 0) ||
                    (currentScreen == Screen.PLAYLIST && playlistOriginTab == 0) ||
                    (currentScreen == Screen.PROFILE && (playlistOriginTab == 0 || currentTab == 0)) ||
                    (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 0)

                val isSearchSelected = (currentScreen == Screen.SEARCH && currentTab == 1) ||
                    (currentScreen == Screen.PLAYLIST && playlistOriginTab == 1) ||
                    (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 1)

                val isLibrarySelected = (currentScreen == Screen.LIBRARY && currentTab == 2) ||
                    currentScreen == Screen.FUSE ||
                    currentScreen == Screen.CLUB ||
                    (currentScreen == Screen.PLAYLIST && playlistOriginTab == 2) ||
                    (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 2)

                val isPinsSelected = (currentScreen == Screen.PINS && currentTab == 3) ||
                    (currentScreen == Screen.PLAYLIST && playlistOriginTab == 3) ||
                    (currentScreen == Screen.SOUND_CAPSULE && soundCapsuleOriginTab == 3)

                val activeIndex = when {
                    isHomeSelected -> 0
                    isSearchSelected -> 1
                    isLibrarySelected -> 2
                    isPinsSelected -> 3
                    else -> 0
                }

                FloatingCapsuleNavBar(
                    activeIndex = activeIndex,
                    isHomeSelected = isHomeSelected,
                    isSearchSelected = isSearchSelected,
                    isLibrarySelected = isLibrarySelected,
                    isPinsSelected = isPinsSelected,
                    onSelectHome = {
                        if (currentScreen != Screen.HOME || currentTab != 0) {
                            currentTab = 0
                            currentScreen = Screen.HOME
                        }
                    },
                    onSelectSearch = {
                        if (currentScreen != Screen.SEARCH || currentTab != 1) {
                            currentTab = 1
                            currentScreen = Screen.SEARCH
                        }
                    },
                    onSelectLibrary = {
                        if (currentScreen != Screen.LIBRARY || currentTab != 2) {
                            currentTab = 2
                            currentScreen = Screen.LIBRARY
                        }
                    },
                    onSelectPins = {
                        if (currentScreen == Screen.PINS && currentTab == 3) {
                            pinsRefreshTrigger++
                        } else {
                            currentTab = 3
                            currentScreen = Screen.PINS
                        }
                    }
                )
            }
        }

        // Full Screen Player Modal / Overlay
        AnimatedVisibility(
            visible = showFullPlayer,
            modifier = Modifier
                .zIndex(3f)
                .fillMaxSize(),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val playerPlaybackState = stablePlaybackStateHolder.value
            val downloadProgressMap by repository.downloadManager.downloadProgress.collectAsState()
            var isDownloaded by remember(playerPlaybackState.currentSong?.id) { mutableStateOf(false) }
            LaunchedEffect(playerPlaybackState.currentSong?.id) {
                playerPlaybackState.currentSong?.let { s ->
                    isDownloaded = repository.downloadManager.isDownloaded(s.id)
                }
            }
            val isPlayingFromFuse = playerPlaybackState.context?.uri?.startsWith("alaktra:fuse:") == true ||
                FuseCodeCodec.isFusePlaylistName(playerPlaybackState.playbackSource) ||
                FuseCodeCodec.isFusePlaylistName(playerPlaybackState.context?.name.orEmpty()) ||
                playerPlaybackState.currentSong?.fuseContributor != null

            val currentDownloadProgress = if (isPlayingFromFuse) {
                null
            } else {
                playerPlaybackState.currentSong?.let { downloadProgressMap[it.id] }
            }

            PlayerScreen(
                audioController = audioController,
                playbackState = playerPlaybackState,
                isDownloaded = !isPlayingFromFuse && isDownloaded,
                allowDownload = !isPlayingFromFuse,
                downloadProgress = currentDownloadProgress,
                onNavigateToEqualizer = {
                    showFullPlayer = false
                    currentScreen = Screen.EQUALIZER
                },
                onToggleDownload = {
                    if (!isPlayingFromFuse) {
                        playerPlaybackState.currentSong?.let { song ->
                            scope.launch {
                                try {
                                    if (isDownloaded) {
                                        repository.downloadManager.removeDownload(song.id)
                                        isDownloaded = false
                                        android.widget.Toast.makeText(context, "Download removed", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Downloading ${song.title}...", android.widget.Toast.LENGTH_SHORT).show()
                                        repository.downloadManager.downloadSong(song)
                                        isDownloaded = repository.downloadManager.isDownloaded(song.id)
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                }
                            }
                        }
                    }
                },
                onAddToPlaylist = {
                    playerPlaybackState.currentSong?.let { song ->
                        scope.launch {
                            try {
                                availablePlaylists = loadEditableUserPlaylists()
                                songForPlaylistSelection = song
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                    }
                },
                onOpenArtist = { song -> openArtistForSong(song) },
                onSelectArtistCredit = { artistId, artistName ->
                    openArtistByHint(artistId.takeIf { it > 0 }, artistName)
                },
                onClose = { showFullPlayer = false },
                onLikeToggle = {
                    playerPlaybackState.currentSong?.let { song ->
                        scope.launch {
                            try {
                                val isLiked = repository.toggleLike(song)
                                audioController.updateSongLiked(song.id, isLiked)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                    }
                }
            )
        }

        // Multi-artist Collaborator Disambiguation Bottom Sheet
        songForArtistCreditsSheet?.let { creditSong ->
            ArtistCreditsBottomSheet(
                song = creditSong,
                onDismiss = { songForArtistCreditsSheet = null },
                onSelectArtist = { artistId, artistName ->
                    songForArtistCreditsSheet = null
                    openArtistByHint(artistId.takeIf { it > 0 }, artistName)
                }
            )
        }

        // Add to Playlist Selection Modal Bottom Sheet
        songForPlaylistSelection?.let { song ->
            AlaktraModalBottomSheet(
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
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                            contentPadding = PaddingValues(bottom = 12.dp)
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

                        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
    }
}

private val NavPillOuterShape = RoundedCornerShape(36.dp)
private val NavPillInnerShape = RoundedCornerShape(23.dp)
private val NavPillBg = Color(0xFF000000).copy(alpha = 0.85f)
private val NavPillBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))

@Composable
private fun FloatingCapsuleNavBar(
    activeIndex: Int,
    isHomeSelected: Boolean,
    isSearchSelected: Boolean,
    isLibrarySelected: Boolean,
    isPinsSelected: Boolean,
    onSelectHome: () -> Unit,
    onSelectSearch: () -> Unit,
    onSelectLibrary: () -> Unit,
    onSelectPins: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 3.dp)
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .height(58.dp)
            .shadow(
                elevation = 20.dp,
                shape = NavPillOuterShape,
                spotColor = Color.Black.copy(alpha = 0.70f),
                ambientColor = Color.Black.copy(alpha = 0.50f)
            ),
        shape = NavPillOuterShape,
        color = NavPillBg,
        border = NavPillBorder,
        tonalElevation = 0.dp
    ) {
        val animatedIndex by animateFloatAsState(
            targetValue = activeIndex.toFloat(),
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
            label = "gel_pill_slide"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
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
                    .clip(NavPillInnerShape)
                    .background(AlaktraMint.copy(alpha = 0.22f))
                    .border(1.dp, AlaktraMint.copy(alpha = 0.45f), NavPillInnerShape)
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CapsuleNavItem(
                    selected = isHomeSelected,
                    onClick = onSelectHome,
                    icon = Icons.Outlined.Home,
                    selectedIcon = Icons.Default.Home,
                    label = "Home",
                    modifier = Modifier.weight(w0)
                )

                CapsuleNavItem(
                    selected = isSearchSelected,
                    onClick = onSelectSearch,
                    icon = Icons.Outlined.Search,
                    selectedIcon = Icons.Default.Search,
                    label = "Search",
                    modifier = Modifier.weight(w1)
                )

                CapsuleNavItem(
                    selected = isLibrarySelected,
                    onClick = onSelectLibrary,
                    icon = Icons.Outlined.LibraryMusic,
                    selectedIcon = Icons.Default.LibraryMusic,
                    label = "Library",
                    modifier = Modifier.weight(w2)
                )

                CapsuleNavItem(
                    selected = isPinsSelected,
                    onClick = onSelectPins,
                    icon = Icons.Outlined.Place,
                    selectedIcon = Icons.Default.Place,
                    label = "Pins",
                    modifier = Modifier.weight(w3)
                )
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
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "icon_scale"
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
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
private fun LandscapePelletNavItem(
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
        targetValue = if (selected) 1.10f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = 280f
        ),
        label = "landscape_nav_scale"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else icon,
                contentDescription = label,
                tint = if (selected) activeColor else inactiveColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) activeColor else inactiveColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
