package com.example.ui.screens.club

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioController
import com.example.audio.ContextType
import com.example.audio.PlaybackContext
import com.example.data.repository.MusicRepository
import com.example.model.ClubPlaylistDetails
import com.example.model.Song
import com.example.ui.components.SongCover
import com.example.ui.components.SongItemRow
import com.example.ui.components.SongMenuBottomSheet
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubScreen(
    repository: MusicRepository,
    audioController: AudioController,
    initialClubId: Int? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedClubId by remember(initialClubId) { mutableStateOf(initialClubId) }
    var playlistDetails by remember { mutableStateOf<ClubPlaylistDetails?>(null) }
    var isLoadingDetails by remember { mutableStateOf(true) }

    // Bottom sheet states
    var showCollaboratorsSheet by remember { mutableStateOf(false) }
    var showAddSongSheet by remember { mutableStateOf(false) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }

    // Catalog search state
    var catalogSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoadingCatalog by remember { mutableStateOf(false) }

    val playbackState by audioController.playbackState.collectAsState()

    fun loadClubDetails(clubId: Int) {
        scope.launch {
            isLoadingDetails = true
            val res = repository.getClubDetails(clubId)
            isLoadingDetails = false
            if (res.isSuccess) {
                playlistDetails = res.getOrNull()
            } else {
                Toast.makeText(context, "Could not load playlist", Toast.LENGTH_SHORT).show()
                onBack()
            }
        }
    }

    fun loadCatalog() {
        scope.launch {
            isLoadingCatalog = true
            try {
                catalogSongs = repository.getSongs()
            } catch (_: Exception) {}
            isLoadingCatalog = false
        }
    }

    LaunchedEffect(selectedClubId) {
        if (selectedClubId != null) {
            loadClubDetails(selectedClubId!!)
        } else {
            onBack()
        }
    }

    BackHandler {
        onBack()
    }

    val contextUri = "alaktra:club:${selectedClubId ?: 0}"
    val effectiveTitle = playlistDetails?.name ?: "Collaborative Playlist"
    val displaySongs = remember(playlistDetails) {
        playlistDetails?.songs?.map { it.song } ?: emptyList()
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
            // Header Gradient & Top Action Section
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    AlaktraMint.copy(alpha = 0.35f),
                                    AlaktraSurface,
                                    AlaktraBackground
                                )
                            )
                        )
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Top Navigation Bar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraSurface.copy(alpha = 0.6f))
                                    .testTag("club_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = AlaktraTextPrimary
                                )
                            }

                            if (playlistDetails != null) {
                                IconButton(
                                    onClick = { showCollaboratorsSheet = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(AlaktraSurface.copy(alpha = 0.6f))
                                        .testTag("club_collaborators_menu_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Group,
                                        contentDescription = "Collaborators",
                                        tint = AlaktraMint
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Cover + Playlist Info Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan)))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = Color(0xFF041C12),
                                    modifier = Modifier.size(36.dp)
                                )
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
                                        fontSize = 24.sp,
                                        lineHeight = 30.sp
                                    ),
                                    color = AlaktraTextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${displaySongs.size} tracks ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                                if (playlistDetails != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AlaktraSurface.copy(alpha = 0.7f))
                                            .clickable { showCollaboratorsSheet = true }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Key,
                                            contentDescription = null,
                                            tint = AlaktraMint,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Code: #${playlistDetails!!.code}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = AlaktraMint
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action Bar Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Collaborators drawer button
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = AlaktraSurface,
                                    border = BorderStroke(1.dp, AlaktraBorder),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { showCollaboratorsSheet = true }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.People,
                                            contentDescription = null,
                                            tint = AlaktraMint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Collaborators (${playlistDetails?.members?.size ?: 1})",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = AlaktraTextPrimary
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
                                        .testTag("club_shuffle_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = "Shuffle",
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
                                    .testTag("club_play_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color(0xFF041C12),
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Add Tracks Row
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            loadCatalog()
                            showAddSongSheet = true
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("club_add_tracks_row")
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

            if (isLoadingDetails && displaySongs.isEmpty()) {
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
                                imageVector = Icons.Default.MusicOff,
                                contentDescription = null,
                                tint = AlaktraTextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Playlist is empty",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Add tracks from your server library. Anyone in this collaborative playlist can add songs!",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = playlistDetails?.songs ?: emptyList(),
                    key = { _, clubSong -> clubSong.song.id },
                    contentType = { _, _ -> "song_row" }
                ) { index, clubSong ->
                    val song = clubSong.song
                    val isCurrent = playbackState.currentSong?.id == song.id
                    SongItemRow(
                        song = song,
                        isPlaying = isCurrent && playbackState.isPlaying,
                        showDownloadStatus = true,
                        contributorBadge = {
                            if (clubSong.addedByUsername.isNotBlank()) {
                                Text(
                                    text = "by @${clubSong.addedByUsername}",
                                    color = AlaktraMint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        },
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
                                loadClubDetails(selectedClubId!!)
                            }
                        },
                        onMoreClick = { selectedSongForMenu = song }
                    )
                }
            }
        }
    }

    // SONG MENU BOTTOM SHEET
    selectedSongForMenu?.let { song ->
        SongMenuBottomSheet(
            song = song,
            isDownloaded = song.isDownloaded,
            allowDownload = true,
            onDismiss = { selectedSongForMenu = null },
            onPlayNext = {
                audioController.playNext(song)
                Toast.makeText(context, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = {
                audioController.addToQueue(song)
                Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = {},
            onToggleDownload = {
                scope.launch {
                    if (song.isDownloaded) {
                        repository.downloadManager.removeDownload(song.id)
                    } else {
                        repository.downloadManager.downloadSong(song)
                    }
                    loadClubDetails(selectedClubId!!)
                }
            },
            onViewArtist = {},
            onRemoveFromPlaylist = {
                scope.launch {
                    val res = repository.removeSongFromClub(selectedClubId!!, song.id)
                    if (res.isSuccess) {
                        Toast.makeText(context, "Removed from collaborative playlist", Toast.LENGTH_SHORT).show()
                        loadClubDetails(selectedClubId!!)
                    } else {
                        Toast.makeText(context, "Could not remove song", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // COLLABORATORS & CODE DRAWER (BOTTOM SHEET)
    if (showCollaboratorsSheet && playlistDetails != null) {
        val details = playlistDetails!!
        ModalBottomSheet(
            onDismissRequest = { showCollaboratorsSheet = false },
            containerColor = AlaktraCard
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Collaborative Playlist Info",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Invite Code Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AlaktraSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, AlaktraBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "INVITE CODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AlaktraTextMuted
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "#${details.code}",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AlaktraMint
                            )
                        }

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Club Invite Code", details.code))
                                Toast.makeText(context, "Invite code #${details.code} copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Code", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Collaborators (${details.members.size})",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    items(details.members) { member ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraMint.copy(alpha = 0.2f))
                                    .border(1.dp, AlaktraMint.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Text(
                                    text = member.username.take(1).uppercase(Locale.ROOT),
                                    fontWeight = FontWeight.Bold,
                                    color = AlaktraMint
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = member.username,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            if (member.userId == details.ownerUserId) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = AlaktraMint.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "Host",
                                        color = AlaktraMint,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = AlaktraBorder.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // Action Menu items
                if (details.isOwner) {
                    OutlinedButton(
                        onClick = {
                            showCollaboratorsSheet = false
                            scope.launch {
                                val res = repository.deleteClub(details.clubId)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "Could not delete playlist", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Collaborative Playlist", fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            showCollaboratorsSheet = false
                            scope.launch {
                                val res = repository.leaveClub(details.clubId)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Left collaborative playlist", Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "Could not leave playlist", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Leave Collaborative Playlist", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ADD SONGS BOTTOM SHEET
    if (showAddSongSheet && playlistDetails != null) {
        val existingSongIds = remember(playlistDetails) {
            playlistDetails!!.songs.map { it.song.id }.toSet()
        }
        val filteredCatalog = remember(catalogSongs, searchQuery) {
            if (searchQuery.isBlank()) catalogSongs else {
                val q = searchQuery.lowercase(Locale.ROOT)
                catalogSongs.filter {
                    it.title.lowercase(Locale.ROOT).contains(q) ||
                    it.artist.lowercase(Locale.ROOT).contains(q)
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showAddSongSheet = false },
            containerColor = AlaktraSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Add Songs to Club Playlist",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = { showAddSongSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = AlaktraTextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs or artists...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AlaktraMint) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AlaktraMint,
                        unfocusedBorderColor = AlaktraBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoadingCatalog) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AlaktraMint)
                    }
                } else if (filteredCatalog.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("No songs found", color = AlaktraTextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(filteredCatalog) { song ->
                            val alreadyInClub = existingSongIds.contains(song.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SongCover(
                                    imageUrl = song.coverUrl,
                                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artist,
                                        fontSize = 12.sp,
                                        color = AlaktraTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (alreadyInClub) {
                                    Text(
                                        text = "Added",
                                        color = AlaktraMint,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val res = repository.addSongToClub(playlistDetails!!.clubId, song.id)
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "Added ${song.title}!", Toast.LENGTH_SHORT).show()
                                                    loadClubDetails(playlistDetails!!.clubId)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.AddCircle, contentDescription = "Add", tint = AlaktraMint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
