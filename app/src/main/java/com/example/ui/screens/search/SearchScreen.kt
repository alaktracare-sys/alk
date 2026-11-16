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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.ArtistProfile
import com.example.model.Song
import com.example.ui.components.SongItemRow
import com.example.ui.components.SongMenuBottomSheet
import com.example.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

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

enum class SearchFilter { ALL, ARTISTS, DOWNLOADED, FAVORITES }

@Composable
fun SearchScreen(
    repository: MusicRepository,
    audioController: AudioController,
    onNavigateToPlaylistSelect: (Song) -> Unit,
    onOpenArtist: (Int, String) -> Unit = { _, _ -> },
    onViewSongArtist: (Song) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var matchingArtists by remember { mutableStateOf<List<ArtistProfile>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var recentSearches by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val playbackState by audioController.playbackState.collectAsState()
    val downloadProgress by repository.downloadManager.downloadProgress.collectAsState()

    LaunchedEffect(Unit) {
        recentSearches = loadRecentSearches(context)
        val songsDeferred = async { repository.getSongs() }
        val artistsDeferred = async { repository.getArtists() }
        results = songsDeferred.await()
        matchingArtists = artistsDeferred.await()
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
            delay(200)
            isSearching = true
            val songsDeferred = async { repository.getSongs(query = q.ifBlank { null }) }
            val artistsDeferred = async { repository.getArtists(query = q.trim()) }
            results = songsDeferred.await()
            matchingArtists = artistsDeferred.await()
            isSearching = false
        }
    }

    val filteredResults = remember(results, selectedFilter) {
        when (selectedFilter) {
            SearchFilter.ALL -> results
            SearchFilter.ARTISTS -> emptyList()
            SearchFilter.DOWNLOADED -> results.filter { it.isDownloaded || it.localPath != null }
            SearchFilter.FAVORITES -> results.filter { it.isLiked }
        }
    }

    Scaffold(
        containerColor = AlaktraBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
            ) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp
                    ),
                    color = AlaktraTextPrimary,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 10.dp)
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        triggerSearch(it)
                    },
                    placeholder = {
                        Text(
                            "Search songs or artists",
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
                        .testTag("search_query_input")
                )

                // Filter Chips
                LazyRow(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        SearchFilterChipItem(
                            label = "All Tracks",
                            selected = selectedFilter == SearchFilter.ALL,
                            onClick = { selectedFilter = SearchFilter.ALL },
                            testTag = "search_filter_all"
                        )
                    }
                    item {
                        SearchFilterChipItem(
                            label = stringResource(R.string.search_filter_artists),
                            selected = selectedFilter == SearchFilter.ARTISTS,
                            onClick = { selectedFilter = SearchFilter.ARTISTS },
                            testTag = "search_filter_artists"
                        )
                    }
                    item {
                        SearchFilterChipItem(
                            label = "Downloaded",
                            selected = selectedFilter == SearchFilter.DOWNLOADED,
                            onClick = { selectedFilter = SearchFilter.DOWNLOADED },
                            testTag = "search_filter_downloaded"
                        )
                    }
                    item {
                        SearchFilterChipItem(
                            label = "Favorites",
                            selected = selectedFilter == SearchFilter.FAVORITES,
                            onClick = { selectedFilter = SearchFilter.FAVORITES },
                            testTag = "search_filter_favorites"
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
                } else if (selectedFilter == SearchFilter.ARTISTS) {
                    if (matchingArtists.isEmpty()) {
                        EmptySearchPlaceholder(
                            title = "No artists found",
                            subtitle = if (query.isNotBlank()) "No artist profile matches \"$query\"."
                            else "No artist profiles in the catalog yet."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = matchingArtists,
                                key = { "artist_card_${it.id}" }
                            ) { artist ->
                                SearchArtistRowCard(
                                    artist = artist,
                                    onClick = {
                                        if (query.isNotBlank()) addRecentSearch(query)
                                        onOpenArtist(artist.id, artist.name)
                                    }
                                )
                            }
                        }
                    }
                } else if (query.isEmpty() && selectedFilter == SearchFilter.ALL) {
                    if (recentSearches.isNotEmpty() || matchingArtists.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 160.dp)
                        ) {
                            if (matchingArtists.isNotEmpty()) {
                                item(key = "browse_artists_header") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.search_artists_section),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = AlaktraTextPrimary
                                        )
                                        TextButton(onClick = { selectedFilter = SearchFilter.ARTISTS }) {
                                            Text(
                                                text = "See all",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = AlaktraMint
                                            )
                                        }
                                    }
                                }
                                item(key = "browse_artists_carousel") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        items(
                                            items = matchingArtists.take(12),
                                            key = { "carousel_artist_${it.id}" }
                                        ) { artist ->
                                            SearchArtistBubble(
                                                artist = artist,
                                                onClick = { onOpenArtist(artist.id, artist.name) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }

                            if (recentSearches.isNotEmpty()) {
                                item(key = "recent_header") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp)
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
                                            .padding(horizontal = 20.dp, vertical = 5.dp)
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
                                            modifier = Modifier.size(36.dp)
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
                        }
                    } else {
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
                                    text = "Find tracks, primary artists, or featured collaborations.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlaktraTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else if (filteredResults.isEmpty() && (selectedFilter != SearchFilter.ALL || matchingArtists.isEmpty())) {
                    EmptySearchPlaceholder(
                        title = "No tracks found",
                        subtitle = if (query.isNotBlank()) "No match for \"$query\". Try a different title or artist."
                        else "No songs in this filter category."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 160.dp)
                    ) {
                        if (selectedFilter == SearchFilter.ALL && query.isNotBlank() && matchingArtists.isNotEmpty()) {
                            item(key = "matched_artists_section") {
                                Text(
                                    text = stringResource(R.string.search_artists_section),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = AlaktraTextPrimary,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = matchingArtists.take(8),
                                        key = { "matched_artist_${it.id}" }
                                    ) { artist ->
                                        SearchArtistBubble(
                                            artist = artist,
                                            onClick = {
                                                addRecentSearch(query)
                                                onOpenArtist(artist.id, artist.name)
                                            }
                                        )
                                    }
                                }
                                if (filteredResults.isNotEmpty()) {
                                    Text(
                                        text = "Songs",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        color = AlaktraTextPrimary,
                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp)
                                    )
                                }
                            }
                        }

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
            onViewArtist = { onViewSongArtist(song) },
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
private fun SearchFilterChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AlaktraMint,
            selectedLabelColor = Color(0xFF0A2218),
            containerColor = AlaktraSurface,
            labelColor = AlaktraTextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = if (selected) AlaktraMint else AlaktraBorder,
            enabled = true,
            selected = selected
        ),
        modifier = Modifier.testTag(testTag)
    )
}

