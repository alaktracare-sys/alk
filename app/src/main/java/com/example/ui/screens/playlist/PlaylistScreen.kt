package com.example.ui.screens.playlist

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.AudioCache
import com.example.audio.AudioController
import com.example.audio.ContextType
import com.example.audio.PlaybackContext
import com.example.data.fuse.FuseCodeCodec
import com.example.data.repository.MusicRepository
import com.example.model.DailyPlaylist
import com.example.model.Playlist
import com.example.model.Song
import com.example.ui.components.CacheCapSettingsDialog
import com.example.ui.components.DailyPlaylistCollageCover
import com.example.ui.components.FuseBlendStoryDialog
import com.example.ui.components.FuseContributorBadge
import com.example.ui.components.FuseDualAvatarCover
import com.example.ui.components.SongItemRow
import com.example.ui.components.SongMenuBottomSheet
import com.example.ui.components.parseDailyAccentColor
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: Int,
    playlistName: String,
    repository: MusicRepository,
    audioController: AudioController,
    onBack: () -> Unit,
    onNavigateToAddSongs: () -> Unit,
    onNavigateToPlaylistSelect: (Song) -> Unit = {},
    onViewSongArtist: (Song) -> Unit = {}
) {
    val context = LocalContext.current
    var songs by remember(playlistId) { mutableStateOf<List<Song>>(emptyList()) }
    var fusePlaylistMeta by remember(playlistId) { mutableStateOf<Playlist?>(null) }
    var dailyPlaylistMeta by remember(playlistId) { mutableStateOf<DailyPlaylist?>(null) }
    var isLoading by remember(playlistId) { mutableStateOf(true) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var showCacheCapDialog by remember { mutableStateOf(false) }
    var showBlendStoryDialog by remember { mutableStateOf(false) }
    var cacheCapMb by remember { mutableIntStateOf(AudioCache.getCacheCapMb(context)) }
    val scope = rememberCoroutineScope()

    val isDownloadedPlaylist = playlistId == Playlist.ID_DOWNLOADED || playlistId == -2 || playlistName.contains("Download", ignoreCase = true)
    val isLikedPlaylist = playlistId == Playlist.ID_LIKED || playlistId == -1 || playlistName.contains("Liked", ignoreCase = true)
    val isOfflineBackup = playlistId == Playlist.ID_OFFLINE_BACKUP || playlistId == -3 || playlistName.contains("Offline Backup", ignoreCase = true)
    val isDailyPlaylist = !isDownloadedPlaylist && !isLikedPlaylist && !isOfflineBackup && Playlist.isDailyPlaylistId(playlistId)
    val isFusePlaylist = !isDownloadedPlaylist && !isLikedPlaylist && !isOfflineBackup && !isDailyPlaylist &&
        (fusePlaylistMeta?.isFuse == true || FuseCodeCodec.isFusePlaylistName(playlistName))

    val effectiveTitle = dailyPlaylistMeta?.name?.takeIf { it.isNotBlank() }
        ?: fusePlaylistMeta?.name?.takeIf { it.isNotBlank() }
        ?: playlistName
    val dailyAccentColor = remember(dailyPlaylistMeta?.accentColorHex) {
        parseDailyAccentColor(dailyPlaylistMeta?.accentColorHex, AlaktraMint)
    }
    val fallbackNames = remember(effectiveTitle) { FuseCodeCodec.parseFuseNamesFromTitle(effectiveTitle) }
    val fuseUser1Name = fusePlaylistMeta?.fuseUser1Name?.takeIf { it.isNotBlank() } ?: fallbackNames.first
    val fuseUser2Name = fusePlaylistMeta?.fuseUser2Name?.takeIf { it.isNotBlank() } ?: fallbackNames.second
    val fuseMatchScore = (fusePlaylistMeta?.fuseMatchScore ?: 0).let { if (it > 0) it else 84 }

    val playbackState by audioController.playbackState.collectAsState()
    val downloadProgress by repository.downloadManager.downloadProgress.collectAsState()
    val liveDownloadedSongs by repository.downloadedSongsFlow.collectAsState(initial = emptyList())

    val displaySongs = if (isDownloadedPlaylist) liveDownloadedSongs else songs
    val contextUri = when {
        isFusePlaylist -> "alaktra:fuse:$playlistId"
        isDailyPlaylist -> "alaktra:daily:$playlistId"
        else -> "alaktra:playlist:$playlistId"
    }

    LaunchedEffect(playlistId, playlistName) {
        isLoading = true
        val detectedFuse = !isDownloadedPlaylist && !isLikedPlaylist && !isOfflineBackup && !isDailyPlaylist &&
            (FuseCodeCodec.isFusePlaylistName(playlistName) || repository.isFusePlaylist(playlistId))

        if (isDailyPlaylist) {
            val cachedMeta = repository.getCachedDailyPlaylists().find { it.id == playlistId }
            if (cachedMeta != null) {
                dailyPlaylistMeta = cachedMeta
                if (cachedMeta.songs.isNotEmpty()) {
                    songs = cachedMeta.songs
                    isLoading = false
                }
            }
            val freshMeta = repository.getDailyPlaylistMeta(playlistId)
            if (freshMeta != null) {
                dailyPlaylistMeta = freshMeta
                songs = freshMeta.songs
            } else {
                songs = repository.getPlaylistSongs(playlistId)
            }
            isLoading = false
        } else if (detectedFuse) {
            val cachedBundle = repository.getFusePlaylistBundle(playlistId, forceRefresh = false)
            if (cachedBundle != null) {
                fusePlaylistMeta = cachedBundle.playlist
                if (cachedBundle.songs.isNotEmpty()) {
                    songs = cachedBundle.songs
                    isLoading = false
                }
            }
            val freshBundle = repository.getFusePlaylistBundle(playlistId, forceRefresh = true)
            if (freshBundle != null) {
                fusePlaylistMeta = freshBundle.playlist
                songs = freshBundle.songs
            }
            isLoading = false
        } else {
            val cached = when {
                isOfflineBackup -> repository.getOfflineBackupSongs()
                isLikedPlaylist -> repository.getCachedLikedSongs()
                isDownloadedPlaylist -> repository.getDownloadedSongs()
                else -> repository.getCachedPlaylistSongs(playlistId)
            }
            if (cached.isNotEmpty()) {
                songs = cached
                isLoading = false
            }

            val fresh = when {
                isOfflineBackup -> repository.getOfflineBackupSongs()
                isLikedPlaylist -> repository.getLikedSongs()
                isDownloadedPlaylist -> repository.getDownloadedSongs()
                else -> repository.getPlaylistSongs(playlistId)
            }
            songs = fresh
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlaktraBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            // Gradient Header + Action Bar
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                when {
                                    isDailyPlaylist -> listOf(
                                        dailyAccentColor.copy(alpha = 0.42f),
                                        AlaktraSurface,
                                        AlaktraBackground
                                    )
                                    isFusePlaylist -> listOf(
                                        AlaktraViolet.copy(alpha = 0.56f),
                                        AlaktraCyan.copy(alpha = 0.26f),
                                        AlaktraSurface.copy(alpha = 0.92f),
                                        AlaktraBackground
                                    )
                                    isOfflineBackup -> listOf(Color(0xFF0284C7).copy(alpha = 0.5f), AlaktraSurface, AlaktraBackground)
                                    isLikedPlaylist -> listOf(Color(0xFF6366F1).copy(alpha = 0.5f), AlaktraSurface, AlaktraBackground)
                                    isDownloadedPlaylist -> listOf(Color(0xFF0D9488).copy(alpha = 0.5f), AlaktraSurface, AlaktraBackground)
                                    else -> listOf(AlaktraMint.copy(alpha = 0.35f), AlaktraSurface, AlaktraBackground)
                                }
                            )
                        )
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Top Bar: Back button only
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraSurface.copy(alpha = 0.6f))
                                    .testTag("playlist_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.playlist_back),
                                    tint = AlaktraTextPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Cover + Playlist Title Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDailyPlaylist) {
                                val coverUrls = dailyPlaylistMeta?.previewCovers
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: displaySongs.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4)
                                DailyPlaylistCollageCover(
                                    coverUrls = coverUrls,
                                    accentColor = dailyAccentColor,
                                    cornerRadius = 16.dp,
                                    modifier = Modifier.size(84.dp)
                                )
                            } else if (isFusePlaylist) {
                                FuseDualAvatarCover(
                                    user1Name = fuseUser1Name,
                                    user2Name = fuseUser2Name,
                                    size = 84.dp,
                                    cornerRadius = 18.dp
                                )
                            } else {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            when {
                                                isOfflineBackup -> Brush.linearGradient(listOf(Color(0xFF0369A1), Color(0xFF06B6D4), AlaktraMint))
                                                isLikedPlaylist -> Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF06B6D4), AlaktraMint))
                                                isDownloadedPlaylist -> Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF14B8A6), AlaktraMint))
                                                else -> Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan))
                                            }
                                        )
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isOfflineBackup -> Icons.Default.CloudDone
                                            isLikedPlaylist -> Icons.Default.Favorite
                                            isDownloadedPlaylist -> Icons.Default.DownloadDone
                                            else -> Icons.Default.QueueMusic
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            isOfflineBackup || isLikedPlaylist || isDownloadedPlaylist -> Color.White
                                            else -> Color(0xFF041C12)
                                        },
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = effectiveTitle,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = if (isFusePlaylist) 25.sp else 24.sp,
                                        lineHeight = 30.sp
                                    ),
                                    color = AlaktraTextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!isFusePlaylist) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when {
                                            isDailyPlaylist -> "${displaySongs.size} tracks • Personalized Daily Mix"
                                            isOfflineBackup -> "${displaySongs.size} tracks • Offline Backup (Cache Memory)"
                                            isDownloadedPlaylist -> "${displaySongs.size} tracks • Offline Music"
                                            isLikedPlaylist -> "${displaySongs.size} tracks • Favorites Collection"
                                            else -> "${displaySongs.size} tracks • Alaktra Collection"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDailyPlaylist) dailyAccentColor else AlaktraTextSecondary
                                    )
                                    if (isDailyPlaylist) {
                                        val mixSubtitle = dailyPlaylistMeta?.subtitle
                                        if (!mixSubtitle.isNullOrBlank()) {
                                            Text(
                                                text = mixSubtitle,
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

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action Bar Row: Relocated Taste Match pill (or Download/Status) on left, Shuffle + Circular Play on right
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp)
                        ) {
                            if (isFusePlaylist) {
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF0A141A).copy(alpha = 0.65f),
                                        border = BorderStroke(1.2.dp, AlaktraMint.copy(alpha = 0.68f)),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(50))
                                            .clickable { showBlendStoryDialog = true }
                                            .testTag("fuse_blend_story_button")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = AlaktraMint,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.fuse_blend_story_cta, fuseMatchScore),
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 12.5.sp
                                                ),
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (displaySongs.isNotEmpty()) {
                                                val ctx = PlaybackContext(
                                                    uri = contextUri,
                                                    type = ContextType.PLAYLIST,
                                                    name = effectiveTitle,
                                                    trackIds = displaySongs.map { it.id },
                                                    tracks = displaySongs
                                                )
                                                audioController.playContext(ctx, startIndex = 0, autoShuffle = true)
                                            }
                                        },
                                        modifier = Modifier
                                            .requiredSize(48.dp)
                                            .clip(CircleShape)
                                            .background(AlaktraSurface.copy(alpha = 0.85f))
                                            .border(1.dp, AlaktraBorder, CircleShape)
                                            .testTag("playlist_shuffle_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = stringResource(R.string.playlist_shuffle),
                                            tint = AlaktraTextSecondary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .requiredSize(56.dp)
                                            .clip(CircleShape)
                                            .background(Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan)))
                                            .clickable {
                                                if (displaySongs.isNotEmpty()) {
                                                    val ctx = PlaybackContext(
                                                        uri = contextUri,
                                                        type = ContextType.PLAYLIST,
                                                        name = effectiveTitle,
                                                        trackIds = displaySongs.map { it.id },
                                                        tracks = displaySongs
                                                    )
                                                    audioController.playContext(ctx, startIndex = 0, autoShuffle = false)
                                                }
                                            }
                                            .testTag("playlist_play_all_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.playlist_play_all),
                                            tint = Color(0xFF041C12),
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isDownloadedPlaylist || isOfflineBackup) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = (if (isOfflineBackup) Color(0xFF0369A1) else Color(0xFF0F766E)).copy(alpha = 0.25f),
                                            border = BorderStroke(1.dp, (if (isOfflineBackup) Color(0xFF06B6D4) else Color(0xFF14B8A6)).copy(alpha = 0.5f)),
                                            modifier = if (isOfflineBackup) {
                                                Modifier
                                                    .heightIn(min = 48.dp)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .clickable { showCacheCapDialog = true }
                                            } else Modifier
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isOfflineBackup) Icons.Default.CloudDone else Icons.Default.OfflinePin,
                                                    contentDescription = null,
                                                    tint = if (isOfflineBackup) AlaktraCyan else AlaktraMint,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isOfflineBackup) "Cache Cap: ${AudioCache.formatCapMb(cacheCapMb)}" else "Offline Ready",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (isOfflineBackup) AlaktraCyan else AlaktraMint,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isOfflineBackup) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Tune,
                                                        contentDescription = null,
                                                        tint = AlaktraCyan,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Download all button (only on normal/liked playlists, NEVER on Fuse)
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    songs.forEach { song ->
                                                        if (!song.isDownloaded) {
                                                            repository.downloadManager.downloadSong(song)
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .requiredSize(48.dp)
                                                .clip(CircleShape)
                                                .background(AlaktraSurface)
                                                .border(1.dp, AlaktraBorder, CircleShape)
                                                .testTag("playlist_download_all_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DownloadForOffline,
                                                contentDescription = stringResource(R.string.playlist_download_all),
                                                tint = AlaktraMint,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    // Shuffle play button
                                    IconButton(
                                        onClick = {
                                            if (displaySongs.isNotEmpty()) {
                                                val ctx = PlaybackContext(
                                                    uri = contextUri,
                                                    type = ContextType.PLAYLIST,
                                                    name = effectiveTitle,
                                                    trackIds = displaySongs.map { it.id },
                                                    tracks = displaySongs
                                                )
                                                audioController.playContext(ctx, startIndex = 0, autoShuffle = true)
                                            }
                                        },
                                        modifier = Modifier
                                            .requiredSize(48.dp)
                                            .clip(CircleShape)
                                            .background(AlaktraSurface)
                                            .border(1.dp, AlaktraBorder, CircleShape)
                                            .testTag("playlist_shuffle_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = stringResource(R.string.playlist_shuffle),
                                            tint = AlaktraTextSecondary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Large Play button
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .requiredSize(56.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan)))
                                        .clickable {
                                            if (displaySongs.isNotEmpty()) {
                                                val ctx = PlaybackContext(
                                                    uri = contextUri,
                                                    type = ContextType.PLAYLIST,
                                                    name = effectiveTitle,
                                                    trackIds = displaySongs.map { it.id },
                                                    tracks = displaySongs
                                                )
                                                audioController.playContext(ctx, startIndex = 0, autoShuffle = false)
                                            }
                                        }
                                        .testTag("playlist_play_all_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.playlist_play_all),
                                        tint = Color(0xFF041C12),
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Add Song Row (HIDDEN for Fuse and Daily Mix playlists since they are server-curated)
            if (!isLikedPlaylist && !isDownloadedPlaylist && !isOfflineBackup && !isFusePlaylist && !isDailyPlaylist) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToAddSongs)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .testTag("playlist_add_tracks_row")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AlaktraSurface)
                                .border(1.dp, AlaktraBorder, RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = AlaktraMint,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Text(
                            text = "Add tracks to playlist",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary
                        )
                    }
                }
            }

            if (isLoading && displaySongs.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        CircularProgressIndicator(color = AlaktraMint)
                    }
                }
            } else if (displaySongs.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = when {
                                    isFusePlaylist -> Icons.Default.AutoAwesome
                                    isDownloadedPlaylist -> Icons.Default.CloudDownload
                                    isLikedPlaylist -> Icons.Default.FavoriteBorder
                                    else -> Icons.Default.MusicOff
                                },
                                contentDescription = null,
                                tint = AlaktraTextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when {
                                    isFusePlaylist -> "Curating your Fuse Blend"
                                    isDownloadedPlaylist -> "No downloaded songs yet"
                                    isLikedPlaylist -> "No liked songs yet"
                                    else -> "Playlist is empty"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    isFusePlaylist -> "Once either listener likes or plays songs on the server, your 30-song Blend updates automatically."
                                    isDownloadedPlaylist -> "Download any track from any playlist or search to listen offline without internet."
                                    isLikedPlaylist -> "Tap the heart icon on any song to add it here."
                                    else -> "Add tracks from your server library."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = displaySongs,
                    key = { _, song -> song.id },
                    contentType = { _, _ -> "song_row" }
                ) { index, song ->
                    val isCurrent = playbackState.currentSong?.id == song.id
                    SongItemRow(
                        song = song,
                        isPlaying = isCurrent && playbackState.isPlaying,
                        downloadProgress = if (isFusePlaylist) null else downloadProgress[song.id],
                        showDownloadStatus = !isFusePlaylist,
                        contributorBadge = if (isFusePlaylist) {
                            {
                                FuseContributorBadge(
                                    contributor = song.fuseContributor,
                                    user1Name = fuseUser1Name,
                                    user2Name = fuseUser2Name
                                )
                            }
                        } else null,
                        onSongClick = {
                            val ctx = PlaybackContext(
                                uri = contextUri,
                                type = ContextType.PLAYLIST,
                                name = effectiveTitle,
                                trackIds = displaySongs.map { it.id },
                                tracks = displaySongs
                            )
                            audioController.playContext(ctx, startIndex = index, autoShuffle = false)
                        },
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
            }
        }
    }

    selectedSongForMenu?.let { song ->
        val isDownloaded = !isFusePlaylist && (song.isDownloaded || (downloadProgress[song.id] == null && song.localPath != null))
        SongMenuBottomSheet(
            song = song,
            isDownloaded = isDownloaded,
            allowDownload = !isFusePlaylist,
            onDismiss = { selectedSongForMenu = null },
            onPlayNext = {
                audioController.playNext(song)
                Toast.makeText(context, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = {
                audioController.addToQueue(song)
                Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = {
                onNavigateToPlaylistSelect(song)
            },
            onViewArtist = { onViewSongArtist(song) },
            onRemoveFromPlaylist = if (!isDownloadedPlaylist && !isLikedPlaylist && !isOfflineBackup && !isFusePlaylist && !isDailyPlaylist) {
                {
                    scope.launch {
                        repository.removeSongFromPlaylist(playlistId, song.id)
                        songs = songs.filter { it.id != song.id }
                        Toast.makeText(context, "Removed from playlist", Toast.LENGTH_SHORT).show()
                    }
                }
            } else null,
            onToggleDownload = {
                if (!isFusePlaylist) {
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
            }
        )
    }

    if (showBlendStoryDialog && isFusePlaylist) {
        FuseBlendStoryDialog(
            playlistName = effectiveTitle,
            user1Name = fuseUser1Name,
            user2Name = fuseUser2Name,
            matchScore = fuseMatchScore,
            songs = displaySongs,
            onDismiss = { showBlendStoryDialog = false },
            onPlayFuse = {
                if (displaySongs.isNotEmpty()) {
                    val ctx = PlaybackContext(
                        uri = contextUri,
                        type = ContextType.PLAYLIST,
                        name = effectiveTitle,
                        trackIds = displaySongs.map { it.id },
                        tracks = displaySongs
                    )
                    audioController.playContext(ctx, startIndex = 0, autoShuffle = false)
                }
            }
        )
    }

    if (showCacheCapDialog) {
        CacheCapSettingsDialog(
            currentCapMb = cacheCapMb,
            currentUsageBytes = AudioCache.getCacheSizeBytes(context),
            onDismissRequest = { showCacheCapDialog = false },
            onApplyCap = { newCapMb ->
                val applied = AudioCache.setCacheCapMb(context, newCapMb)
                cacheCapMb = applied
                showCacheCapDialog = false
                scope.launch {
                    if (isOfflineBackup) {
                        songs = repository.getOfflineBackupSongs()
                    }
                }
                Toast.makeText(
                    context,
                    "Song cache limit set to ${AudioCache.formatCapMb(applied)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}
