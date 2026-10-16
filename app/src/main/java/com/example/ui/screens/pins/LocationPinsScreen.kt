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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import com.example.model.LocationPin
import com.example.model.Song
import com.example.ui.components.SongCover
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@Composable
fun LocationPinsScreen(
    repository: MusicRepository,
    audioController: AudioController,
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("location_pins_pref", Context.MODE_PRIVATE) }

    var currentLat by remember {
        mutableDoubleStateOf(prefs.getFloat("last_lat", 37.7749f).toDouble())
    }
    var currentLng by remember {
        mutableDoubleStateOf(prefs.getFloat("last_lng", -122.4194f).toDouble())
    }
    var hasLocation by remember { mutableStateOf(prefs.contains("last_lat")) }
    var pins by remember { mutableStateOf<List<LocationPin>>(emptyList()) }
    var isLoadingPins by remember { mutableStateOf(false) }

    // Pin creation state flow
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showSongPicker by remember { mutableStateOf(false) }
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isUploadingPin by remember { mutableStateOf(false) }

    val playbackState by audioController.playbackState.collectAsState()

    // Location manager setup
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    var lastQueriedLat by remember { mutableDoubleStateOf(0.0) }
    var lastQueriedLng by remember { mutableDoubleStateOf(0.0) }
    var lastQueriedTimeMs by remember { mutableLongStateOf(0L) }

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
        prefs.edit()
            .putFloat("last_lat", lat.toFloat())
            .putFloat("last_lng", lng.toFloat())
            .apply()
    }

    fun fetchPins(lat: Double, lng: Double, isInitial: Boolean = false, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val distMoved = distanceMeters(lat, lng, lastQueriedLat, lastQueriedLng)
        if (!force && !isInitial && lastQueriedTimeMs > 0L) {
            // Only query if moved at least 5m or 30s elapsed
            if (distMoved < 5.0 && (now - lastQueriedTimeMs) < 30000L) {
                return
            }
        }

        lastQueriedLat = lat
        lastQueriedLng = lng
        lastQueriedTimeMs = now

        scope.launch {
            if (isInitial && pins.isEmpty()) {
                isLoadingPins = true
            }
            val freshPins = repository.getNearbyPins(lat, lng)
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

            // Dynamically update proximity queue so upcoming tracks match current 50m range
            audioController.updateNearbyPinsQueue(freshPins.map { it.song })
        }
    }

    fun refreshLocationAndPins() {
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (provider in providers) {
                try {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (best == null || loc.time > best.time) {
                            best = loc
                        }
                    }
                } catch (_: SecurityException) {}
            }

            if (best != null) {
                saveLocation(best.latitude, best.longitude)
            }

            // Android 11+ (API 30+) getCurrentLocation for high precision fresh fix
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.getCurrentLocation(
                            LocationManager.GPS_PROVIDER,
                            null,
                            ContextCompat.getMainExecutor(context)
                        ) { loc ->
                            if (loc != null) {
                                saveLocation(loc.latitude, loc.longitude)
                                fetchPins(loc.latitude, loc.longitude, isInitial = false, force = true)
                            }
                        }
                    }
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.getCurrentLocation(
                            LocationManager.NETWORK_PROVIDER,
                            null,
                            ContextCompat.getMainExecutor(context)
                        ) { loc ->
                            if (loc != null) {
                                saveLocation(loc.latitude, loc.longitude)
                                fetchPins(loc.latitude, loc.longitude, isInitial = false, force = true)
                            }
                        }
                    }
                } catch (_: SecurityException) {}
            }

            fetchPins(currentLat, currentLng, isInitial = pins.isEmpty(), force = true)
        } catch (_: Exception) {
            fetchPins(currentLat, currentLng, isInitial = pins.isEmpty(), force = true)
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            hasPermission = true
            refreshLocationAndPins()
        }
    }

    // Automatically refresh every time the user enters or navigates to the Pins screen
    LaunchedEffect(refreshTrigger) {
        if (!hasPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        } else {
            refreshLocationAndPins()
        }
        allSongs = repository.getSongs()
    }

    // Real-time movement listener: updates 50m pins and proximity queue dynamically as the user moves
    DisposableEffect(hasPermission) {
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

        if (hasPermission) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000L,
                        5f,
                        listener,
                        Looper.getMainLooper()
                    )
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000L,
                        5f,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            } catch (_: SecurityException) {}
        }

        onDispose {
            try {
                locationManager.removeUpdates(listener)
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

    fun saveBitmapToFile(bitmap: Bitmap): File? {
        return try {
            val file = File(context.cacheDir, "pin_capture_${System.currentTimeMillis()}.jpg")
            val fOut = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fOut)
            fOut.flush()
            fOut.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    val isMiniPlayerActive = playbackState.currentSong != null
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
                    .padding(horizontal = 20.dp, vertical = 14.dp),
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
                        onClick = { refreshLocationAndPins() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AlaktraSurface)
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
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

                                val isNearbyPlaying = playbackState.isPlaying &&
                                    (playbackState.context?.name == "Nearby Pins" || playbackState.context?.uri?.contains("nearby_pins") == true)

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
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
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
                        val isCurrentSong = playbackState.currentSong?.id == pin.song.id
                        val isCurrentPlaying = isCurrentSong && playbackState.isPlaying
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
                                                        if (created != null) {
                                                            Toast.makeText(context, "Pinned successfully!", Toast.LENGTH_SHORT).show()
                                                            refreshLocationAndPins()
                                                        } else {
                                                            Toast.makeText(context, "Failed to upload pin", Toast.LENGTH_SHORT).show()
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
                    model = pin.imageUrl,
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