@Composable
private fun SearchArtistBubble(
    artist: ArtistProfile,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .testTag("search_artist_bubble_${artist.id}")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(AlaktraMint.copy(alpha = 0.3f), AlaktraCyan.copy(alpha = 0.3f))))
                .border(1.5.dp, AlaktraBorder, CircleShape)
        ) {
            if (artist.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = remember(artist.avatarUrl) {
                        ImageRequest.Builder(context).data(artist.avatarUrl).crossfade(true).build()
                    },
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = artist.name.take(1).uppercase(Locale.getDefault()),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    color = AlaktraTextPrimary
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = artist.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AlaktraTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (artist.isVerified) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = "Verified",
                    tint = AlaktraCyan,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchArtistRowCard(
    artist: ArtistProfile,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AlaktraCard)
            .border(1.dp, AlaktraBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("search_artist_row_${artist.id}")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan)))
        ) {
            if (artist.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = remember(artist.avatarUrl) {
                        ImageRequest.Builder(context).data(artist.avatarUrl).crossfade(true).build()
                    },
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = artist.name.take(1).uppercase(Locale.getDefault()),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = Color(0xFF042014)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isVerified) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Verified",
                        tint = AlaktraCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Artist • ${artist.songCount} ${if (artist.songCount == 1) "track" else "tracks"}",
                style = MaterialTheme.typography.bodySmall,
                color = AlaktraTextSecondary
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open artist",
            tint = AlaktraTextSecondary
        )
    }
}

@Composable
private fun EmptySearchPlaceholder(
    title: String,
    subtitle: String
) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AlaktraTextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AlaktraTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
