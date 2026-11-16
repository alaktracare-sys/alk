package com.example.ui.screens.artist

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.ArtistDetailBundle
import com.example.model.ArtistProfile
import com.example.model.Song
import com.example.model.UserArtistStatus
import com.example.ui.components.SongCover
import com.example.ui.theme.*
import kotlinx.coroutines.launch

private enum class StudioTab { PROFILE, RELEASE, CATALOG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistStudioScreen(
    repository: MusicRepository,
    audioController: AudioController,
    onBack: () -> Unit,
    onOpenArtistProfile: (Int, String) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = remember { repository.authPreferences.getUser() }

    var isLoading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<UserArtistStatus?>(null) }
    var allCatalogArtists by remember { mutableStateOf<List<ArtistProfile>>(emptyList()) }
    var artistDetail by remember { mutableStateOf<ArtistDetailBundle?>(null) }
    var activeTab by remember { mutableStateOf(StudioTab.PROFILE) }

    // Claim form state (when listener account is not yet linked to an artist profile)
    var claimExistingMode by remember { mutableStateOf(true) }
    var claimSearch by remember { mutableStateOf("") }
    var selectedUnclaimedArtist by remember { mutableStateOf<ArtistProfile?>(null) }
    var requestedNewStageName by remember { mutableStateOf("") }
    var verificationNote by remember { mutableStateOf("") }
    var isSubmittingClaim by remember { mutableStateOf(false) }

    // Studio Profile Edit state
    var stageName by remember { mutableStateOf("") }
    var bioText by remember { mutableStateOf("") }
    var pickedAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var pickedBannerUri by remember { mutableStateOf<Uri?>(null) }
    var isSavingProfile by remember { mutableStateOf(false) }

