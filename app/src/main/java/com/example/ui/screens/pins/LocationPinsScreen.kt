package com.example.ui.screens.pins

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.LocationPin
import com.example.model.Song
import com.example.ui.components.SongCover
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal object PinsSessionCache {
    @Volatile var lastLat: Double = 37.7749
    @Volatile var lastLng: Double = -122.4194
    @Volatile var hasLocation: Boolean = false
    @Volatile var pins: List<LocationPin> = emptyList()
    @Volatile var hasPermission: Boolean = false
    @Volatile var hasInitialized: Boolean = false
    @Volatile var lastSyncedLat: Double = 0.0
    @Volatile var lastSyncedLng: Double = 0.0
    @Volatile var lastSyncedAtMs: Long = 0L
    @Volatile var hasPromptedPermission: Boolean = false

    suspend fun prewarm(context: Context, repository: MusicRepository) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("location_pins_pref", Context.MODE_PRIVATE)
        var lat = prefs.getFloat("last_lat", 37.7749f).toDouble()
        var lng = prefs.getFloat("last_lng", -122.4194f).toDouble()
        var hasLoc = prefs.contains("last_lat")

        val permitted =
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (permitted) {
            try {
                val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                var best: Location? = null
                for (provider in providers) {
                    try {
                        val loc = lm.getLastKnownLocation(provider)
                        if (loc != null && (best == null || loc.time > best.time)) {
                            best = loc
                        }
                    } catch (_: SecurityException) {}
                }
                if (best != null) {
                    lat = best.latitude
                    lng = best.longitude
                    hasLoc = true
                    prefs.edit()
                        .putFloat("last_lat", lat.toFloat())
                        .putFloat("last_lng", lng.toFloat())
                        .apply()
                }
            } catch (_: Exception) {}
        }

        val cachedNearby = repository.getCachedNearbyPins(lat, lng)
        lastLat = lat
        lastLng = lng
        hasLocation = hasLoc
        hasPermission = permitted
        if (cachedNearby.isNotEmpty() || !hasInitialized) {
            pins = cachedNearby
        }
        hasInitialized = true
    }
}

