package com.example.ui.screens.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.Song
import com.example.ui.components.SongItemRow
import com.example.ui.components.SongMenuBottomSheet
import com.example.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BrowseGenre(val title: String, val gradient: List<Color>, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val browseGenres = listOf(
    BrowseGenre("Electronic", listOf(Color(0xFF6366F1), Color(0xFF3B82F6)), Icons.Default.GraphicEq),
    BrowseGenre("Chill & Lo-Fi", listOf(Color(0xFF10B981), Color(0xFF047857)), Icons.Default.Spa),
    BrowseGenre("Deep Focus", listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9)), Icons.Default.Headphones),
    BrowseGenre("Ambient Wave", listOf(Color(0xFF06B6D4), Color(0xFF0284C7)), Icons.Default.Waves),
    BrowseGenre("Hip-Hop / Beats", listOf(Color(0xFFF59E0B), Color(0xFFD97706)), Icons.Default.Album),
    BrowseGenre("Indie & Alt", listOf(Color(0xFFEC4899), Color(0xFFBE185D)), Icons.Default.MusicNote)
)

enum class SearchFilter { ALL, DOWNLOADED, FAVORITES }

@Composable
fun SearchScreen(
    repository: MusicRepository,
    audioController: AudioController,
    onNavigateToPlaylistSelect: (Song) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var recentSearches by remember { mutableStateOf(listOf("synth", "acoustic", "lofi")) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val playbackState by audioController.playbackState.collectAsState()
    val downloadProgress by repository.downloadManager.downloadProgress.collectAsState()

    LaunchedEffect(Unit) {
        allSongs = repository.getSongs()
        results = allSongs
    }

    fun triggerSearch(q: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(200) // debounce
            isSearching = true
            results = repository.getSongs(query = q.ifBlank { null })
            isSearching = false
        }
    }

    val filteredResults = remember(results, selectedFilter) {
        when (selectedFilter) {
            SearchFilter.ALL -> results
            SearchFilter.DOWNLOADED -> results.filter { it.isDownloaded || it.localPath != null }
            SearchFilter.FAVORITES -> results.filter { it.isLiked }
        }
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
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp
                ),
                color = AlaktraTextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            // Search Bar
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    triggerSearch(it)
                },
                placeholder = {
                    Text(
                        "Search songs, artists, or genres...",
                        color = AlaktraTextMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = if (query.isNotEmpty()) AlaktraMint else AlaktraTextSecondary
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            triggerSearch("")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = AlaktraTextSecondary
                            )
                        }
                    }
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
                    .padding(horizontal = 20.dp)
            )

            // Filter Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == SearchFilter.ALL,
                        onClick = { selectedFilter = SearchFilter.ALL },
                        label = { Text("All Tracks") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AlaktraMint,
                            selectedLabelColor = Color(0xFF0A2218),
                            containerColor = AlaktraSurface,
                            labelColor = AlaktraTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (selectedFilter == SearchFilter.ALL) AlaktraMint else AlaktraBorder,
                            enabled = true,
                            selected = selectedFilter == SearchFilter.ALL
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == SearchFilter.DOWNLOADED,
                        onClick = { selectedFilter = SearchFilter.DOWNLOADED },
                        label = { Text("Downloaded") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AlaktraMint,
                            selectedLabelColor = Color(0xFF0A2218),
                            containerColor = AlaktraSurface,
                            labelColor = AlaktraTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (selectedFilter == SearchFilter.DOWNLOADED) AlaktraMint else AlaktraBorder,
                            enabled = true,
                            selected = selectedFilter == SearchFilter.DOWNLOADED
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == SearchFilter.FAVORITES,
                        onClick = { selectedFilter = SearchFilter.FAVORITES },
                        label = { Text("Favorites") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AlaktraMint,
                            selectedLabelColor = Color(0xFF0A2218),
                            containerColor = AlaktraSurface,
                            labelColor = AlaktraTextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (selectedFilter == SearchFilter.FAVORITES) AlaktraMint else AlaktraBorder,
                            enabled = true,
                            selected = selectedFilter == SearchFilter.FAVORITES
                        )
                    )
                }
            }

            if (isSearching) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(color = AlaktraMint)
                }
            } else if (query.isEmpty() && selectedFilter == SearchFilter.ALL) {
                // Browse Categories & Recent Searches
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    // Recent Searches
                    if (recentSearches.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Recent Searches",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary,
                                    modifier = Modifier.clickable { recentSearches = emptyList() }
                                )
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(recentSearches) { term ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(AlaktraSurface)
                                            .border(1.dp, AlaktraBorder, RoundedCornerShape(20.dp))
                                            .clickable {
                                                query = term
                                                triggerSearch(term)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = AlaktraTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = term,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AlaktraTextPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    // Browse Categories
                    item {
                        Text(
                            text = "Browse Genres",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                    }

                    item {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        ) {
                            val columns = when {
                                maxWidth >= 700.dp -> 4
                                maxWidth >= 460.dp -> 3
                                else -> 2
                            }
                            val rows = browseGenres.chunked(columns)
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                rows.forEach { rowGenres ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        rowGenres.forEach { genre ->
                                            GenreCard(
                                                genre = genre,
                                                onClick = {
                                                    query = genre.title
                                                    triggerSearch(genre.title)
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        val remainder = columns - rowGenres.size
                                        repeat(remainder) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (filteredResults.isEmpty()) {
                // No Results State
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = AlaktraTextMuted,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No tracks found",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (query.isNotBlank()) "No match for \"$query\". Try a different title or artist." else "No songs in this filter category.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AlaktraTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                // Search Results List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    itemsIndexed(filteredResults) { index, song ->
                        val isCurrent = playbackState.currentSong?.id == song.id
                        SongItemRow(
                            song = song,
                            isPlaying = isCurrent && playbackState.isPlaying,
                            downloadProgress = downloadProgress[song.id],
                            onSongClick = {
                                val src = if (query.isNotBlank()) "Search: \"$query\"" else "Browse"
                                audioController.playQueue(filteredResults, index, source = src)
                            },
                            onLikeToggle = {
                                scope.launch {
                                    val isLiked = repository.toggleLike(song)
                                    audioController.updateSongLiked(song.id, isLiked)
                                    results = results.map { if (it.id == song.id) it.copy(isLiked = isLiked) else it }
                                }
                            },
                            onMoreClick = { selectedSongForMenu = song }
                        )
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
            onToggleDownload = {
                scope.launch {
                    if (isDownloaded) {
                        repository.downloadManager.removeDownload(song.id)
                        results = results.map { if (it.id == song.id) it.copy(isDownloaded = false, localPath = null) else it }
                    } else {
                        repository.downloadManager.downloadSong(song)
                        val path = repository.downloadManager.getLocalPath(song.id)
                        results = results.map { if (it.id == song.id) it.copy(isDownloaded = true, localPath = path) else it }
                    }
                }
            }
        )
    }
}

@Composable
private fun GenreCard(
    genre: BrowseGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(84.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(colors = genre.gradient))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = genre.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            ),
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Icon(
            imageVector = genre.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .size(32.dp)
                .align(Alignment.BottomEnd)
        )
    }
}

