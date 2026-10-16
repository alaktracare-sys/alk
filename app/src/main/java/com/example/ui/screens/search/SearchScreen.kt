package com.example.ui.screens.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import org.json.JSONArray

private const val SEARCH_PREFS_NAME = "alaktra_search_history"
private const val KEY_RECENT_SEARCHES = "recent_queries"
private const val MAX_RECENT_SEARCHES = 15

private fun loadRecentSearches(context: Context): List<String> {
    val prefs = context.getSharedPreferences(SEARCH_PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optString(i)
            if (!item.isNullOrBlank()) {
                list.add(item)
            }
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveRecentSearchesToPrefs(context: Context, list: List<String>) {
    val prefs = context.getSharedPreferences(SEARCH_PREFS_NAME, Context.MODE_PRIVATE)
    val array = JSONArray()
    list.take(MAX_RECENT_SEARCHES).forEach { array.put(it) }
    prefs.edit().putString(KEY_RECENT_SEARCHES, array.toString()).apply()
}

enum class SearchFilter { ALL, DOWNLOADED, FAVORITES }

@Composable
fun SearchScreen(
    repository: MusicRepository,
    audioController: AudioController,
    onNavigateToPlaylistSelect: (Song) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var recentSearches by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val playbackState by audioController.playbackState.collectAsState()
    val downloadProgress by repository.downloadManager.downloadProgress.collectAsState()

    // Load persistent search history on launch
    LaunchedEffect(Unit) {
        recentSearches = loadRecentSearches(context)
        allSongs = repository.getSongs()
        results = allSongs
    }

    fun addRecentSearch(term: String) {
        val trimmed = term.trim()
        if (trimmed.length < 2) return
        val updated = (listOf(trimmed) + recentSearches.filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX_RECENT_SEARCHES)
        recentSearches = updated
        saveRecentSearchesToPrefs(context, updated)
    }

    fun removeRecentSearch(term: String) {
        val updated = recentSearches.filterNot { it.equals(term, ignoreCase = true) }
        recentSearches = updated
        saveRecentSearchesToPrefs(context, updated)
    }

    fun clearAllRecentSearches() {
        recentSearches = emptyList()
        saveRecentSearchesToPrefs(context, emptyList())
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

                // Search Bar with Real Keyboard Search action
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        triggerSearch(it)
                    },
                    placeholder = {
                        Text(
                            "Search songs, artists, or albums...",
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.isNotBlank()) {
                                addRecentSearch(query)
                                triggerSearch(query)
                            }
                            keyboardController?.hide()
                        }
                    ),
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
                    // Real, Persistent Recent Searches Section (Browse Genres removed)
                    if (recentSearches.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 140.dp)
                        ) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "Recent Searches",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )
                                    TextButton(onClick = { clearAllRecentSearches() }) {
                                        Text(
                                            text = "Clear all",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = AlaktraMint
                                        )
                                    }
                                }
                            }

                            items(
                                items = recentSearches,
                                key = { "recent_$it" },
                                contentType = { "recent_search_row" }
                            ) { term ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            query = term
                                            addRecentSearch(term)
                                            triggerSearch(term)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = AlaktraTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = term,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = AlaktraTextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { removeRecentSearch(term) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove search",
                                            tint = AlaktraTextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Empty State when no recent searches yet
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp, vertical = 48.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .background(AlaktraSurface)
                                        .border(1.dp, AlaktraBorder, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = AlaktraMint,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(18.dp))
                                Text(
                                    text = "Search your music",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Find tracks by song title, artist name, or album.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlaktraTextSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
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
                        itemsIndexed(
                            items = filteredResults,
                            key = { _, song -> song.id },
                            contentType = { _, _ -> "song_row" }
                        ) { index, song ->
                            val isCurrent = playbackState.currentSong?.id == song.id
                            SongItemRow(
                                song = song,
                                isPlaying = isCurrent && playbackState.isPlaying,
                                downloadProgress = downloadProgress[song.id],
                                onSongClick = {
                                    if (query.isNotBlank()) {
                                        addRecentSearch(query)
                                    }
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
                selectedSongForMenu = null
            },
            onAddToQueue = {
                audioController.addToQueue(song)
                selectedSongForMenu = null
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