@Composable
fun LocationPinsScreen(
    repository: MusicRepository,
    audioController: AudioController,
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var currentLat by remember { mutableDoubleStateOf(PinsSessionCache.lastLat) }
    var currentLng by remember { mutableDoubleStateOf(PinsSessionCache.lastLng) }
    var hasLocation by remember { mutableStateOf(PinsSessionCache.hasLocation) }
    var pins by remember { mutableStateOf(PinsSessionCache.pins) }
    var isLoadingPins by remember { mutableStateOf(false) }
    var isServerOffline by remember { mutableStateOf(repository.lastPinSyncOffline) }
    var activeFetchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Pin creation state flow
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showSongPicker by remember { mutableStateOf(false) }
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isUploadingPin by remember { mutableStateOf(false) }

    // Isolate playback state reads so 10Hz currentPositionMs ticks never recompose LocationPinsScreen
    val playbackStateHolder = audioController.playbackState.collectAsState()
    val isMiniPlayerActive by remember {
        derivedStateOf { playbackStateHolder.value.currentSong != null }
    }
    val currentPlayingSongId by remember {
        derivedStateOf { playbackStateHolder.value.currentSong?.id }
    }
    val isAudioPlaying by remember {
        derivedStateOf { playbackStateHolder.value.isPlaying }
    }
    val isNearbyPlaying by remember {
        derivedStateOf {
            val s = playbackStateHolder.value
            s.isPlaying && (s.context?.name == "Nearby Pins" || s.context?.uri?.contains("nearby_pins") == true)
        }
    }

    var lastQueriedLat by remember { mutableDoubleStateOf(PinsSessionCache.lastSyncedLat) }
    var lastQueriedLng by remember { mutableDoubleStateOf(PinsSessionCache.lastSyncedLng) }
    var lastQueriedTimeMs by remember { mutableLongStateOf(PinsSessionCache.lastSyncedAtMs) }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun saveLocation(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        hasLocation = true
        PinsSessionCache.lastLat = lat
        PinsSessionCache.lastLng = lng
        PinsSessionCache.hasLocation = true
        scope.launch(Dispatchers.IO) {
            appContext.getSharedPreferences("location_pins_pref", Context.MODE_PRIVATE)
                .edit()
                .putFloat("last_lat", lat.toFloat())
                .putFloat("last_lng", lng.toFloat())
                .apply()
        }
    }

    fun fetchPins(
        lat: Double,
        lng: Double,
        isInitial: Boolean = false,
        force: Boolean = false,
        manualRefresh: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val distMoved = distanceMeters(lat, lng, lastQueriedLat, lastQueriedLng)
        if (!force && !manualRefresh && !isInitial && lastQueriedTimeMs > 0L) {
            // Only query if moved at least 5m or 30s elapsed
            if (distMoved < 5.0 && (now - lastQueriedTimeMs) < 30000L) {
                return
            }
        }

        lastQueriedLat = lat
        lastQueriedLng = lng
        lastQueriedTimeMs = now
        PinsSessionCache.lastSyncedLat = lat
        PinsSessionCache.lastSyncedLng = lng
        PinsSessionCache.lastSyncedAtMs = now

        activeFetchJob?.cancel()
        activeFetchJob = scope.launch {
            if (pins.isEmpty()) {
                val cachedPins = repository.getCachedNearbyPins(lat, lng)
                if (cachedPins.isNotEmpty()) {
                    pins = cachedPins
                    PinsSessionCache.pins = cachedPins
                }
            }
            if (manualRefresh && pins.isEmpty()) {
                isLoadingPins = true
            }
            try {
                val freshPins = repository.getNearbyPins(lat, lng, forceRefresh = manualRefresh)
                isServerOffline = repository.lastPinSyncOffline
                isLoadingPins = false

                // Diff check: only mutate state if pins count, songs, or distances changed
                val hasChanged = freshPins.size != pins.size || freshPins.zip(pins).any { (fresh, current) ->
                    fresh.id != current.id ||
                    fresh.song.id != current.song.id ||
                    Math.abs(fresh.distanceMeters - current.distanceMeters) >= 3.0
                }
                if (hasChanged) {
                    pins = freshPins
                }
                PinsSessionCache.pins = freshPins
                PinsSessionCache.hasInitialized = true

                // Dynamically update proximity queue so upcoming tracks match current 50m range
                audioController.updateNearbyPinsQueue(freshPins.map { it.song })
            } finally {
                isLoadingPins = false
            }
        }
    }

    var hasPermission by remember { mutableStateOf(PinsSessionCache.hasPermission) }

    var requestPermissionCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshLocationAndPins(manualRefresh: Boolean = false) {
        scope.launch {
            val permitted = withContext(Dispatchers.IO) {
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            hasPermission = permitted
            PinsSessionCache.hasPermission = permitted

            if (!permitted) {
                if (manualRefresh) {
                    requestPermissionCallback?.invoke()
                }
                fetchPins(
                    currentLat,
                    currentLng,
                    isInitial = !PinsSessionCache.hasInitialized,
                    force = manualRefresh || PinsSessionCache.lastSyncedAtMs == 0L,
                    manualRefresh = manualRefresh
                )
                return@launch
            }

            try {
                val locSnapshot = withContext(Dispatchers.IO) {
                    val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val providers = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                    )
                    var best: Location? = null
                    for (provider in providers) {
                        try {
                            val loc = lm.getLastKnownLocation(provider)
                            if (loc != null && (best == null || loc.time > best.time)) {
                                best = loc
                            }
                        } catch (_: SecurityException) {}
                    }
                    val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
                    val netEnabled = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
                    Triple(best, gpsEnabled, netEnabled)
                }

                val (best, gpsEnabled, netEnabled) = locSnapshot
                if (best != null) {
                    val movedFromCached = distanceMeters(best.latitude, best.longitude, currentLat, currentLng)
                    if (!hasLocation || movedFromCached >= 1.0) {
                        saveLocation(best.latitude, best.longitude)
                    }
                }

                val now = System.currentTimeMillis()
                val shouldRequestFreshFix = manualRefresh || (now - lastQueriedTimeMs) >= 20_000L

                // Android 11+ (API 30+) getCurrentLocation off the UI thread for high-precision fix
                if (shouldRequestFreshFix && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    withContext(Dispatchers.IO) {
                        try {
                            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                            val ioExecutor = Dispatchers.IO.asExecutor()
                            if (gpsEnabled) {
                                lm.getCurrentLocation(
                                    LocationManager.GPS_PROVIDER,
                                    null,
                                    ioExecutor
                                ) { loc ->
                                    if (loc != null) {
                                        scope.launch {
                                            val moved = distanceMeters(loc.latitude, loc.longitude, currentLat, currentLng)
                                            saveLocation(loc.latitude, loc.longitude)
                                            if (moved >= 3.0) {
                                                fetchPins(loc.latitude, loc.longitude, isInitial = false, force = true)
                                            }
                                        }
                                    }
                                }
                            }
                            if (netEnabled) {
                                lm.getCurrentLocation(
                                    LocationManager.NETWORK_PROVIDER,
                                    null,
                                    ioExecutor
                                ) { loc ->
                                    if (loc != null) {
                                        scope.launch {
                                            val moved = distanceMeters(loc.latitude, loc.longitude, currentLat, currentLng)
                                            saveLocation(loc.latitude, loc.longitude)
                                            if (moved >= 3.0) {
                                                fetchPins(loc.latitude, loc.longitude, isInitial = false, force = true)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: SecurityException) {}
                    }
                }

                val recentEnough = !manualRefresh &&
                    PinsSessionCache.lastSyncedAtMs > 0L &&
                    (now - PinsSessionCache.lastSyncedAtMs) < 15_000L &&
                    distanceMeters(currentLat, currentLng, PinsSessionCache.lastSyncedLat, PinsSessionCache.lastSyncedLng) < 5.0

                if (!recentEnough) {
                    fetchPins(
                        currentLat,
                        currentLng,
                        isInitial = !PinsSessionCache.hasInitialized,
                        force = manualRefresh || PinsSessionCache.lastSyncedAtMs == 0L,
                        manualRefresh = manualRefresh
                    )
                }
            } catch (_: Exception) {
                fetchPins(
                    currentLat,
                    currentLng,
                    isInitial = !PinsSessionCache.hasInitialized,
                    force = manualRefresh || PinsSessionCache.lastSyncedAtMs == 0L,
                    manualRefresh = manualRefresh
                )
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            hasPermission = true
            PinsSessionCache.hasPermission = true
            refreshLocationAndPins(manualRefresh = true)
        }
    }

    SideEffect {
        requestPermissionCallback = {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        }
    }

    // Defer location & network refresh until AFTER the ~220ms tab transition animation finishes
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger == 0) {
            if (!PinsSessionCache.hasInitialized) {
                PinsSessionCache.prewarm(appContext, repository)
                currentLat = PinsSessionCache.lastLat
                currentLng = PinsSessionCache.lastLng
                hasLocation = PinsSessionCache.hasLocation
                if (pins.isEmpty() && PinsSessionCache.pins.isNotEmpty()) {
                    pins = PinsSessionCache.pins
                }
            }
            // Yield complete CPU/GPU frame budget to AnimatedContent transition
            delay(250L)
        }

        val permitted = withContext(Dispatchers.IO) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        hasPermission = permitted
        PinsSessionCache.hasPermission = permitted

        if (!permitted) {
            if (!PinsSessionCache.hasPromptedPermission || refreshTrigger > 0) {
                PinsSessionCache.hasPromptedPermission = true
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.CAMERA
                    )
                )
            }
            if (PinsSessionCache.lastSyncedAtMs == 0L) {
                fetchPins(currentLat, currentLng, isInitial = false, force = true)
            }
        } else {
            refreshLocationAndPins(manualRefresh = refreshTrigger > 0)
        }
    }

    // Lazy-load song catalog ONLY when the user opens the Pin Song picker dialog
    LaunchedEffect(showSongPicker) {
        if (showSongPicker) {
            if (allSongs.isEmpty()) {
                allSongs = repository.getCachedSongs().ifEmpty { repository.getDownloadedSongs() }
            }
            val freshSongs = repository.getSongs()
            if (freshSongs.isNotEmpty()) {
                allSongs = freshSongs
            }
        }
    }

    // Real-time movement listener: registered AFTER the screen transition completes to avoid Frame 1 Binder stalls
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        delay(320L)

        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                saveLocation(loc.latitude, loc.longitude)
                fetchPins(loc.latitude, loc.longitude, isInitial = false, force = false)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val (gpsEnabled, netEnabled) = withContext(Dispatchers.IO) {
            val gps = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
            val net = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
            gps to net
        }

        try {
            if (gpsEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (netEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            awaitCancellation()
        } catch (_: SecurityException) {
        } finally {
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {}
        }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            capturedBitmap = bitmap
            showSongPicker = true
        } else {
            Toast.makeText(context, "Failed to capture photo", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun saveBitmapToFile(bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "pin_capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { fOut ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fOut)
                fOut.flush()
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    val fabBottomOffset = if (isMiniPlayerActive) 175.dp else 100.dp
    val listBottomPadding = if (isMiniPlayerActive) 240.dp else 170.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlaktraBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
        ) {
            // Header with title, refresh button, and prominent top Camera / Pin Song action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Location Pins",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp
                            ),
                            color = AlaktraTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Tracks within 50m • Queue follows your movement",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Refresh button
                    IconButton(
                        onClick = { refreshLocationAndPins(manualRefresh = true) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(AlaktraSurface)
                            .testTag("pins_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh location and pins",
                            tint = AlaktraMint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Prominent Top Camera Button (always visible, never covered by bottom nav bar)
                    FilledTonalButton(
                        onClick = { cameraLauncher.launch(null) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AlaktraMint,
                            contentColor = Color(0xFF0A2218)
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("pin_song_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddAPhoto,
                            contentDescription = "Pin Location Photo",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Pin Song",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (isServerOffline && !isLoadingPins) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AlaktraSurface,
                    border = BorderStroke(1.dp, AlaktraBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "Server offline",
                                tint = AlaktraTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Server unreachable • Showing offline pins",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { refreshLocationAndPins(manualRefresh = true) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Retry",
                                color = AlaktraMint,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            if (isLoadingPins && pins.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AlaktraMint)
                }
            } else if (pins.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            tint = AlaktraTextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No location pins in 50m range",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AlaktraTextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Take a picture and pin a song here! Anyone within 50 meters can discover and play your pin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlaktraTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint)
                        ) {
                            Icon(imageVector = Icons.Default.AddAPhoto, contentDescription = null, tint = Color(0xFF0A2218))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pin Current Location", color = Color(0xFF0A2218), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Proximity Queue Header: Start the 50m range mix
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = AlaktraCard,
                            border = BorderStroke(1.dp, AlaktraMint.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.NearMe,
                                            contentDescription = null,
                                            tint = AlaktraMint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "50m Proximity Mix",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = AlaktraTextPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${pins.size} song(s) in range • Auto-adapts as you move",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AlaktraTextSecondary
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (isNearbyPlaying) {
                                            audioController.togglePlayPause()
                                        } else {
                                            val pinSongs = pins.map { it.song }
                                            audioController.playQueue(pinSongs, startIndex = 0, source = "Nearby Pins")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.testTag("play_proximity_mix_button")
                                ) {
                                    Icon(
                                        imageVector = if (isNearbyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(0xFF0A2218),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isNearbyPlaying) "Playing" else "Play Mix",
                                        color = Color(0xFF0A2218),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(items = pins, key = { _, pin -> pin.id }) { index, pin ->
                        val isCurrentSong = currentPlayingSongId == pin.song.id
                        val isCurrentPlaying = isCurrentSong && isAudioPlaying
                        LocationPinCard(
                            pin = pin,
                            isPlaying = isCurrentPlaying,
                            onPlay = {
                                if (isCurrentPlaying) {
                                    audioController.togglePlayPause()
                                } else if (isCurrentSong) {
                                    audioController.play()
                                } else {
                                    val pinSongs = pins.map { it.song }
                                    audioController.playQueue(pinSongs, startIndex = index, source = "Nearby Pins")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Song Picker Dialog when creating a pin
    if (showSongPicker) {
        AlertDialog(
            onDismissRequest = {
                if (!isUploadingPin) {
                    showSongPicker = false
                    capturedBitmap = null
                }
            },
            containerColor = AlaktraCard,
            title = {
                Text(text = "Select Song to Pin", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 350.dp)) {
                    Text(
                        text = "Choose a track to attach to your location photo:",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isUploadingPin) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AlaktraMint)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Uploading your location pin...", color = AlaktraTextPrimary)
                            }
                        }
                    } else if (allSongs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tracks cached yet. Connect to your Alaktra server to load songs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AlaktraTextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(items = allSongs, key = { it.id }) { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AlaktraSurface)
                                        .clickable {
                                            val bmp = capturedBitmap
                                            if (bmp != null) {
                                                isUploadingPin = true
                                                scope.launch {
                                                    val file = saveBitmapToFile(bmp)
                                                    if (file != null) {
                                                        val created = repository.createPin(
                                                            songId = song.id,
                                                            lat = currentLat,
                                                            lng = currentLng,
                                                            imageFile = file
                                                        )
                                                        isServerOffline = repository.lastPinSyncOffline
                                                        if (created != null) {
                                                            val msg = if (created.id < 0) {
                                                                "Saved offline • Will sync when server connects"
                                                            } else {
                                                                "Pinned successfully!"
                                                            }
                                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                            val updatedCached = repository.getCachedNearbyPins(currentLat, currentLng)
                                                            pins = updatedCached
                                                            PinsSessionCache.pins = updatedCached
                                                            if (!isServerOffline) {
                                                                refreshLocationAndPins(manualRefresh = true)
                                                            }
                                                        } else {
                                                            Toast.makeText(context, "Failed to save pin", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    isUploadingPin = false
                                                    showSongPicker = false
                                                    capturedBitmap = null
                                                }
                                            }
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SongCover(imageUrl = song.coverUrl, size = 44.dp, cornerRadius = 8.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = AlaktraTextPrimary
                                        )
                                        Text(
                                            text = song.artist,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AlaktraTextSecondary
                                        )
                                    }
                                    Icon(imageVector = Icons.Default.AddLocation, contentDescription = null, tint = AlaktraMint)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (!isUploadingPin) {
                    TextButton(onClick = {
                        showSongPicker = false
                        capturedBitmap = null
                    }) {
                        Text("Cancel", color = AlaktraTextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun LocationPinCard(
    pin: LocationPin,
    isPlaying: Boolean,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(pin.imageUrl) {
        ImageRequest.Builder(context)
            .data(pin.imageUrl)
            .size(800, 450)
            .crossfade(false)
            .build()
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AlaktraCard),
        border = BorderStroke(1.dp, AlaktraBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Location Photo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AlaktraSurface)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Pin photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Distance badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = null,
                            tint = AlaktraMint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${pin.distanceMeters}m away",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Attached Song Info & Play Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SongCover(
                    imageUrl = pin.song.coverUrl,
                    size = 50.dp,
                    cornerRadius = 10.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = AlaktraMint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Attached Music Pin",
                            style = MaterialTheme.typography.labelSmall,
                            color = AlaktraMint
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pin.song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AlaktraTextPrimary
                    )
                    Text(
                        text = pin.song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                }

                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(AlaktraMint)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color(0xFF0A2218),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
