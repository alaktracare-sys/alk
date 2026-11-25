package com.example.ui.screens.artist

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.ArtistDetailBundle
import com.example.model.ArtistProfile
import com.example.model.Song
import com.example.ui.components.SongCover
import com.example.ui.components.SongItemRow
import com.example.ui.components.SongMenuBottomSheet
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ArtistProfileScreen(
    artistId: Int,
    initialArtistName: String = "",
    repository: MusicRepository,
    audioController: AudioController,
    onBack: () -> Unit,
    onNavigateToPlaylistSelect: (Song) -> Unit,
    onNavigateToArtistStudio: () -> Unit = {},
    onViewSongArtist: (Song) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser = remember { repository.authPreferences.getUser() }

    var detailBundle by remember(artistId, initialArtistName) { mutableStateOf<ArtistDetailBundle?>(null) }
    var isLoading by remember(artistId, initialArtistName) { mutableStateOf(true) }
    var errorMessage by remember(artistId, initialArtistName) { mutableStateOf<String?>(null) }
    var bioExpanded by remember(artistId) { mutableStateOf(false) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }

    val playbackState by audioController.playbackState.collectAsState()
    val downloadProgress by repository.downloadManager.downloadProgress.collectAsState()

    fun refreshProfile() {
        scope.launch {
            isLoading = true
            errorMessage = null
            val resolvedId = if (artistId > 0) artistId else {
                repository.resolveArtistId(artistIdHint = null, artistNameHint = initialArtistName) ?: 0
            }
            val res = repository.getArtistDetail(resolvedId, fallbackName = initialArtistName)
            res.onSuccess {
                detailBundle = it
            }.onFailure { err ->
                errorMessage = err.message ?: "Unable to load artist profile."
            }
            isLoading = false
        }
    }

    LaunchedEffect(artistId, initialArtistName) {
        refreshProfile()
    }

    fun updateSongInBundle(transform: (Song) -> Song) {
        val current = detailBundle ?: return
        detailBundle = current.copy(
            popularSongs = current.popularSongs.map(transform),
            primarySongs = current.primarySongs.map(transform),
            featuredSongs = current.featuredSongs.map(transform),
            allSongs = current.allSongs.map(transform)
        )
    }

    Scaffold(
        containerColor = AlaktraBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("artist_profile_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isLoading && detailBundle == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AlaktraMint)
                }
            } else if (detailBundle == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonOff,
                        contentDescription = null,
                        tint = AlaktraTextMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = errorMessage ?: "Artist profile unavailable",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AlaktraTextPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack) {
                            Text("Go Back", color = AlaktraTextPrimary)
                        }
                        Button(
                            onClick = { refreshProfile() },
                            colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF042014))
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                val bundle = detailBundle!!
                val artist = bundle.artist
                val allPlayable = remember(bundle) {
                    bundle.allSongs.ifEmpty { (bundle.primarySongs + bundle.featuredSongs).distinctBy { it.id } }
                }
                val isOwner = currentUser != null && artist.claimedByUserId != null && artist.claimedByUserId == currentUser.id

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 860.dp),
                    contentPadding = PaddingValues(bottom = 170.dp)
                ) {
                    // Hero Banner + Avatar + Identity + Play/Shuffle
                    item(key = "artist_hero") {
                        ArtistHeroHeader(
                            artist = artist,
                            bioExpanded = bioExpanded,
                            onToggleBio = { bioExpanded = !bioExpanded },
                            isOwner = isOwner,
                            hasTracks = allPlayable.isNotEmpty(),
                            onBack = onBack,
                            onOpenStudio = onNavigateToArtistStudio,
                            onPlayAll = {
                                if (allPlayable.isNotEmpty()) {
                                    if (playbackState.shuffle) {
                                        audioController.toggleShuffle()
                                    }
                                    audioController.playQueue(allPlayable, 0, source = artist.name)
                                }
                            },
                            onShuffleAll = {
                                if (allPlayable.isNotEmpty()) {
                                    val shuffled = allPlayable.shuffled()
                                    audioController.playQueue(shuffled, 0, source = "${artist.name} • Shuffle")
                                }
                            }
                        )
                    }

                    // Popular Tracks Section (Top 5)
                    if (bundle.popularSongs.isNotEmpty()) {
                        item(key = "popular_header") {
                            SectionHeaderRow(
                                title = stringResource(R.string.artist_popular_title),
                                subtitle = "Most streamed tracks"
                            )
                        }
                        itemsIndexed(
                            items = bundle.popularSongs,
                            key = { _, s -> "popular_${s.id}" }
                        ) { index, song ->
                            val isCurrent = playbackState.currentSong?.id == song.id
                            PopularArtistTrackRow(
                                rank = index + 1,
                                song = song,
                                isPlaying = isCurrent && playbackState.isPlaying,
                                onClick = {
                                    audioController.playQueue(bundle.popularSongs, index, source = "${artist.name} • Popular")
                                },
                                onLikeToggle = {
                                    scope.launch {
                                        val liked = repository.toggleLike(song)
                                        audioController.updateSongLiked(song.id, liked)
                                        updateSongInBundle { if (it.id == song.id) it.copy(isLiked = liked) else it }
                                    }
                                },
                                onMoreClick = { selectedSongForMenu = song }
                            )
                        }
                    }

                    // Primary Releases Section (Full Discography as Primary Artist)
                    if (bundle.primarySongs.isNotEmpty()) {
                        item(key = "primary_header") {
                            Spacer(modifier = Modifier.height(14.dp))
                            SectionHeaderRow(
                                title = stringResource(R.string.artist_releases_title),
                                subtitle = "${bundle.primarySongs.size} ${if (bundle.primarySongs.size == 1) "track" else "tracks"} as primary artist"
                            )
                        }
                        itemsIndexed(
                            items = bundle.primarySongs,
                            key = { _, s -> "primary_${s.id}" }
                        ) { index, song ->
                            val isCurrent = playbackState.currentSong?.id == song.id
                            SongItemRow(
                                song = song,
                                isPlaying = isCurrent && playbackState.isPlaying,
                                downloadProgress = downloadProgress[song.id],
                                onSongClick = {
                                    audioController.playQueue(bundle.primarySongs, index, source = artist.name)
                                },
                                onLikeToggle = {
                                    scope.launch {
                                        val liked = repository.toggleLike(song)
                                        audioController.updateSongLiked(song.id, liked)
                                        updateSongInBundle { if (it.id == song.id) it.copy(isLiked = liked) else it }
                                    }
                                },
                                onMoreClick = { selectedSongForMenu = song }
                            )
                        }
                    }

                    // Featured Appearances Section ("Appears On")
                    if (bundle.featuredSongs.isNotEmpty()) {
                        item(key = "featured_header") {
                            Spacer(modifier = Modifier.height(16.dp))
                            SectionHeaderRow(
                                title = stringResource(R.string.artist_appears_on_title),
                                subtitle = "Featured collaborations (${bundle.featuredSongs.size})"
                            )
                        }
                        itemsIndexed(
                            items = bundle.featuredSongs,
                            key = { _, s -> "featured_${s.id}" }
                        ) { index, song ->
                            val isCurrent = playbackState.currentSong?.id == song.id
                            SongItemRow(
                                song = song,
                                isPlaying = isCurrent && playbackState.isPlaying,
                                downloadProgress = downloadProgress[song.id],
                                onSongClick = {
                                    audioController.playQueue(bundle.featuredSongs, index, source = "${artist.name} • Appears On")
                                },
                                onLikeToggle = {
                                    scope.launch {
                                        val liked = repository.toggleLike(song)
                                        audioController.updateSongLiked(song.id, liked)
                                        updateSongInBundle { if (it.id == song.id) it.copy(isLiked = liked) else it }
                                    }
                                },
                                onMoreClick = { selectedSongForMenu = song }
                            )
                        }
                    }

                    if (allPlayable.isEmpty()) {
                        item(key = "empty_discography") {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 36.dp)
                            ) {
                                Text(
                                    text = "No tracks have been allotted to ${artist.name} yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlaktraTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

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
            onViewArtist = if (song.hasMultipleArtists) {
                { onViewSongArtist(song) }
            } else null,
            onToggleDownload = {
                scope.launch {
                    if (isDownloaded) {
                        repository.downloadManager.removeDownload(song.id)
                        updateSongInBundle { if (it.id == song.id) it.copy(isDownloaded = false, localPath = null) else it }
                    } else {
                        repository.downloadManager.downloadSong(song)
                        val path = repository.downloadManager.getLocalPath(song.id)
                        updateSongInBundle { if (it.id == song.id) it.copy(isDownloaded = true, localPath = path) else it }
                    }
                }
            }
        )
    }
}

