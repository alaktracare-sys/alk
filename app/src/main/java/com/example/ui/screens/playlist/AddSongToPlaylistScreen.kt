package com.example.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.MusicRepository
import com.example.model.Song
import com.example.ui.components.SongCover
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongToPlaylistScreen(
    playlistId: Int,
    repository: MusicRepository,
    onBack: () -> Unit
) {
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlistSongIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(playlistId) {
        allSongs = repository.getSongs()
        val playlistSongs = repository.getPlaylistSongs(playlistId)
        playlistSongIds = playlistSongs.map { it.id }.toSet()
    }

    val filteredSongs = remember(allSongs, searchQuery) {
        if (searchQuery.isBlank()) {
            allSongs
        } else {
            allSongs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Tracks to Playlist", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AlaktraTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AlaktraBackground)
            )
        },
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
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
            ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search tracks to add...", color = AlaktraTextMuted) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = AlaktraTextSecondary)
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AlaktraTextPrimary,
                    unfocusedTextColor = AlaktraTextPrimary,
                    focusedContainerColor = AlaktraSurface,
                    unfocusedContainerColor = AlaktraSurface,
                    focusedBorderColor = AlaktraMint,
                    unfocusedBorderColor = AlaktraBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                items(filteredSongs) { song ->
                    val isAdded = playlistSongIds.contains(song.id)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        SongCover(imageUrl = song.coverUrl, size = 52.dp)

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                color = AlaktraTextPrimary,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1
                            )
                            Text(
                                text = song.artist,
                                color = AlaktraTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (isAdded) {
                                        repository.removeSongFromPlaylist(playlistId, song.id)
                                        playlistSongIds = playlistSongIds - song.id
                                    } else {
                                        repository.addSongToPlaylist(playlistId, song.id)
                                        playlistSongIds = playlistSongIds + song.id
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isAdded) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = if (isAdded) "Added" else "Add",
                                tint = if (isAdded) AlaktraMint else AlaktraTextSecondary
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

