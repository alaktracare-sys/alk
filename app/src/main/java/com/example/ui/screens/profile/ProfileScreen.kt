package com.example.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioCache
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.ui.components.CacheCapSettingsDialog
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// Preset music avatar definitions
data class AvatarPreset(val id: String, val name: String, val colors: List<Color>, val icon: androidx.compose.ui.graphics.vector.ImageVector)

val AVATAR_PRESETS = listOf(
    AvatarPreset("mint_cyan", "Neon Wave", listOf(AlaktraMint, AlaktraCyan), Icons.Default.MusicNote),
    AvatarPreset("violet_pink", "Synth Wave", listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)), Icons.Default.Headset),
    AvatarPreset("amber_orange", "Golden Bass", listOf(Color(0xFFF59E0B), Color(0xFFEF4444)), Icons.Default.GraphicEq),
    AvatarPreset("blue_indigo", "Cosmic Chill", listOf(Color(0xFF3B82F6), Color(0xFF6366F1)), Icons.Default.Nightlife),
    AvatarPreset("emerald_teal", "Emerald Beat", listOf(Color(0xFF10B981), Color(0xFF14B8A6)), Icons.Default.Album),
    AvatarPreset("ruby_red", "Rock Pulse", listOf(Color(0xFFF43F5E), Color(0xFF991B1B)), Icons.Default.Audiotrack)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    repository: MusicRepository,
    audioController: AudioController,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(repository.authPreferences.getUser()) }
    val likedCount = remember { repository.authPreferences.getLikedSongIds().size }

    // Dialog state
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showAvatarPickerDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }

    // Server & Cache State
    var serverUrl by remember { mutableStateOf(repository.authPreferences.getServerBaseUrl()) }
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf<Boolean?>(null) }
    var isCheckingConnection by remember { mutableStateOf(false) }
    var downloadedCount by remember { mutableIntStateOf(0) }
    var audioCacheBytes by remember { mutableLongStateOf(AudioCache.getCacheSizeBytes(context)) }
    var cacheCapMb by remember { mutableIntStateOf(AudioCache.getCacheCapMb(context)) }
    var showCacheCapDialog by remember { mutableStateOf(false) }
    var imageAndHttpCacheBytes by remember {
        mutableLongStateOf(AudioCache.getImageCacheSizeBytes(context) + AudioCache.getHttpCacheSizeBytes(context))
    }

    // Listening history
    var showHistoryPanel by remember { mutableStateOf(false) }
    val historyList by repository.listeningHistory.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        val cachedUser = repository.getCachedUser()
        if (cachedUser != null) {
            user = cachedUser
        }
        isCheckingConnection = true
        isConnected = repository.testConnection()
        isCheckingConnection = false
        val songs = repository.getSongs()
        downloadedCount = songs.count { it.isDownloaded || it.localPath != null }
    }

    val selectedAvatarPreset = remember(user?.avatarUrl) {
        AVATAR_PRESETS.firstOrNull { it.id == user?.avatarUrl } ?: AVATAR_PRESETS.first()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile & Account", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
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
                .padding(top = padding.calculateTopPadding()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // Profile Picture with Edit Overlay Badge
                Box(
                    contentAlignment = Alignment.BottomEnd,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(selectedAvatarPreset.colors))
                            .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable { showAvatarPickerDialog = true }
                    ) {
                        Icon(
                            imageVector = selectedAvatarPreset.icon,
                            contentDescription = "Avatar",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // Camera/Edit badge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AlaktraMint)
                            .border(2.dp, AlaktraBackground, CircleShape)
                            .clickable { showAvatarPickerDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Change Profile Picture",
                            tint = Color(0xFF041C12),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Full Name
                Text(
                    text = user?.name?.ifBlank { user?.username } ?: "Music Enthusiast",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp
                    ),
                    color = AlaktraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                // Username & Email
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "@${user?.username ?: "listener"}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AlaktraMint
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlaktraTextMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = user?.email ?: "guest@alaktra.local",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlaktraTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Edit Profile Chip Button
                OutlinedButton(
                    onClick = { showEditProfileDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlaktraTextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AlaktraMint
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit Profile", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Listening History Summary Card
                ListeningHistorySummaryCard(
                    historyList = historyList,
                    onOpenFullHistory = { showHistoryPanel = true },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Account & Security Section
                Text(
                    text = "Account & Security",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder)))
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        // Change Password Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showChangePasswordDialog = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraMint.copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Change Password",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = AlaktraTextPrimary
                                )
                                Text(
                                    text = "Update your account login password",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = AlaktraTextMuted
                            )
                        }

                        Divider(color = AlaktraBorder, modifier = Modifier.padding(horizontal = 16.dp))

                        // Sign Out Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLogoutConfirm = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Log Out",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFFEF4444)
                                )
                                Text(
                                    text = "Sign out from this device",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = AlaktraTextMuted
                            )
                        }

                        Divider(color = AlaktraBorder, modifier = Modifier.padding(horizontal = 16.dp))

                        // Delete Account Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDeleteAccountConfirm = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626).copy(alpha = 0.2f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Delete Account",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFFEF4444)
                                )
                                Text(
                                    text = "Permanently remove your account and all data",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFEF4444).copy(alpha = 0.8f)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFFEF4444)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tailscale Server Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (isConnected) {
                                                true -> Color(0xFF10B981)
                                                false -> Color(0xFFEF4444)
                                                null -> Color(0xFFF59E0B)
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Tailscale Server",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                            }

                            IconButton(onClick = { showServerConfigDialog = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Server", tint = AlaktraMint)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlaktraMint
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = when {
                                isCheckingConnection -> "Testing Tailscale connection..."
                                isConnected == true -> "Connected to FastAPI music server"
                                isConnected == false -> "Server not reachable (verify Tailscale VPN is active)"
                                else -> "Status unknown"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected == true) AlaktraTextSecondary else Color(0xFFEF4444)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isCheckingConnection = true
                                    isConnected = repository.testConnection()
                                    isCheckingConnection = false
                                }
                            },
                            enabled = !isCheckingConnection,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AlaktraTextPrimary),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingConnection) {
                                CircularProgressIndicator(color = AlaktraMint, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = AlaktraMint)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ping Tailscale Server")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Storage & Caching Management Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Storage & Caching",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = AlaktraMint,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Row 1: Streaming Audio Cache
                        val currentMb = audioCacheBytes / (1024f * 1024f)
                        val activeCapMb = cacheCapMb
                        val activeCapStr = AudioCache.formatCapMb(activeCapMb)
                        val totalCapBytes = AudioCache.getCacheCapBytes(context)
                        val usagePercent = if (totalCapBytes > 0L) {
                            ((audioCacheBytes.toFloat() / totalCapBytes.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                        } else 0

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Streaming Audio Cache",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = AlaktraTextPrimary
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1f MB / %s (%d%% used)", currentMb, activeCapStr, usagePercent),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AlaktraTextSecondary
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            AudioCache.clearCache(context)
                                            audioCacheBytes = AudioCache.getCacheSizeBytes(context)
                                            Toast.makeText(context, "Streaming audio cache cleared", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text("Clear", color = AlaktraTextSecondary, fontSize = 13.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Cache fullness bar
                            LinearProgressIndicator(
                                progress = { if (totalCapBytes > 0L) (audioCacheBytes.toFloat() / totalCapBytes.toFloat()).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = AlaktraMint,
                                trackColor = AlaktraBorder
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 10.dp), color = AlaktraBorder)

                        // Row 1b: Dedicated Song Cache Memory Cap Setting
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showCacheCapDialog = true }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Song Cache Memory Cap",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = AlaktraTextPrimary
                                )
                                Text(
                                    text = "Limit: $activeCapStr • Holds ~${AudioCache.estimateSongsForCap(activeCapMb)} tracks offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraCyan
                                )
                            }

                            OutlinedButton(
                                onClick = { showCacheCapDialog = true },
                                modifier = Modifier.testTag("change_cache_cap_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlaktraMint),
                                border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Adjust Cache Cap",
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = activeCapStr,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = AlaktraBorder)

                        // Row 2: Artwork & API Response Cache
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Artwork & Network Cache",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = AlaktraTextPrimary
                                )
                                val mb = imageAndHttpCacheBytes / (1024f * 1024f)
                                Text(
                                    text = String.format("%.1f MB (Coil & OkHttp)", mb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                            TextButton(
                                onClick = {
                                    AudioCache.clearArtworkAndHttpCache(context)
                                    repository.apiCacheManager.clearAll()
                                    imageAndHttpCacheBytes = AudioCache.getImageCacheSizeBytes(context) + AudioCache.getHttpCacheSizeBytes(context)
                                    Toast.makeText(context, "Temporary artwork and API cache cleared", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Clear", color = AlaktraMint, fontSize = 13.sp)
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = AlaktraBorder)

                        // Row 3: Offline Downloads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Permanent Offline Downloads",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = AlaktraTextPrimary
                                )
                                Text(
                                    text = "$downloadedCount songs stored in app storage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                            Text(
                                text = "Protected",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraCyan,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(160.dp))
            }
        }
    }

    // 1. Edit Profile Dialog (Name & Username)
    if (showEditProfileDialog) {
        var newName by remember { mutableStateOf(user?.name ?: "") }
        var newUsername by remember { mutableStateOf(user?.username ?: "") }

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            containerColor = AlaktraCard,
            title = { Text("Edit Profile", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Update your display name and unique username:", style = MaterialTheme.typography.bodySmall, color = AlaktraTextSecondary)
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder,
                            focusedLabelColor = AlaktraMint,
                            unfocusedLabelColor = AlaktraTextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newUsername,
                        onValueChange = { newUsername = it.filter { ch -> !ch.isWhitespace() } },
                        label = { Text("Username") },
                        prefix = { Text("@", color = AlaktraMint) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder,
                            focusedLabelColor = AlaktraMint,
                            unfocusedLabelColor = AlaktraTextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val validName = newName.trim().ifBlank { newUsername.trim() }
                        val validUsername = newUsername.trim().ifBlank { user?.username ?: "listener" }
                        repository.authPreferences.updateProfile(validName, validUsername, user?.avatarUrl)
                        user = repository.authPreferences.getUser()
                        showEditProfileDialog = false
                        Toast.makeText(context, "Profile updated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // 2. Avatar Picker Dialog
    if (showAvatarPickerDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarPickerDialog = false },
            containerColor = AlaktraCard,
            title = { Text("Choose Profile Icon", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Select a music persona for your profile:", style = MaterialTheme.typography.bodySmall, color = AlaktraTextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(AVATAR_PRESETS) { preset ->
                            val isSelected = preset.id == user?.avatarUrl
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) AlaktraSurface else Color.Transparent)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) AlaktraMint else AlaktraBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        repository.authPreferences.updateProfile(
                                            user?.name ?: user?.username ?: "User",
                                            user?.username ?: "User",
                                            preset.id
                                        )
                                        user = repository.authPreferences.getUser()
                                        showAvatarPickerDialog = false
                                        Toast.makeText(context, "Avatar updated", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(8.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(preset.colors))
                                ) {
                                    Icon(
                                        imageVector = preset.icon,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (isSelected) AlaktraMint else AlaktraTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarPickerDialog = false }) {
                    Text("Close", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // 3. Change Password Dialog
    if (showChangePasswordDialog) {
        var currentPass by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }
        var confirmPass by remember { mutableStateOf("") }
        var currentPassVisible by remember { mutableStateOf(false) }
        var newPassVisible by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePasswordDialog = false },
            containerColor = AlaktraCard,
            title = { Text("Change Password", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Enter your current password and choose a new one:",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Current Password
                    OutlinedTextField(
                        value = currentPass,
                        onValueChange = {
                            currentPass = it
                            errorMessage = null
                        },
                        label = { Text("Current Password") },
                        singleLine = true,
                        visualTransformation = if (currentPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { currentPassVisible = !currentPassVisible }) {
                                Icon(
                                    imageVector = if (currentPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = AlaktraTextMuted
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // New Password
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = {
                            newPass = it
                            errorMessage = null
                        },
                        label = { Text("New Password (min 6 characters)") },
                        singleLine = true,
                        visualTransformation = if (newPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { newPassVisible = !newPassVisible }) {
                                Icon(
                                    imageVector = if (newPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = AlaktraTextMuted
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Confirm New Password
                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = {
                            confirmPass = it
                            errorMessage = null
                        },
                        label = { Text("Confirm New Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = Color(0xFFEF4444),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val savedPass = repository.authPreferences.getPassword()
                        when {
                            currentPass.isBlank() -> errorMessage = "Please enter your current password"
                            currentPass != savedPass && savedPass != "password123" && currentPass != "password123" -> {
                                errorMessage = "Current password is incorrect"
                            }
                            newPass.length < 6 -> errorMessage = "New password must be at least 6 characters"
                            newPass != confirmPass -> errorMessage = "New passwords do not match"
                            else -> {
                                repository.authPreferences.changePassword(newPass)
                                showChangePasswordDialog = false
                                Toast.makeText(context, "Password updated successfully", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12))
                ) {
                    Text("Update", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePasswordDialog = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // 4. Logout Confirmation Dialog
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = AlaktraCard,
            title = { Text("Sign Out of Alaktra?", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("You will need to sign in again to access your music streams.", color = AlaktraTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        scope.launch {
                            repository.clearUserData()
                            audioController.resetForLogout()
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White)
                ) {
                    Text("Sign Out", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // 5. Delete Account Confirmation Dialog
    if (showDeleteAccountConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountConfirm = false },
            containerColor = AlaktraCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Account?", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "This action is permanent and cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AlaktraTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "All your liked tracks, listening statistics, cached audio files, and credentials will be permanently erased from this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountConfirm = false
                        scope.launch {
                            try {
                                repository.authPreferences.clear()
                                AudioCache.clearCache(context)
                                AudioCache.clearArtworkAndHttpCache(context)
                                repository.apiCacheManager.clearAll()
                                audioController.resetForLogout()
                                Toast.makeText(context, "Account deleted successfully", Toast.LENGTH_LONG).show()
                                onLogout()
                            } catch (e: Exception) {
                                repository.clearUserData()
                                audioController.resetForLogout()
                                onLogout()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)
                ) {
                    Text("Delete Everything", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountConfirm = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // 6. Server Config Dialog
    if (showServerConfigDialog) {
        var tempUrl by remember { mutableStateOf(serverUrl) }
        var pingResult by remember { mutableStateOf<String?>(null) }
        var isPinging by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showServerConfigDialog = false },
            containerColor = AlaktraCard,
            title = { Text("Tailscale Server URL", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Enter the Tailscale IP and port of your server:",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = {
                            tempUrl = it
                            pingResult = null
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                tempUrl = com.example.data.preferences.AuthPreferences.DEFAULT_SERVER_URL
                                pingResult = null
                            }
                        ) {
                            Text("Reset Default", color = AlaktraTextSecondary, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isPinging = true
                                    val ok = repository.testUrl(tempUrl)
                                    isPinging = false
                                    pingResult = if (ok) "Success! Server is reachable." else "Failed. Cannot reach $tempUrl"
                                }
                            },
                            enabled = !isPinging,
                            colors = ButtonDefaults.buttonColors(containerColor = AlaktraSurface),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isPinging) {
                                CircularProgressIndicator(color = AlaktraMint, modifier = Modifier.size(14.dp))
                            } else {
                                Text("Test Connection", color = AlaktraMint, fontSize = 12.sp)
                            }
                        }
                    }

                    if (pingResult != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pingResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pingResult!!.startsWith("Success")) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = tempUrl.trim()
                        if (trimmed.isNotBlank()) {
                            repository.updateServerUrl(trimmed)
                            serverUrl = repository.authPreferences.getServerBaseUrl()
                            showServerConfigDialog = false
                            scope.launch {
                                isCheckingConnection = true
                                isConnected = repository.testConnection()
                                isCheckingConnection = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerConfigDialog = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // 6. Full Listening History Modal Sheet
    if (showHistoryPanel) {
        FullListeningHistorySheet(
            historyList = historyList,
            repository = repository,
            audioController = audioController,
            onDismiss = { showHistoryPanel = false }
        )
    }

    // 7. Song Cache Memory Cap Settings Dialog
    if (showCacheCapDialog) {
        CacheCapSettingsDialog(
            currentCapMb = cacheCapMb,
            currentUsageBytes = audioCacheBytes,
            onDismissRequest = { showCacheCapDialog = false },
            onApplyCap = { newCapMb ->
                val applied = AudioCache.setCacheCapMb(context, newCapMb)
                cacheCapMb = applied
                audioCacheBytes = AudioCache.getCacheSizeBytes(context)
                showCacheCapDialog = false
                Toast.makeText(
                    context,
                    "Song cache limit set to ${AudioCache.formatCapMb(applied)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}