@Composable
private fun ArtistHeroHeader(
    artist: ArtistProfile,
    bioExpanded: Boolean,
    onToggleBio: () -> Unit,
    isOwner: Boolean,
    hasTracks: Boolean,
    onBack: () -> Unit,
    onOpenStudio: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    val context = LocalContext.current
    val bannerSource = artist.bannerUrl.ifBlank { artist.avatarUrl }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        ) {
            if (bannerSource.isNotBlank()) {
                AsyncImage(
                    model = remember(bannerSource) {
                        ImageRequest.Builder(context)
                            .data(bannerSource)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = "${artist.name} cover banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF0E3A2F),
                                    Color(0xFF162447),
                                    AlaktraBackground
                                )
                            )
                        )
                )
            }

            // Atmospheric dark gradient scrim into AlaktraBackground
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.45f),
                            0.45f to Color.Black.copy(alpha = 0.35f),
                            1.0f to AlaktraBackground
                        )
                    )
            )

            // Top bar inside hero
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.48f))
                        .testTag("artist_profile_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.playlist_back),
                        tint = Color.White
                    )
                }

                if (isOwner) {
                    Surface(
                        onClick = onOpenStudio,
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("artist_open_studio_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.artist_open_studio),
                                tint = AlaktraMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.artist_open_studio),
                                color = AlaktraTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Avatar + Verified + Stage Name anchored at bottom of hero
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan))
                        )
                        .border(2.5.dp, AlaktraBackground, CircleShape)
                ) {
                    if (artist.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = remember(artist.avatarUrl) {
                                ImageRequest.Builder(context)
                                    .data(artist.avatarUrl)
                                    .crossfade(true)
                                    .build()
                            },
                            contentDescription = "${artist.name} avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = artist.name.take(1).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color(0xFF041E14)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (artist.isVerified) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = stringResource(R.string.artist_verified_badge),
                                tint = AlaktraCyan,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.artist_verified_badge),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AlaktraCyan
                            )
                        }
                    }

                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 28.sp
                        ),
                        color = AlaktraTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Stats + Bio + Action Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${formatCount(artist.monthlyListeners)} monthly listeners",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AlaktraTextSecondary
                )
                Text("•", color = AlaktraTextMuted)
                Text(
                    text = "${formatCount(artist.totalPlays)} total plays",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlaktraTextSecondary
                )
                Text("•", color = AlaktraTextMuted)
                Text(
                    text = "${artist.songCount} ${if (artist.songCount == 1) "song" else "songs"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlaktraTextSecondary
                )
            }

            if (artist.bio.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = artist.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlaktraTextSecondary,
                    maxLines = if (bioExpanded) 12 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleBio)
                        .padding(vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onPlayAll,
                    enabled = hasTracks,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AlaktraMint,
                        contentColor = Color(0xFF042014)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("artist_play_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.artist_play),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.artist_play),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }

                OutlinedButton(
                    onClick = onShuffleAll,
                    enabled = hasTracks,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, AlaktraBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = AlaktraSurface,
                        contentColor = AlaktraTextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("artist_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.artist_shuffle),
                        tint = AlaktraMint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.artist_shuffle),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            ),
            color = AlaktraTextPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = AlaktraTextMuted
        )
    }
}

@Composable
private fun PopularArtistTrackRow(
    rank: Int,
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLikeToggle: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPlaying) Color(0xFF1E202C) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("artist_popular_track_${song.id}")
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = if (isPlaying) AlaktraMint else AlaktraTextMuted,
            modifier = Modifier.width(24.dp)
        )

        SongCover(
            imageUrl = song.coverUrl,
            size = 48.dp,
            cornerRadius = 10.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) AlaktraMint else AlaktraTextPrimary,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (song.playCount > 0) "${formatCount(song.playCount)} plays • ${song.artist}" else song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = AlaktraTextSecondary,
                fontSize = 12.sp
            )
        }

        IconButton(
            onClick = onLikeToggle,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (song.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (song.isLiked) "Unlike" else "Like",
                tint = if (song.isLiked) AlaktraMint else AlaktraTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = onMoreClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More Options",
                tint = AlaktraTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value.coerceAtLeast(0))