    // Studio Track Release state
    var trackTitle by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf("#00E599") }
    var pickedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var pickedCoverUri by remember { mutableStateOf<Uri?>(null) }
    var featuredArtistIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    var customFeaturedNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var isUploadingTrack by remember { mutableStateOf(false) }

    // My Catalog credit allotment modal
    var songToEditCredits by remember { mutableStateOf<Song?>(null) }
    var isSavingCredits by remember { mutableStateOf(false) }

    // Zero-permission Android Photo Pickers & Audio Content Picker (Google Play Policy compliant)
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickedAvatarUri = uri
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickedBannerUri = uri
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickedCoverUri = uri
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedAudioUri = uri
    }

    fun loadStudioState() {
        scope.launch {
            isLoading = true
            val fetchedArtists = repository.getArtists()
            allCatalogArtists = fetchedArtists

            val statusRes = repository.getUserArtistStatus()
            statusRes.onSuccess { st ->
                status = st
                st.claimedArtist?.let { claimed ->
                    stageName = claimed.name
                    bioText = claimed.bio
                    repository.getArtistDetail(claimed.id, claimed.name).onSuccess { detail ->
                        artistDetail = detail
                    }
                }
            }
            if (fetchedArtists.none { it.claimedByUserId == null }) {
                claimExistingMode = false
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadStudioState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (status?.isArtist == true) "Artist Studio" else "Artist Profile & Segregation",
                        color = AlaktraTextPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("artist_studio_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AlaktraTextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadStudioState() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh status",
                            tint = AlaktraMint
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AlaktraBackground)
            )
        },
        containerColor = AlaktraBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .testTag("artist_studio_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AlaktraMint)
                }
            } else if (user == null) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Sign in to your listener account first to claim or manage an Artist Profile.",
                        color = AlaktraTextSecondary
                    )
                }
            } else if (status?.isArtist == true && status?.claimedArtist != null) {
                val claimed = status!!.claimedArtist!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // Claimed Artist Identity Banner Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan)))
                            ) {
                                if (claimed.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(claimed.avatarUrl).crossfade(true).build(),
                                        contentDescription = claimed.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF042014))
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = claimed.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        color = AlaktraTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (claimed.isVerified) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Icon(
                                            imageVector = Icons.Default.Verified,
                                            contentDescription = "Verified",
                                            tint = AlaktraCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Segregated Artist Identity • Linked to @${user.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraMint
                                )
                            }
                            OutlinedButton(
                                onClick = { onOpenArtistProfile(claimed.id, claimed.name) },
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, AlaktraBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("studio_view_public_profile_button")
                            ) {
                                Text("Public Page", color = AlaktraTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Studio Navigation Tabs
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StudioTabChip(
                            title = "Profile",
                            icon = Icons.Default.Badge,
                            selected = activeTab == StudioTab.PROFILE,
                            onClick = { activeTab = StudioTab.PROFILE },
                            modifier = Modifier.weight(1f).testTag("studio_tab_profile")
                        )
                        StudioTabChip(
                            title = "Release Track",
                            icon = Icons.Default.CloudUpload,
                            selected = activeTab == StudioTab.RELEASE,
                            onClick = { activeTab = StudioTab.RELEASE },
                            modifier = Modifier.weight(1f).testTag("studio_tab_release")
                        )
                        StudioTabChip(
                            title = "My Catalog",
                            icon = Icons.Default.LibraryMusic,
                            selected = activeTab == StudioTab.CATALOG,
                            onClick = { activeTab = StudioTab.CATALOG },
                            modifier = Modifier.weight(1f).testTag("studio_tab_catalog")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    when (activeTab) {
                        StudioTab.PROFILE -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, AlaktraBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    Text(
                                        text = "Artist Identity & Artwork",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )

                                    OutlinedTextField(
                                        value = stageName,
                                        onValueChange = { stageName = it },
                                        label = { Text("Stage / Artist Name") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("studio_stage_name_input")
                                    )

                                    OutlinedTextField(
                                        value = bioText,
                                        onValueChange = { bioText = it },
                                        label = { Text("Artist Bio") },
                                        minLines = 3,
                                        maxLines = 6,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("studio_bio_input")
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, if (pickedAvatarUri != null) AlaktraMint else AlaktraBorder),
                                            modifier = Modifier.weight(1f).height(48.dp)
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = AlaktraMint, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (pickedAvatarUri != null) "Avatar Selected" else "Pick Avatar",
                                                color = AlaktraTextPrimary,
                                                fontSize = 13.sp
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                bannerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, if (pickedBannerUri != null) AlaktraMint else AlaktraBorder),
                                            modifier = Modifier.weight(1f).height(48.dp)
                                        ) {
                                            Icon(Icons.Default.Panorama, contentDescription = null, tint = AlaktraCyan, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (pickedBannerUri != null) "Banner Selected" else "Pick Banner",
                                                color = AlaktraTextPrimary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isSavingProfile = true
                                                val res = repository.updateArtistProfile(
                                                    artistId = claimed.id,
                                                    name = stageName,
                                                    bio = bioText,
                                                    avatarUri = pickedAvatarUri,
                                                    bannerUri = pickedBannerUri
                                                )
                                                isSavingProfile = false
                                                res.onSuccess { updated ->
                                                    pickedAvatarUri = null
                                                    pickedBannerUri = null
                                                    status = status?.copy(claimedArtist = updated)
                                                    Toast.makeText(context, "Artist profile updated!", Toast.LENGTH_SHORT).show()
                                                    loadStudioState()
                                                }.onFailure { err ->
                                                    Toast.makeText(context, err.message ?: "Update failed", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        enabled = !isSavingProfile && stageName.isNotBlank(),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF042014)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("save_artist_profile_button")
                                    ) {
                                        if (isSavingProfile) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF042014))
                                        } else {
                                            Text("Save Artist Profile", fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                        }

                        StudioTab.RELEASE -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, AlaktraBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    Text(
                                        text = "Release New Track as ${claimed.name}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )
                                    Text(
                                        text = "Primary artist ownership is automatically allotted to your verified profile.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AlaktraTextSecondary
                                    )

                                    OutlinedTextField(
                                        value = trackTitle,
                                        onValueChange = { trackTitle = it },
                                        label = { Text("Song Title") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("studio_track_title_input")
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedButton(
                                            onClick = { audioPicker.launch("audio/*") },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, if (pickedAudioUri != null) AlaktraMint else AlaktraBorder),
                                            modifier = Modifier.weight(1f).height(50.dp)
                                        ) {
                                            Icon(Icons.Default.AudioFile, contentDescription = null, tint = AlaktraMint, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (pickedAudioUri != null) "Audio Ready" else "Select Audio",
                                                color = AlaktraTextPrimary,
                                                fontSize = 13.sp
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, if (pickedCoverUri != null) AlaktraMint else AlaktraBorder),
                                            modifier = Modifier.weight(1f).height(50.dp)
                                        ) {
                                            Icon(Icons.Default.Image, contentDescription = null, tint = AlaktraCyan, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (pickedCoverUri != null) "Cover Ready" else "Select Cover",
                                                color = AlaktraTextPrimary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Player Accent Color",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )
                                    SwatchPickerRow(
                                        selectedHex = selectedColorHex,
                                        onSelectHex = { selectedColorHex = it }
                                    )

                                    FeaturedCollaboratorsSelector(
                                        primaryArtistId = claimed.id,
                                        catalogArtists = allCatalogArtists,
                                        selectedFeaturedIds = featuredArtistIds,
                                        onToggleFeaturedId = { id ->
                                            featuredArtistIds = if (featuredArtistIds.contains(id)) featuredArtistIds - id else featuredArtistIds + id
                                        },
                                        customFeaturedNames = customFeaturedNames,
                                        onAddCustomName = { name ->
                                            if (customFeaturedNames.none { it.equals(name, ignoreCase = true) } &&
                                                !name.equals(claimed.name, ignoreCase = true)
                                            ) {
                                                customFeaturedNames = customFeaturedNames + name
                                            }
                                        },
                                        onRemoveCustomName = { name ->
                                            customFeaturedNames = customFeaturedNames.filterNot { it.equals(name, ignoreCase = true) }
                                        }
                                    )

                                    Button(
                                        onClick = {
                                            val audioUri = pickedAudioUri ?: return@Button
                                            val coverUri = pickedCoverUri ?: return@Button
                                            scope.launch {
                                                isUploadingTrack = true
                                                val res = repository.uploadStudioSong(
                                                    title = trackTitle,
                                                    colorHex = selectedColorHex,
                                                    primaryArtistId = claimed.id,
                                                    primaryArtistName = claimed.name,
                                                    featuredArtistIds = featuredArtistIds,
                                                    featuredArtistNames = customFeaturedNames,
                                                    audioUri = audioUri,
                                                    coverUri = coverUri
                                                )
                                                isUploadingTrack = false
                                                res.onSuccess { song ->
                                                    trackTitle = ""
                                                    pickedAudioUri = null
                                                    pickedCoverUri = null
                                                    featuredArtistIds = emptyList()
                                                    customFeaturedNames = emptyList()
                                                    Toast.makeText(context, "Released \"${song.title}\"!", Toast.LENGTH_SHORT).show()
                                                    loadStudioState()
                                                    activeTab = StudioTab.CATALOG
                                                }.onFailure { err ->
                                                    Toast.makeText(context, err.message ?: "Release failed", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        enabled = !isUploadingTrack && trackTitle.isNotBlank() && pickedAudioUri != null && pickedCoverUri != null,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF042014)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("studio_release_track_button")
                                    ) {
                                        if (isUploadingTrack) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF042014))
                                        } else {
                                            Text("Publish to Catalog", fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                        }

                        StudioTab.CATALOG -> {
                            val primaryReleases = artistDetail?.primarySongs.orEmpty()
                            val featuredAppearances = artistDetail?.featuredSongs.orEmpty()

                            Text(
                                text = "Primary Releases (${primaryReleases.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (primaryReleases.isEmpty()) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No primary tracks allotted yet. Release a track or allot existing tracks in Studio.",
                                        color = AlaktraTextSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    primaryReleases.forEachIndexed { idx, song ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, AlaktraBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        audioController.playQueue(primaryReleases, idx, source = "${claimed.name} Studio")
                                                    }
                                                    .padding(12.dp)
                                            ) {
                                                SongCover(imageUrl = song.coverUrl, size = 48.dp, cornerRadius = 10.dp)
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = song.title,
                                                        fontWeight = FontWeight.Bold,
                                                        color = AlaktraTextPrimary,
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
                                                OutlinedButton(
                                                    onClick = { songToEditCredits = song },
                                                    shape = RoundedCornerShape(18.dp),
                                                    border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.5f)),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Icon(Icons.Default.GroupAdd, contentDescription = null, tint = AlaktraMint, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text("Credits", color = AlaktraMint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (featuredAppearances.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "Featured Appearances (${featuredAppearances.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    featuredAppearances.forEachIndexed { idx, song ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = AlaktraSurface),
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, AlaktraBorder),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    audioController.playQueue(featuredAppearances, idx, source = "${claimed.name} • Featured")
                                                }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(12.dp)
                                            ) {
                                                SongCover(imageUrl = song.coverUrl, size = 44.dp, cornerRadius = 8.dp)
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(song.title, fontWeight = FontWeight.SemiBold, color = AlaktraTextPrimary, maxLines = 1)
                                                    Text(song.artist, fontSize = 12.sp, color = AlaktraTextSecondary, maxLines = 1)
                                                }
                                                Text("Featured", fontSize = 11.sp, color = Color(0xFFC4B5FD), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(160.dp))
                }
            } else {
                // Normal Listener Segregation View: Pending Claim OR Submit Claim Form
                val pending = status?.pendingClaim
                val unclaimedArtists = remember(allCatalogArtists, claimSearch) {
                    val q = claimSearch.trim().lowercase()
                    allCatalogArtists
                        .filter { it.claimedByUserId == null }
                        .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 700.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Explanation Banner: Listener vs Artist Segregation
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, AlaktraBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraCyan.copy(alpha = 0.16f))
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AlaktraCyan)
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Listener & Artist Segregation",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AlaktraTextPrimary,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Normal listener accounts (@${user.username}) stay separate from public catalog artists. Submit a claim to verify and link your stage profile.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                        }
                    }

                    if (pending != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("artist_pending_claim_card")
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(18.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color(0xFFF59E0B))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Claim Request Pending Verification",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFBBF24),
                                        fontSize = 16.sp
                                    )
                                }
                                Text(
                                    text = "Requested Artist Profile: ${pending.requestedName}",
                                    color = AlaktraTextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (pending.note.isNotBlank()) {
                                    Text(
                                        text = "Note: ${pending.note}",
                                        color = AlaktraTextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    text = "Approve this request from the Studio Web Portal (Artists & Claim Requests tab) to unlock Artist Studio on this account.",
                                    color = AlaktraTextMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = { loadStudioState() },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, AlaktraMint)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AlaktraMint, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Check Approval Status", color = AlaktraMint, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Claim Existing vs Request New Stage Profile
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = claimExistingMode,
                                onClick = { claimExistingMode = true },
                                label = { Text("Claim Catalog Artist") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AlaktraMint,
                                    selectedLabelColor = Color(0xFF042014),
                                    containerColor = AlaktraSurface,
                                    labelColor = AlaktraTextSecondary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = !claimExistingMode,
                                onClick = { claimExistingMode = false },
                                label = { Text("New Artist Profile") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AlaktraMint,
                                    selectedLabelColor = Color(0xFF042014),
                                    containerColor = AlaktraSurface,
                                    labelColor = AlaktraTextSecondary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, AlaktraBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(18.dp)
                            ) {
                                if (claimExistingMode) {
                                    Text(
                                        text = "Select Unclaimed Catalog Profile",
                                        fontWeight = FontWeight.Bold,
                                        color = AlaktraTextPrimary
                                    )
                                    OutlinedTextField(
                                        value = claimSearch,
                                        onValueChange = { claimSearch = it },
                                        placeholder = { Text("Filter unclaimed artists...", color = AlaktraTextMuted) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        unclaimedArtists.take(8).forEach { candidate ->
                                            val isSelected = selectedUnclaimedArtist?.id == candidate.id
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (isSelected) AlaktraMint.copy(alpha = 0.14f) else AlaktraSurface)
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) AlaktraMint else AlaktraBorder,
                                                        RoundedCornerShape(12.dp)
                                                    )
                                                    .clickable { selectedUnclaimedArtist = candidate }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { selectedUnclaimedArtist = candidate },
                                                    colors = RadioButtonDefaults.colors(selectedColor = AlaktraMint)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(candidate.name, fontWeight = FontWeight.Bold, color = AlaktraTextPrimary)
                                                    Text(
                                                        "${candidate.songCount} catalog ${if (candidate.songCount == 1) "track" else "tracks"}",
                                                        fontSize = 12.sp,
                                                        color = AlaktraTextSecondary
                                                    )
                                                }
                                            }
                                        }
                                        if (unclaimedArtists.isEmpty()) {
                                            Text(
                                                text = "No matching unclaimed catalog artists. Switch to \"New Artist Profile\" to request a brand-new stage name.",
                                                color = AlaktraTextMuted,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Request New Stage Profile",
                                        fontWeight = FontWeight.Bold,
                                        color = AlaktraTextPrimary
                                    )
                                    OutlinedTextField(
                                        value = requestedNewStageName,
                                        onValueChange = { requestedNewStageName = it },
                                        label = { Text("Official Stage Name") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = studioTextFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("artist_claim_stage_name_input")
                                    )
                                }

                                OutlinedTextField(
                                    value = verificationNote,
                                    onValueChange = { verificationNote = it },
                                    label = { Text("Verification Note / Social Handle (Optional)") },
                                    minLines = 2,
                                    maxLines = 4,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = studioTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                val resolvedClaimName = if (claimExistingMode) {
                                    selectedUnclaimedArtist?.name.orEmpty()
                                } else {
                                    requestedNewStageName.trim()
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isSubmittingClaim = true
                                            val res = repository.submitArtistClaim(
                                                artistId = if (claimExistingMode) selectedUnclaimedArtist?.id else null,
                                                requestedName = resolvedClaimName,
                                                note = verificationNote
                                            )
                                            isSubmittingClaim = false
                                            res.onSuccess {
                                                Toast.makeText(context, "Artist claim submitted for verification!", Toast.LENGTH_SHORT).show()
                                                loadStudioState()
                                            }.onFailure { err ->
                                                Toast.makeText(context, err.message ?: "Failed to submit claim", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = !isSubmittingClaim && resolvedClaimName.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF042014)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("submit_artist_claim_button")
                                ) {
                                    if (isSubmittingClaim) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF042014))
                                    } else {
                                        Text("Submit Artist Claim", fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(140.dp))
                }
            }
        }
    }

    val editingSong = songToEditCredits
    val currentClaimed = status?.claimedArtist
    if (editingSong != null && currentClaimed != null) {
        EditSongCreditsDialog(
            song = editingSong,
            primaryArtist = currentClaimed,
            catalogArtists = allCatalogArtists,
            isSaving = isSavingCredits,
            onDismiss = { songToEditCredits = null },
            onSave = { newTitle, featIds, featNames ->
                scope.launch {
                    isSavingCredits = true
                    val res = repository.allotStudioSong(
                        songId = editingSong.id,
                        title = newTitle,
                        primaryArtistId = currentClaimed.id,
                        featuredArtistIds = featIds,
                        featuredArtistNames = featNames
                    )
                    isSavingCredits = false
                    res.onSuccess {
                        songToEditCredits = null
                        Toast.makeText(context, "Track credits updated!", Toast.LENGTH_SHORT).show()
                        loadStudioState()
                    }.onFailure { err ->
                        Toast.makeText(context, err.message ?: "Failed to update credits", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun StudioTabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AlaktraMint else AlaktraSurface,
        border = BorderStroke(1.dp, if (selected) AlaktraMint else AlaktraBorder),
        modifier = modifier.height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color(0xFF042014) else AlaktraTextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = if (selected) Color(0xFF042014) else AlaktraTextPrimary,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun studioTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AlaktraTextPrimary,
    unfocusedTextColor = AlaktraTextPrimary,
    focusedContainerColor = AlaktraSurface,
    unfocusedContainerColor = AlaktraSurface,
    focusedBorderColor = AlaktraMint,
    unfocusedBorderColor = AlaktraBorder,
    focusedLabelColor = AlaktraMint,
    unfocusedLabelColor = AlaktraTextSecondary
)
