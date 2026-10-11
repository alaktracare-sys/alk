package com.example.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.MusicRepository
import com.example.model.Playlist
import com.example.model.Song
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: MusicRepository,
    onNavigateToPlaylist: (Int, String) -> Unit,
    onNavigateToLikedSongs: () -> Unit,
    onNavigateToDownloadedSongs: () -> Unit
) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    val downloadedSongs by repository.downloadedSongsFlow.collectAsState(initial = emptyList())
    var likedSongsCount by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToEdit by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val cached = repository.getCachedPlaylists()
        if (cached.isNotEmpty()) {
            playlists = cached
        }
        likedSongsCount = repository.authPreferences.getLikedSongIds().size

        val fresh = repository.getPlaylists(forceRefresh = true)
        playlists = fresh
    }

    Scaffold(
        containerColor = AlaktraBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
            ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Your Library",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp
                    ),
                    color = AlaktraTextPrimary
                )

                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AlaktraSurface)
                        .border(1.dp, AlaktraBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Playlist",
                        tint = AlaktraMint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 150.dp)
            ) {
                // Liked Songs card
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToLikedSongs)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF4F46E5), Color(0xFF06B6D4), AlaktraMint)
                                    )
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Liked Songs",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Auto playlist • $likedSongsCount tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                        }
                    }
                }

                // Permanent Downloaded Songs / Offline Music Card
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToDownloadedSongs)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF0F766E), Color(0xFF14B8A6), AlaktraMint)
                                    )
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.DownloadDone,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Downloaded Songs",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.OfflinePin,
                                    contentDescription = "Offline ready",
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Permanent offline playlist • ${downloadedSongs.size} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                        }
                    }
                }

                // Playlists List
                if (playlists.isEmpty()) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 24.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No custom playlists yet",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap '+' to create your first music collection.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(playlists) { playlist ->
                            var menuExpanded by remember { mutableStateOf(false) }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToPlaylist(playlist.id, playlist.name) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(AlaktraSurface)
                                        .border(1.dp, AlaktraBorder, RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = null,
                                        tint = AlaktraMint,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        ),
                                        color = AlaktraTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Playlist • ${playlist.songCount} tracks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AlaktraTextSecondary
                                    )
                                }

                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Options",
                                            tint = AlaktraTextSecondary
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        modifier = Modifier
                                            .background(AlaktraCard)
                                            .border(1.dp, AlaktraBorder, RoundedCornerShape(12.dp))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename", color = AlaktraTextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = AlaktraMint) },
                                            onClick = {
                                                menuExpanded = false
                                                playlistToEdit = playlist
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = Color(0xFFEF4444)) },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444)) },
                                            onClick = {
                                                menuExpanded = false
                                                playlistToDelete = playlist
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
    }

    // Create Playlist Dialog
    if (showCreateDialog) {
        var newPlaylistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = AlaktraCard,
            title = { Text("Give your playlist a name", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("My Playlist #1", color = AlaktraTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AlaktraTextPrimary,
                        unfocusedTextColor = AlaktraTextPrimary,
                        focusedBorderColor = AlaktraMint,
                        unfocusedBorderColor = AlaktraBorder
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newPlaylistName.trim().ifEmpty { "New Playlist" }
                        scope.launch {
                            val created = repository.createPlaylist(name)
                            playlists = playlists + created
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12))
                ) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // Rename Playlist Dialog
    playlistToEdit?.let { playlist ->
        var editName by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { playlistToEdit = null },
            containerColor = AlaktraCard,
            title = { Text("Rename Playlist", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AlaktraTextPrimary,
                        unfocusedTextColor = AlaktraTextPrimary,
                        focusedBorderColor = AlaktraMint,
                        unfocusedBorderColor = AlaktraBorder
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = editName.trim().ifEmpty { playlist.name }
                        scope.launch {
                            val updated = repository.renamePlaylist(playlist.id, name)
                            playlists = playlists.map { if (it.id == playlist.id) updated else it }
                            playlistToEdit = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToEdit = null }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // Delete Confirmation Dialog
    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = AlaktraCard,
            title = { Text("Delete Playlist?", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete \"${playlist.name}\"?", color = AlaktraTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.deletePlaylist(playlist.id)
                            playlists = playlists.filter { it.id != playlist.id }
                            playlistToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }
}

