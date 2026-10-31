package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.repository.MusicRepository
import com.example.model.Song
import com.example.util.NetworkMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

enum class LoopMode {
    OFF, ALL, ONE
}

enum class RepeatMode {
    OFF, CONTEXT, TRACK
}

enum class ContextType {
    PLAYLIST, ALBUM, ARTIST, SEARCH
}

data class PlaybackContext(
    val uri: String,
    val type: ContextType,
    val name: String,
    val trackIds: List<Int>,
    val tracks: List<Song> = emptyList()
)

data class PreOfflineSnapshot(
    val context: PlaybackContext,
    val contextOrder: List<Song>,
    val resumeIndex: Int,
    val preserveExactRandomOrder: Boolean
)

enum class AdvanceReason {
    ENDED, SKIP
}

data class PlaybackState(
    val context: PlaybackContext? = null,
    val contextOrder: List<Song> = emptyList(),    // ACTIVE ordering of context.trackIds
    val contextIndex: Int = -1,                  // pointer into contextOrder
    val userQueue: List<Song> = emptyList(),       // explicitly queued songs, FIFO, destructive
    val history: List<Song> = emptyList(),         // context plays only
    val recentPlayedSongIds: List<Int> = emptyList(), // last 20 actually played song IDs
    val currentSong: Song? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val autoplayTracks: List<Song> = emptyList(),
    val dominantColor: Color = Color.Transparent,
    val secondaryColor: Color = Color.Transparent
) {
    val elapsedSec: Long get() = currentPositionMs / 1000
    val currentTrackId: String? get() = currentSong?.id?.toString()

    // Backward-compatibility properties
    val isShuffle: Boolean get() = shuffle
    val loopMode: LoopMode get() = when (repeat) {
        RepeatMode.OFF -> LoopMode.OFF
        RepeatMode.CONTEXT -> LoopMode.ALL
        RepeatMode.TRACK -> LoopMode.ONE
    }
    val queue: List<Song> get() = contextOrder
    val currentIndex: Int get() = contextIndex
    val originalQueue: List<Song> get() = context?.tracks ?: emptyList()
    val playbackSource: String get() = context?.name ?: "Now Playing"
    val upNext: List<Song> get() = userQueue
}

@OptIn(UnstableApi::class)
@SuppressLint("StaticFieldLeak")
class AudioController private constructor(private val context: Context) {
    val player: ExoPlayer by lazy { createPlayer() }
    val equalizerManager: EqualizerManager by lazy { EqualizerManager(context, player) }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    val recentPlayedSongIds: List<Int> get() = _playbackState.value.recentPlayedSongIds

    private var progressJob: Job? = null
    private var paletteJob: Job? = null

    private var headsetClickCount = 0
    private var headsetClickJob: Job? = null

    fun handleHeadsetHookClick() {
        headsetClickCount++
        headsetClickJob?.cancel()
        headsetClickJob = scope.launch {
            delay(500)
            val count = headsetClickCount
            headsetClickCount = 0
            when (count) {
                1 -> togglePlayPause()
                2 -> next()
                3 -> previous()
                else -> {
                    if (count > 3) {
                        next()
                    } else {
                        togglePlayPause()
                    }
                }
            }
        }
    }

    private var preOfflineSnapshot: PreOfflineSnapshot? = null
    private var lastTransientRetrySongId: Int? = null

    init {
        restoreSavedState()
        observeNetworkState()
    }

    private fun capturePreOfflineSnapshotIfNeeded(resumeFromNext: Boolean = false) {
        val state = _playbackState.value
        val currentContext = state.context ?: return
        if (isOfflineBackupContext(currentContext)) return

        val baseIndex = state.contextIndex.coerceAtLeast(0)
        val targetResumeIndex = if (resumeFromNext) {
            (baseIndex + 1).coerceAtMost(state.contextOrder.size)
        } else {
            baseIndex.coerceAtMost(state.contextOrder.size)
        }

        preOfflineSnapshot = PreOfflineSnapshot(
            context = currentContext,
            contextOrder = state.contextOrder,
            resumeIndex = targetResumeIndex,
            preserveExactRandomOrder = state.shuffle
        )
    }

    private fun observeNetworkState() {
        scope.launch {
            NetworkMonitor.observeNetwork(context).collect { isOnline ->
                val state = _playbackState.value
                val currentSong = state.currentSong
                val currentContext = state.context

                if (!isOnline) {
                    if (currentContext != null && !isOfflineBackupContext(currentContext)) {
                        capturePreOfflineSnapshotIfNeeded(resumeFromNext = false)
                    }
                    if (state.isPlaying && currentSong != null && !isSongPlayableOffline(currentSong)) {
                        val hasBufferedAudio = try {
                            player.isPlaying &&
                                player.playbackState == Player.STATE_READY &&
                                player.bufferedPosition > player.currentPosition + 1500L
                        } catch (_: Exception) {
                            false
                        }
                        if (!hasBufferedAudio) {
                            // Immediately transition to available cached songs in the active order
                            handleOfflineFallback(autoShuffle = state.shuffle, resumeFromNext = false)
                        }
                    }
                } else {
                    if (isOfflineBackupContext(currentContext)) {
                        restoreOnlineContextOrStream()
                    } else if (isRestoredContext(currentContext)) {
                        preOfflineSnapshot = null
                        restoreOnlineContextOrStream()
                    } else {
                        preOfflineSnapshot = null
                        if (currentSong != null) {
                            preloadUpcomingTrack()
                        }
                    }
                }
            }
        }
    }

    private fun restoreOnlineContextOrStream() {
        val snapshotToRestore = preOfflineSnapshot
        scope.launch(Dispatchers.IO) {
            val repo = MusicRepository(context)
            val needsCatalogSongs = snapshotToRestore == null ||
                isRestoredContext(snapshotToRestore.context) ||
                snapshotToRestore.context.uri == "alaktra:playlist:alaktra_stream"
            val catalogSongs = if (needsCatalogSongs) {
                try {
                    repo.getSongs()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (!NetworkMonitor.isOnline(context)) return@withContext
                val currentState = _playbackState.value
                val currentContext = currentState.context
                val activeSnapshot = preOfflineSnapshot ?: snapshotToRestore

                if (isOfflineBackupContext(currentContext) && activeSnapshot != null) {
                    val restoredState = restoreStateFromPreOfflineSnapshot(
                        currentState = currentState,
                        snapshot = activeSnapshot,
                        freshCatalogSongs = catalogSongs
                    )
                    preOfflineSnapshot = null
                    _playbackState.value = restoredState
                    checkNearbyPinsLocationTracking(restoredState.context?.name)
                    persistCurrentState()
                    preloadUpcomingTrack()
                } else if (isOfflineBackupContext(currentContext) || isRestoredContext(currentContext)) {
                    if (catalogSongs.isNotEmpty()) {
                        val upgradedState = upgradeToStreamState(currentState, catalogSongs)
                        preOfflineSnapshot = null
                        _playbackState.value = upgradedState
                        persistCurrentState()
                        preloadUpcomingTrack()
                    }
                }
            }
        }
    }

    private fun restoreSavedState() {
        try {
            val prefs = com.example.data.preferences.AuthPreferences(context)
            val lastSong = prefs.getLastPlayedSong()
            val savedQueue = prefs.getSavedQueue()
            val savedContextInfo = prefs.getSavedPlaybackContext()
            val lastPos = prefs.getLastPlaybackPosition()
            val shuffle = prefs.getShuffleEnabled()
            val repeatStr = prefs.getRepeatModeName()
            val repeat = try { RepeatMode.valueOf(repeatStr) } catch (_: Exception) { RepeatMode.OFF }
            val recentIds = prefs.getRecentPlayedSongIds()

            if (lastSong != null || savedQueue.isNotEmpty()) {
                val targetSong = lastSong ?: savedQueue.first()
                val rawOrder = if (savedQueue.isNotEmpty()) savedQueue else listOf(targetSong)

                val initialContext = if (savedContextInfo != null) {
                    val ctxType = try {
                        ContextType.valueOf(savedContextInfo.typeName)
                    } catch (_: Exception) {
                        ContextType.PLAYLIST
                    }
                    PlaybackContext(
                        uri = savedContextInfo.uri,
                        type = ctxType,
                        name = savedContextInfo.name,
                        trackIds = savedContextInfo.tracks.map { it.id },
                        tracks = savedContextInfo.tracks
                    )
                } else {
                    PlaybackContext(
                        uri = "alaktra:restored",
                        type = ContextType.PLAYLIST,
                        name = "Last Session",
                        trackIds = rawOrder.map { it.id },
                        tracks = rawOrder
                    )
                }

                val order = if (shuffle) {
                    val naturalIds = initialContext.tracks.map { it.id }
                    val rawIds = rawOrder.map { it.id }
                    val looksAccidentallySequential = initialContext.tracks.size >= 4 &&
                        rawOrder.size >= 4 &&
                        rawIds == naturalIds.take(rawOrder.size)
                    if (looksAccidentallySequential) {
                        val others = initialContext.tracks.filter { it.id != targetSong.id }
                        listOf(targetSong) + ShuffleUtils.artistSpreadShuffle(others)
                    } else {
                        rawOrder
                    }
                } else {
                    if (savedContextInfo != null && initialContext.tracks.isNotEmpty()) {
                        initialContext.tracks
                    } else {
                        rawOrder
                    }
                }

                val idx = order.indexOfFirst { it.id == targetSong.id }.let { if (it >= 0) it else 0 }
                var restoredState = _playbackState.value.copy(
                    context = initialContext,
                    currentSong = targetSong,
                    contextOrder = order,
                    contextIndex = idx,
                    currentPositionMs = lastPos,
                    durationMs = (targetSong.duration * 1000).toLong(),
                    isPlaying = false,
                    shuffle = shuffle,
                    repeat = repeat,
                    recentPlayedSongIds = recentIds
                )
                restoredState = ensureContextBuffer(restoredState)
                _playbackState.value = restoredState
                extractPalette(targetSong.coverUrl, targetSong.dominantColorHex, targetSong.id)

                try {
                    val uri = if (targetSong.localPath != null && File(targetSong.localPath).exists()) {
                        Uri.fromFile(File(targetSong.localPath))
                    } else {
                        Uri.parse(targetSong.audioUrl)
                    }
                    val mediaMetadata = MediaMetadata.Builder()
                        .setTitle(targetSong.title)
                        .setArtist(targetSong.artist)
                        .setArtworkUri(Uri.parse(targetSong.coverUrl))
                        .build()
                    val mediaItem = MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(targetSong.id.toString())
                        .setCustomCacheKey("song:${targetSong.id}")
                        .setMediaMetadata(mediaMetadata)
                        .build()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    if (lastPos > 0) {
                        player.seekTo(lastPos)
                    }
                    player.playWhenReady = false
                } catch (e: Exception) {
                    Log.w("AudioController", "Failed to prepare player with restored song", e)
                }
            }
        } catch (e: Exception) {
            Log.w("AudioController", "Failed to restore saved playback state", e)
        }
    }

    private fun persistCurrentState() {
        try {
            val state = _playbackState.value
            val prefs = com.example.data.preferences.AuthPreferences(context)
            prefs.savePlaybackState(
                song = state.currentSong,
                positionMs = state.currentPositionMs,
                shuffle = state.shuffle,
                repeatModeName = state.repeat.name
            )
            val ctx = state.context
            if (!isOfflineBackupContext(ctx)) {
                if (state.contextOrder.isNotEmpty()) {
                    prefs.saveQueue(state.contextOrder)
                }
                if (ctx != null && !isRestoredContext(ctx) && ctx.tracks.isNotEmpty()) {
                    prefs.savePlaybackContext(
                        uri = ctx.uri,
                        typeName = ctx.type.name,
                        name = ctx.name,
                        tracks = ctx.tracks
                    )
                }
            } else if (preOfflineSnapshot == null && state.contextOrder.isNotEmpty()) {
                prefs.saveQueue(state.contextOrder)
            }
            if (state.recentPlayedSongIds.isNotEmpty()) {
                prefs.saveRecentPlayedSongIds(state.recentPlayedSongIds)
            }
        } catch (_: Exception) {}
    }

    private fun createPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip video codecs to prevent C2 system resource errors
            }

            override fun buildCameraMotionRenderers(
                context: Context,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip camera motion renderers
            }

            override fun buildImageRenderers(
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip image renderers
            }

            override fun buildMiscellaneousRenderers(
                context: Context,
                eventHandler: Handler,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                // Audio-only player: skip misc renderers
            }
        }.apply {
            setEnableDecoderFallback(false)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(
            AudioCache.createCacheDataSourceFactory(context)
        )

        val exo = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                syncPlaybackStatus()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                    equalizerManager.attachToCurrentSession()
                }
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val pos = exo.currentPosition.coerceAtLeast(0L)
                _playbackState.value = _playbackState.value.copy(currentPositionMs = pos)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("AudioController", "Playback error: ${error.errorCodeName} (${error.errorCode}): ${error.message}")
                val isOnline = NetworkMonitor.isOnline(context)
                val state = _playbackState.value
                val failedSong = state.currentSong

                if (!isOnline) {
                    exo.stop()
                    exo.clearMediaItems()
                    handleOfflineFallback(autoShuffle = state.shuffle, resumeFromNext = false)
                    return
                }

                if (failedSong != null && lastTransientRetrySongId != failedSong.id) {
                    lastTransientRetrySongId = failedSong.id
                    val resumePos = exo.currentPosition.coerceAtLeast(state.currentPositionMs).coerceAtLeast(0L)
                    try {
                        val uri = if (failedSong.localPath != null && File(failedSong.localPath).exists()) {
                            Uri.fromFile(File(failedSong.localPath))
                        } else {
                            Uri.parse(failedSong.audioUrl)
                        }
                        val mediaMetadata = MediaMetadata.Builder()
                            .setTitle(failedSong.title)
                            .setArtist(failedSong.artist)
                            .setArtworkUri(Uri.parse(failedSong.coverUrl))
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setUri(uri)
                            .setMediaId(failedSong.id.toString())
                            .setCustomCacheKey("song:${failedSong.id}")
                            .setMediaMetadata(mediaMetadata)
                            .build()
                        exo.setMediaItem(mediaItem)
                        exo.prepare()
                        if (resumePos > 0L) {
                            exo.seekTo(resumePos)
                        }
                        exo.play()
                        return
                    } catch (_: Exception) {}
                }

                exo.stop()
                exo.clearMediaItems()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                stopProgressTracker()
                scope.launch {
                    val songTitle = _playbackState.value.currentSong?.title ?: "track"
                    Toast.makeText(context, "Unable to play \"$songTitle\" (offline or stream error)", Toast.LENGTH_SHORT).show()
                }
            }
        })

        return exo
    }

    private var currentSessionSong: Song? = null
    private var currentSessionHistoryId: Long? = null
    private var currentSessionSongId: Int? = null
    private var currentSessionListenedSec: Long = 0L
    private var currentSongRecordedAsPlayed: Boolean = false
    private var lastTickTimeMs: Long = 0L

    private fun checkAndRecordIfPlayedThresholdMet() {
        if (currentSongRecordedAsPlayed) return
        val song = currentSessionSong ?: _playbackState.value.currentSong ?: return
        // Counted as played ONLY and ONLY if the song has been actively played for at least 20 seconds
        val hasMetThreshold = currentSessionListenedSec >= MIN_PLAYED_SECONDS_THRESHOLD

        if (hasMetThreshold) {
            recordSongAsPlayed(song, currentSessionListenedSec)
        }
    }

    private fun syncPlaybackStatus() {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.coerceAtLeast(0L)
        val isEnded = player.playbackState == Player.STATE_ENDED

        val effectivePlaying = player.playWhenReady && !isEnded && player.playbackState != Player.STATE_IDLE
        _playbackState.value = _playbackState.value.copy(
            isPlaying = effectivePlaying,
            currentPositionMs = pos,
            durationMs = if (dur > 0) dur else _playbackState.value.durationMs
        )

        if (effectivePlaying) {
            startProgressTracker()
        } else {
            stopProgressTracker()
        }
    }

    private fun flushCurrentListeningSession() {
        checkAndRecordIfPlayedThresholdMet()
        val sessionId = currentSessionHistoryId ?: return
        val listenedSec = currentSessionListenedSec
        if (listenedSec <= 0L) return
        scope.launch(Dispatchers.IO) {
            try {
                val db = com.example.data.local.AppDatabase.getInstance(context)
                db.updateListeningDuration(sessionId, listenedSec)
            } catch (e: Exception) {
                Log.w("AudioController", "Failed to flush listening duration", e)
            }
        }
    }

    private fun startProgressTracker() {
        if (progressJob?.isActive == true) return
        progressJob?.cancel()
        lastTickTimeMs = System.currentTimeMillis()
        progressJob = scope.launch {
            try {
                var counter = 0
                while (isActive) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.coerceAtLeast(0L)
                    val isActivelyPlaying = player.isPlaying

                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE,
                        currentPositionMs = pos,
                        durationMs = if (dur > 0) dur else _playbackState.value.durationMs
                    )

                    val now = System.currentTimeMillis()
                    if (isActivelyPlaying) {
                        // Accurately accumulate active listening seconds ONLY when audio is genuinely playing
                        val deltaMs = now - lastTickTimeMs
                        if (deltaMs >= 1000L) {
                            val secondsToAdd = (deltaMs / 1000L).coerceIn(1L, 4L)
                            lastTickTimeMs = now
                            currentSessionListenedSec += secondsToAdd

                            // Check if song has been played for at least 20 seconds of real listening time
                            checkAndRecordIfPlayedThresholdMet()

                            val sessionId = currentSessionHistoryId
                            // Periodically persist updated listened seconds every 5s once counted as played
                            if (sessionId != null && currentSessionListenedSec > 0L && currentSessionListenedSec % 5L == 0L) {
                                val sec = currentSessionListenedSec
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val db = com.example.data.local.AppDatabase.getInstance(context)
                                        db.updateListeningDuration(sessionId, sec)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } else {
                        // Not actively playing (paused, buffering, loading, idle, audio focus loss, etc.)
                        // Continuously reset lastTickTimeMs so time spent paused or loading is NEVER credited!
                        lastTickTimeMs = now

                        // Check if player has truly paused or stopped
                        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                            _playbackState.value = _playbackState.value.copy(isPlaying = false)
                            break
                        }
                    }

                    counter++
                    if (counter >= 25) { // Every ~5 seconds
                        counter = 0
                        scope.launch(Dispatchers.IO) {
                            persistCurrentState()
                        }
                    }
                    delay(200)
                }
            } catch (e: CancellationException) {
                // Expected on cancellation
            } catch (e: Exception) {
                Log.e("AudioController", "Exception in progress tracker loop", e)
            } finally {
                progressJob = null
                flushCurrentListeningSession()
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
        flushCurrentListeningSession()
        scope.launch(Dispatchers.IO) {
            persistCurrentState()
        }
    }

    fun playContext(
        context: PlaybackContext,
        startIndex: Int = 0,
        autoShuffle: Boolean = false
    ) {
        playContextInternal(
            context = context,
            startIndex = startIndex,
            autoShuffle = autoShuffle,
            clearPreOfflineSnapshot = true
        )
    }

    private fun playContextInternal(
        context: PlaybackContext,
        startIndex: Int = 0,
        autoShuffle: Boolean = false,
        clearPreOfflineSnapshot: Boolean = true
    ) {
        val songs = context.tracks
        if (songs.isEmpty()) return
        val shouldShuffle = autoShuffle || _playbackState.value.shuffle
        if (clearPreOfflineSnapshot) {
            preOfflineSnapshot = null
        }

        val isOnline = NetworkMonitor.isOnline(this.context)
        val validIndex = if (!isOnline) {
            val requested = songs.getOrNull(startIndex)
            if (requested != null && isSongPlayableOffline(requested) && !(autoShuffle && startIndex == 0 && songs.size > 1)) {
                startIndex.coerceIn(0, songs.size - 1)
            } else {
                val playableIndices = songs.indices.filter { isSongPlayableOffline(songs[it]) }
                if (playableIndices.isEmpty()) {
                    if (!isOfflineBackupContext(context)) {
                        val fallbackSeedOrder = if (shouldShuffle) {
                            val clampedStart = startIndex.coerceIn(0, songs.size - 1)
                            val startTrack = songs[clampedStart]
                            val others = songs.filterIndexed { i, _ -> i != clampedStart }
                            listOf(startTrack) + ShuffleUtils.artistSpreadShuffle(others)
                        } else {
                            songs
                        }
                        _playbackState.value = _playbackState.value.copy(shuffle = shouldShuffle)
                        preOfflineSnapshot = PreOfflineSnapshot(
                            context = context,
                            contextOrder = fallbackSeedOrder,
                            resumeIndex = if (shouldShuffle) 0 else startIndex.coerceIn(0, songs.size - 1),
                            preserveExactRandomOrder = shouldShuffle
                        )
                        playOfflineBackup(autoShuffle = shouldShuffle, isAutomaticFallback = true)
                    }
                    return
                }
                if (shouldShuffle) {
                    if (requested != null && isSongPlayableOffline(requested) && !autoShuffle) {
                        startIndex.coerceIn(0, songs.size - 1)
                    } else {
                        playableIndices.random()
                    }
                } else {
                    playableIndices.first()
                }
            }
        } else {
            if (autoShuffle && startIndex == 0 && songs.size > 1 && isOfflineBackupContext(context)) {
                songs.indices.random()
            } else {
                startIndex.coerceIn(0, songs.size - 1)
            }
        }

        val activeOrder = if (shouldShuffle) {
            val startSong = songs[validIndex]
            val otherSongs = songs.filterIndexed { i, _ -> i != validIndex }
            val shuffledOthers = ShuffleUtils.artistSpreadShuffle(otherSongs)
            listOf(startSong) + shuffledOthers
        } else {
            songs
        }
        val activeIndex = if (shouldShuffle) 0 else validIndex

        // Starting a new context must NOT clear userQueue — queued tracks survive a context change (Spotify behavior)
        var updatedState = _playbackState.value.copy(
            context = context,
            contextOrder = activeOrder,
            contextIndex = activeIndex,
            shuffle = shouldShuffle,
            autoplayTracks = emptyList()
        )
        updatedState = ensureContextBuffer(updatedState)
        _playbackState.value = updatedState
        checkNearbyPinsLocationTracking(context.name)
        playSongAtIndex(updatedState.contextIndex)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0, source: String = "Alaktra Stream") {
        if (songs.isEmpty()) return
        val contextType = when {
            source.startsWith("Album", ignoreCase = true) -> ContextType.ALBUM
            source.startsWith("Artist", ignoreCase = true) -> ContextType.ARTIST
            source.startsWith("Search", ignoreCase = true) -> ContextType.SEARCH
            else -> ContextType.PLAYLIST
        }
        val ctx = PlaybackContext(
            uri = "alaktra:${contextType.name.lowercase()}:${source.replace(" ", "_").lowercase()}",
            type = contextType,
            name = source,
            trackIds = songs.map { it.id },
            tracks = songs
        )
        playContext(ctx, startIndex = startIndex)
    }

    // Background location listener for dynamic 50m Nearby Pins proximity queue
    private var nearbyPinsLocationListener: LocationListener? = null

    private fun checkNearbyPinsLocationTracking(contextName: String?) {
        val isNearbyPins = contextName == "Nearby Pins" || _playbackState.value.context?.uri?.contains("nearby_pins") == true
        if (isNearbyPins) {
            startNearbyPinsTracking()
        } else {
            stopNearbyPinsTracking()
        }
    }

    private fun startNearbyPinsTracking() {
        if (nearbyPinsLocationListener != null) return
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val repo = MusicRepository(context)
                        val freshPins = repo.getNearbyPins(loc.latitude, loc.longitude)
                        withContext(Dispatchers.Main) {
                            updateNearbyPinsQueue(freshPins.map { it.song })
                        }
                    } catch (_: Exception) {}
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        nearbyPinsLocationListener = listener
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    1f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    1f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}
    }

    private fun stopNearbyPinsTracking() {
        nearbyPinsLocationListener?.let { listener ->
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(listener)
            } catch (_: Exception) {}
        }
        nearbyPinsLocationListener = null
    }

    /**
     * Dynamically updates the remaining queue for Nearby Pins without interrupting
     * the currently playing song. Even if the current song is no longer in the 50m range,
     * it continues playing to completion, and upcoming tracks are populated from the new 50m range.
     */
    fun updateNearbyPinsQueue(newSongs: List<Song>) {
        val state = _playbackState.value
        val isNearbyPins = state.context?.name == "Nearby Pins" || state.context?.uri?.contains("nearby_pins") == true
        if (!isNearbyPins) return

        val currentSong = state.currentSong
        if (currentSong == null) {
            if (newSongs.isNotEmpty()) {
                playQueue(newSongs, startIndex = 0, source = "Nearby Pins")
            }
            return
        }

        var updatedState = computeUpdatedNearbyPinsState(state, newSongs)
        updatedState = ensureContextBuffer(updatedState)
        _playbackState.value = updatedState
    }

    fun playSong(song: Song, source: String = "Now Playing") {
        val isOnline = NetworkMonitor.isOnline(context)
        if (!isOnline && !isSongPlayableOffline(song)) {
            val state = _playbackState.value
            val nextPlayable = findNextPlayableOfflineIndex(state.contextIndex + 1)
            if (nextPlayable != -1) {
                playSongAtIndex(nextPlayable)
            } else {
                playOfflineBackup(autoShuffle = state.shuffle, isAutomaticFallback = true)
            }
            return
        }
        val currentOrder = _playbackState.value.contextOrder
        val existingIndex = currentOrder.indexOfFirst { it.id == song.id }
        if (existingIndex != -1 && _playbackState.value.context != null) {
            _playbackState.value = _playbackState.value.copy(
                contextIndex = existingIndex
            )
            playSongAtIndex(existingIndex)
        } else {
            val ctx = PlaybackContext(
                uri = "alaktra:track:${song.id}",
                type = ContextType.SEARCH,
                name = source,
                trackIds = listOf(song.id),
                tracks = listOf(song)
            )
            playContext(ctx, startIndex = 0)
        }
    }

    private fun playSongDirectly(song: Song, pushToHistory: Boolean = false) {
        val isOnline = NetworkMonitor.isOnline(context)
        if (!isOnline && !isSongPlayableOffline(song)) {
            handleOfflineFallback(autoShuffle = _playbackState.value.shuffle, resumeFromNext = false)
            return
        }
        lastTransientRetrySongId = null
        val state = _playbackState.value
        val updatedHistory = if (pushToHistory && state.currentSong != null) {
            val wasContext = state.contextOrder.getOrNull(state.contextIndex)?.id == state.currentSong.id
            if (wasContext) state.history + state.currentSong else state.history
        } else {
            state.history
        }
        val updatedRecentPlayed = addToRecentPlayedHistory(song.id, state.recentPlayedSongIds)

        _playbackState.value = state.copy(
            currentSong = song,
            currentPositionMs = 0L,
            durationMs = (song.duration * 1000).toLong(),
            history = updatedHistory,
            recentPlayedSongIds = updatedRecentPlayed
        )

        // Resolve local file if available
        val uri = if (song.localPath != null && File(song.localPath).exists()) {
            Uri.fromFile(File(song.localPath))
        } else {
            Uri.parse(song.audioUrl)
        }

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(Uri.parse(song.coverUrl))
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id.toString())
            .setCustomCacheKey("song:${song.id}")
            .setMediaMetadata(mediaMetadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        ensureServiceStarted()

        // Extract palette from cover
        extractPalette(song.coverUrl, song.dominantColorHex, song.id)

        // Prepare listening session (counted as played only after >= 20s active playback)
        prepareNewSongListeningSession(song)

        persistCurrentState()
        preloadUpcomingTrack()
    }

    private fun playSongAtIndex(index: Int) {
        val state = _playbackState.value
        if (index !in state.contextOrder.indices) return

        val song = state.contextOrder[index]
        val isOnline = NetworkMonitor.isOnline(context)
        if (!isOnline && !isSongPlayableOffline(song)) {
            // Instantly skip forward to the next fully cached song in the playlist while preserving unplayed random tracks
            val nextPlayable = findNextPlayableOfflineIndex(index + 1)
            if (nextPlayable != -1 && nextPlayable != index) {
                val relocatedState = relocateOfflinePlayableInShuffleOrder(
                    state = state,
                    desiredSlotIndex = index,
                    playableIndex = nextPlayable
                )
                if (relocatedState !== state) {
                    _playbackState.value = relocatedState
                }
                playSongAtIndex(relocatedState.contextIndex)
                return
            }
            // Context has no cached songs: switch seamlessly to Offline Backup
            playOfflineBackup(autoShuffle = state.shuffle, isAutomaticFallback = true, resumeFromNext = false)
            return
        }

        lastTransientRetrySongId = null
        val updatedRecentPlayed = addToRecentPlayedHistory(song.id, state.recentPlayedSongIds)
        _playbackState.value = state.copy(
            currentSong = song,
            contextIndex = index,
            currentPositionMs = 0L,
            durationMs = (song.duration * 1000).toLong(),
            recentPlayedSongIds = updatedRecentPlayed
        )

        // Resolve local file if available
        val uri = if (song.localPath != null && File(song.localPath).exists()) {
            Uri.fromFile(File(song.localPath))
        } else {
            Uri.parse(song.audioUrl)
        }

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(Uri.parse(song.coverUrl))
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id.toString())
            .setCustomCacheKey("song:${song.id}")
            .setMediaMetadata(mediaMetadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        ensureServiceStarted()

        // Extract palette from cover
        extractPalette(song.coverUrl, song.dominantColorHex, song.id)

        // Prepare listening session (counted as played only after >= 20s active playback)
        prepareNewSongListeningSession(song)

        persistCurrentState()
        preloadUpcomingTrack()
    }

    fun getUpcomingTrack(): Song? {
        val state = _playbackState.value
        if (state.userQueue.isNotEmpty()) {
            return state.userQueue.first()
        }
        val nextIdx = state.contextIndex + 1
        if (nextIdx in state.contextOrder.indices) {
            return state.contextOrder[nextIdx]
        }
        if (state.repeat == RepeatMode.CONTEXT && state.contextOrder.isNotEmpty()) {
            return state.contextOrder.first()
        }
        if (state.autoplayTracks.isNotEmpty()) {
            return state.autoplayTracks.first()
        }
        return null
    }

    private fun preloadUpcomingTrack() {
        val state = _playbackState.value
        val toPreload = mutableListOf<Song>()
        getUpcomingTrack()?.let { toPreload.add(it) }
        toPreload.addAll(state.userQueue.take(2))
        if (state.contextOrder.isNotEmpty()) {
            val nextIdx = state.contextIndex + 1
            if (nextIdx < state.contextOrder.size) {
                toPreload.add(state.contextOrder[nextIdx])
            }
        }
        for (song in toPreload.distinctBy { it.id }) {
            if (song.localPath == null || !File(song.localPath).exists()) {
                AudioCache.preloadTrack(context, song.audioUrl, song.id)
            }
            try {
                val req = ImageRequest.Builder(context)
                    .data(song.coverUrl)
                    .build()
                context.imageLoader.enqueue(req)
            } catch (_: Exception) {}
        }
    }

    fun ensureServiceStarted() {
        try {
            val serviceIntent = Intent(context, MusicPlaybackService::class.java)
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w("AudioController", "Could not start playback service", e)
        }
    }

    private var lastRecordedSongId: Int? = null
    private var lastRecordedTimeMs: Long = 0L

    private fun prepareNewSongListeningSession(song: Song) {
        // Flush previous session if active and qualified
        flushCurrentListeningSession()

        val isDifferentSong = song.id != currentSessionSongId
        currentSessionSong = song
        currentSessionSongId = song.id
        if (isDifferentSong) {
            currentSessionListenedSec = 0L
            currentSessionHistoryId = null
            currentSongRecordedAsPlayed = false
        }
        lastTickTimeMs = System.currentTimeMillis()
    }

    private fun recordSongAsPlayed(song: Song, initialListenedSec: Long) {
        if (currentSongRecordedAsPlayed) return
        currentSongRecordedAsPlayed = true
        val now = System.currentTimeMillis()
        if (song.id == lastRecordedSongId && (now - lastRecordedTimeMs) < 1000L) {
            return
        }
        lastRecordedSongId = song.id
        lastRecordedTimeMs = now

        scope.launch(Dispatchers.IO) {
            try {
                val db = com.example.data.local.AppDatabase.getInstance(context)
                val newId = db.insertListeningHistory(
                    com.example.data.local.ListeningHistoryEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        audioUrl = song.audioUrl,
                        coverUrl = song.coverUrl,
                        duration = song.duration,
                        listenedSeconds = initialListenedSec.coerceAtLeast(0L),
                        playedAt = now
                    )
                )
                currentSessionHistoryId = newId
            } catch (e: Exception) {
                Log.w("AudioController", "Failed to record played song history", e)
            }
        }
    }

    fun togglePlayPause() {
        if (player.playWhenReady) {
            pause()
        } else {
            play()
        }
    }

    fun pause() {
        player.pause()
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            currentPositionMs = if (currentPos > 0L) currentPos else _playbackState.value.currentPositionMs
        )
        stopProgressTracker()
        scope.launch(Dispatchers.IO) {
            persistCurrentState()
        }
    }

    fun stop() {
        stopNearbyPinsTracking()
        player.pause()
        player.seekTo(0)
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            currentPositionMs = 0L
        )
        stopProgressTracker()
        scope.launch(Dispatchers.IO) {
            persistCurrentState()
        }
    }

    fun play() {
        if (player.currentMediaItem == null && _playbackState.value.currentSong != null) {
            val song = _playbackState.value.currentSong!!
            val savedPos = _playbackState.value.currentPositionMs
            playSongDirectly(song, pushToHistory = false)
            if (savedPos > 0) {
                player.seekTo(savedPos)
            }
        } else {
            ensureServiceStarted()
            player.play()
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            startProgressTracker()
            scope.launch(Dispatchers.IO) {
                persistCurrentState()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        if (player.currentMediaItem != null) {
            player.seekTo(target)
        } else if (_playbackState.value.currentSong != null) {
            val song = _playbackState.value.currentSong!!
            playSongDirectly(song, pushToHistory = false)
            player.seekTo(target)
        }
        _playbackState.value = _playbackState.value.copy(currentPositionMs = target)
        scope.launch(Dispatchers.IO) {
            persistCurrentState()
        }
    }

    /**
     * Advance ladder: strict top-down execution with early returns (Steps 1-4).
     */
    fun advanceTrack(reason: AdvanceReason) {
        val state = _playbackState.value
        val isOnline = NetworkMonitor.isOnline(context)

        // 1. repeat === 'track' && reason === 'ended'
        //    -> reset elapsedSec to 0, keep currentTrackId, return.
        //    This check sits ABOVE the user queue on purpose: a queued song must not cut
        //    into a looping track. An explicit skip press still moves on.
        if (state.repeat == RepeatMode.TRACK && reason == AdvanceReason.ENDED) {
            seekTo(0)
            player.play()
            return
        }

        // Offline mode: automatically skip uncached tracks so playback never stops or waits for network
        if (!isOnline) {
            val offlineQueueIndex = state.userQueue.indexOfFirst { isSongPlayableOffline(it) }
            if (offlineQueueIndex != -1) {
                val nextSong = state.userQueue[offlineQueueIndex]
                val remainingUserQueue = state.userQueue.filterIndexed { idx, _ -> idx != offlineQueueIndex }
                _playbackState.value = state.copy(userQueue = remainingUserQueue)
                playSongDirectly(nextSong, pushToHistory = false)
                return
            }

            val desiredSlot = state.contextIndex + 1
            val nextPlayableIndex = findNextPlayableOfflineIndex(desiredSlot)
            if (nextPlayableIndex != -1 && nextPlayableIndex != state.contextIndex) {
                val outgoingTrack = state.currentSong
                val wasContext = outgoingTrack != null && state.contextOrder.getOrNull(state.contextIndex)?.id == outgoingTrack.id
                val updatedHistory = if (outgoingTrack != null && wasContext) {
                    state.history + outgoingTrack
                } else {
                    state.history
                }
                var updatedState = relocateOfflinePlayableInShuffleOrder(
                    state = state.copy(history = updatedHistory),
                    desiredSlotIndex = desiredSlot,
                    playableIndex = nextPlayableIndex
                )
                updatedState = ensureContextBuffer(updatedState)
                _playbackState.value = updatedState
                if (!isOfflineBackupContext(updatedState.context)) {
                    capturePreOfflineSnapshotIfNeeded(resumeFromNext = false)
                }
                playSongAtIndex(updatedState.contextIndex)
                return
            }

            // Current queue exhausted for offline: switch seamlessly to Offline Backup (cache memory)
            handleOfflineFallback(autoShuffle = state.shuffle, resumeFromNext = true)
            return
        }

        // 2. userQueue.length > 0
        //    -> shift the head, set it as currentTrackId, return.
        //    contextIndex does NOT move. Do not push the outgoing track to history if it
        //    itself came from the user queue.
        if (state.userQueue.isNotEmpty()) {
            val nextSong = state.userQueue.first()
            val remainingUserQueue = state.userQueue.drop(1)
            _playbackState.value = state.copy(userQueue = remainingUserQueue)
            playSongDirectly(nextSong, pushToHistory = false)
            return
        }

        // 3. contextIndex + 1 < contextOrder.length
        //    -> push outgoing track to history, increment contextIndex, play
        //       contextOrder[contextIndex], return.
        val nextIndex = state.contextIndex + 1
        if (nextIndex < state.contextOrder.size) {
            val outgoingTrack = state.currentSong
            val wasContext = outgoingTrack != null && state.contextOrder.getOrNull(state.contextIndex)?.id == outgoingTrack.id
            val updatedHistory = if (outgoingTrack != null && wasContext) {
                state.history + outgoingTrack
            } else {
                state.history
            }
            var updatedState = state.copy(
                contextIndex = nextIndex,
                history = updatedHistory
            )
            updatedState = ensureContextBuffer(updatedState)
            _playbackState.value = updatedState
            playSongAtIndex(updatedState.contextIndex)
            return
        }

        // 4. context exhausted:
        //    - repeat === 'context' -> if shuffle is on, regenerate contextOrder with a
        //      FRESH seed (never replay the same random order), set contextIndex = 0,
        //      play it.
        //    - otherwise -> populate autoplayTracks and play the first one.
        if (state.repeat == RepeatMode.CONTEXT && state.contextOrder.isNotEmpty()) {
            val outgoingTrack = state.currentSong
            val wasContext = outgoingTrack != null && state.contextOrder.getOrNull(state.contextIndex)?.id == outgoingTrack.id
            val updatedHistory = if (outgoingTrack != null && wasContext) {
                state.history + outgoingTrack
            } else {
                state.history
            }
            val naturalTracks = state.context?.tracks ?: state.contextOrder
            val newOrder = if (state.shuffle) {
                val shuffled = ShuffleUtils.artistSpreadShuffle(naturalTracks)
                val eligibleIndex = shuffled.indexOfFirst { it.id !in state.recentPlayedSongIds }
                if (eligibleIndex > 0) {
                    val eligibleTrack = shuffled[eligibleIndex]
                    listOf(eligibleTrack) + shuffled.filterIndexed { i, _ -> i != eligibleIndex }
                } else {
                    shuffled
                }
            } else {
                naturalTracks
            }
            var updatedState = state.copy(
                contextOrder = newOrder,
                contextIndex = 0,
                history = updatedHistory
            )
            updatedState = ensureContextBuffer(updatedState)
            _playbackState.value = updatedState
            playSongAtIndex(updatedState.contextIndex)
            return
        } else {
            // While repeat !== 'off', autoplay never triggers
            if (state.repeat == RepeatMode.OFF) {
                if (state.autoplayTracks.isNotEmpty()) {
                    val nextAutoplay = state.autoplayTracks.first()
                    val remainingAutoplay = state.autoplayTracks.drop(1)
                    _playbackState.value = state.copy(autoplayTracks = remainingAutoplay)
                    playSongDirectly(nextAutoplay, pushToHistory = false)
                    return
                } else {
                    val candidateTracks = state.context?.tracks ?: emptyList()
                    if (candidateTracks.isNotEmpty()) {
                        val generated = candidateTracks.shuffled().take(5)
                        val first = generated.first()
                        val remaining = generated.drop(1)
                        _playbackState.value = state.copy(autoplayTracks = remaining)
                        playSongDirectly(first, pushToHistory = false)
                        return
                    }
                }
            }
        }

        // Queue/context exhausted and no further tracks: stop playback cleanly and reset position
        stop()
    }

    fun next() {
        advanceTrack(AdvanceReason.SKIP)
    }

    /**
     * previousTrack(): if elapsedSec > 3, restart current track. Otherwise pop
     * history. Never return to a consumed userQueue track.
     */
    fun previousTrack() {
        val state = _playbackState.value
        val elapsedSec = state.currentPositionMs / 1000
        if (elapsedSec > 3) {
            seekTo(0)
            return
        }

        val isOnline = NetworkMonitor.isOnline(context)
        if (!isOnline) {
            val prevInHistory = state.history.lastOrNull { isSongPlayableOffline(it) }
            if (prevInHistory != null) {
                val remHistory = state.history.filter { it.id != prevInHistory.id }
                val orderIdx = state.contextOrder.indexOfFirst { it.id == prevInHistory.id }
                _playbackState.value = state.copy(
                    history = remHistory,
                    contextIndex = if (orderIdx != -1) orderIdx else state.contextIndex
                )
                playSongDirectly(prevInHistory, pushToHistory = false)
                return
            }

            val prevPlayable = findPreviousPlayableOfflineIndex(state.contextIndex)
            if (prevPlayable != -1 && prevPlayable != state.contextIndex) {
                _playbackState.value = state.copy(contextIndex = prevPlayable)
                playSongAtIndex(prevPlayable)
                return
            }

            seekTo(0)
            return
        }

        if (state.history.isNotEmpty()) {
            val lastPlayed = state.history.last()
            val remainingHistory = state.history.dropLast(1)
            val orderIndex = state.contextOrder.indexOfFirst { it.id == lastPlayed.id }
            val newIndex = if (orderIndex != -1) orderIndex else state.contextIndex
            _playbackState.value = state.copy(
                history = remainingHistory,
                contextIndex = newIndex
            )
            playSongDirectly(lastPlayed, pushToHistory = false)
        } else if (state.contextIndex > 0) {
            val prevIndex = state.contextIndex - 1
            _playbackState.value = state.copy(contextIndex = prevIndex)
            playSongAtIndex(prevIndex)
        } else {
            seekTo(0)
        }
    }

    fun previous() {
        previousTrack()
    }

    /**
     * Checks if a song can be played without an active internet connection.
     * Either the track was downloaded to permanent disk, or it is 100% fully
     * cached in Media3 LRU SimpleCache memory.
     */
    fun isSongPlayableOffline(song: Song): Boolean {
        if (song.localPath != null && File(song.localPath).exists() && File(song.localPath).length() > 0) return true
        val downloadedFile = File(File(context.filesDir, "downloads"), "${song.id}.mp3")
        if (downloadedFile.exists() && downloadedFile.length() > 0) return true
        return AudioCache.isSongFullyCached(context, song)
    }

    /**
     * Finds next playable offline track index in contextOrder from [fromIndex].
     */
    fun findNextPlayableOfflineIndex(fromIndex: Int): Int {
        val state = _playbackState.value
        val order = state.contextOrder
        if (order.isEmpty()) return -1
        val start = fromIndex.coerceIn(0, order.size)
        for (i in start until order.size) {
            if (isSongPlayableOffline(order[i])) return i
        }
        for (i in 0 until start) {
            if (isSongPlayableOffline(order[i])) return i
        }
        return -1
    }

    /**
     * Finds previous playable offline track index in contextOrder from [fromIndex].
     */
    fun findPreviousPlayableOfflineIndex(fromIndex: Int): Int {
        val state = _playbackState.value
        val order = state.contextOrder
        if (order.isEmpty()) return -1
        val start = (fromIndex - 1).coerceAtMost(order.size - 1)
        for (i in start downTo 0) {
            if (isSongPlayableOffline(order[i])) return i
        }
        for (i in (order.size - 1) downTo start) {
            if (isSongPlayableOffline(order[i])) return i
        }
        return -1
    }

    /**
     * Spotify-style offline recovery:
     * When offline and an uncached track cannot be played, smoothly switch
     * to the next available cached song in the queue or load the full
     * Offline Backup (cached playlist) so playback never stops.
     */
    fun handleOfflineFallback(
        autoShuffle: Boolean = _playbackState.value.shuffle,
        resumeFromNext: Boolean = false
    ) {
        val state = _playbackState.value
        val shouldShuffle = autoShuffle || state.shuffle

        // 1. Check user queue first
        val cachedUserQueueIndex = state.userQueue.indexOfFirst { isSongPlayableOffline(it) }
        if (cachedUserQueueIndex != -1) {
            playUserQueueItem(cachedUserQueueIndex)
            return
        }

        // 2. Check forward in contextOrder
        val desiredSlot = (state.contextIndex + 1).coerceAtLeast(0)
        val nextPlayableIndex = findNextPlayableOfflineIndex(desiredSlot)
        if (nextPlayableIndex != -1 && nextPlayableIndex != state.contextIndex) {
            val relocatedState = relocateOfflinePlayableInShuffleOrder(
                state = state,
                desiredSlotIndex = desiredSlot,
                playableIndex = nextPlayableIndex
            )
            if (relocatedState !== state) {
                _playbackState.value = relocatedState
            }
            if (!isOfflineBackupContext(relocatedState.context)) {
                capturePreOfflineSnapshotIfNeeded(resumeFromNext = false)
            }
            playSongAtIndex(relocatedState.contextIndex)
            return
        }

        // 3. Current queue exhausted: switch silently to cached songs from background memory
        playOfflineBackup(
            autoShuffle = shouldShuffle,
            isAutomaticFallback = true,
            resumeFromNext = resumeFromNext
        )
    }

    /**
     * Seamlessly starts background playback of songs stored in the cache memory / downloads.
     */
    fun playOfflineBackup(
        autoShuffle: Boolean = _playbackState.value.shuffle,
        isAutomaticFallback: Boolean = false,
        resumeFromNext: Boolean = false
    ) {
        if (isAutomaticFallback) {
            capturePreOfflineSnapshotIfNeeded(resumeFromNext = resumeFromNext)
        } else {
            preOfflineSnapshot = null
        }
        val shouldShuffle = autoShuffle || _playbackState.value.shuffle
        scope.launch(Dispatchers.IO) {
            val repo = MusicRepository(context)
            val offlineSongs = repo.getOfflineBackupSongs()
            withContext(Dispatchers.Main) {
                if (isAutomaticFallback && NetworkMonitor.isOnline(context)) {
                    // Network already recovered while loading offline backup; keep or restore online queue
                    return@withContext
                }
                if (offlineSongs.isNotEmpty()) {
                    val offlineCtx = PlaybackContext(
                        uri = "alaktra:playlist:offline_backup",
                        type = ContextType.PLAYLIST,
                        name = "Offline Backup",
                        trackIds = offlineSongs.map { it.id },
                        tracks = offlineSongs
                    )
                    val effectiveShuffle = shouldShuffle || _playbackState.value.shuffle
                    val startIdx = if (effectiveShuffle && offlineSongs.size > 1) {
                        offlineSongs.indices.random()
                    } else {
                        0
                    }
                    playContextInternal(
                        context = offlineCtx,
                        startIndex = startIdx,
                        autoShuffle = effectiveShuffle,
                        clearPreOfflineSnapshot = !isAutomaticFallback
                    )
                }
            }
        }
    }

    /**
     * Shuffle is a property of the CONTEXT layer only. It must never touch userQueue.
     * toggleShuffle(true):
     *   - generate a shuffled ordering of context.trackIds
     *   - move currentTrackId to position 0 of that ordering
     *   - set contextIndex = 0
     *   - the currently playing track must NOT change when shuffle is toggled
     * toggleShuffle(false):
     *   - contextOrder = [...context.trackIds]
     *   - contextIndex = contextOrder.indexOf(currentTrackId)
     *   - position preserved; only the neighbours change
     */
    fun ensureContextBuffer(state: PlaybackState): PlaybackState {
        return ensureContextBufferStatic(state)
    }

    fun ensureBuffer() {
        val updated = ensureContextBuffer(_playbackState.value)
        if (updated != _playbackState.value) {
            _playbackState.value = updated
        }
    }

    fun toggleShuffle(forceEnable: Boolean? = null) {
        val state = _playbackState.value
        val newShuffle = forceEnable ?: !state.shuffle
        val naturalTracks = state.context?.tracks ?: state.contextOrder

        if (!newShuffle) {
            preOfflineSnapshot = preOfflineSnapshot?.copy(preserveExactRandomOrder = false)
        }

        if (naturalTracks.isEmpty()) {
            _playbackState.value = state.copy(shuffle = newShuffle)
            persistCurrentState()
            return
        }

        val currentSong = state.currentSong
        var newState = if (newShuffle) {
            if (currentSong != null && naturalTracks.isNotEmpty()) {
                val otherSongs = naturalTracks.filter { it.id != currentSong.id }
                val shuffledOthers = ShuffleUtils.artistSpreadShuffle(otherSongs)
                val newOrder = listOf(currentSong) + shuffledOthers
                state.copy(
                    shuffle = true,
                    contextOrder = newOrder,
                    contextIndex = 0
                )
            } else {
                val shuffled = ShuffleUtils.artistSpreadShuffle(naturalTracks)
                state.copy(
                    shuffle = true,
                    contextOrder = shuffled,
                    contextIndex = 0
                )
            }
        } else {
            val restoredIndex = if (currentSong != null) {
                val idx = naturalTracks.indexOfFirst { it.id == currentSong.id }
                if (idx != -1) idx else 0
            } else {
                0
            }
            state.copy(
                shuffle = false,
                contextOrder = naturalTracks,
                contextIndex = restoredIndex
            )
        }

        newState = ensureContextBuffer(newState)
        _playbackState.value = newState
        persistCurrentState()
    }

    /**
     * Repeat cycle: OFF -> CONTEXT -> TRACK -> OFF
     */
    fun cycleRepeatMode(): RepeatMode {
        val nextMode = when (_playbackState.value.repeat) {
            RepeatMode.OFF -> RepeatMode.CONTEXT
            RepeatMode.CONTEXT -> RepeatMode.TRACK
            RepeatMode.TRACK -> RepeatMode.OFF
        }
        setRepeatMode(nextMode)
        return nextMode
    }

    fun setRepeatMode(mode: RepeatMode) {
        var state = _playbackState.value.copy(repeat = mode)
        if (mode == RepeatMode.CONTEXT) {
            state = ensureContextBuffer(state)
        } else if (mode == RepeatMode.OFF) {
            val naturalTracks = state.context?.tracks ?: emptyList()
            if (naturalTracks.isNotEmpty()) {
                val currentIdx = state.contextIndex
                val naturalSize = naturalTracks.size
                val maxLimit = currentIdx + 1 + naturalSize
                if (state.contextOrder.size > maxLimit) {
                    state = state.copy(contextOrder = state.contextOrder.take(maxLimit))
                }
            }
        }
        _playbackState.value = state
        persistCurrentState()
    }

    fun toggleLoop() {
        cycleRepeatMode()
    }

    /** Add song to the end of user queue ("Next in Queue") - Destructive FIFO */
    fun addToQueue(song: Song) {
        val updated = _playbackState.value.userQueue + song
        _playbackState.value = _playbackState.value.copy(userQueue = updated)
    }

    /** Add song immediately next in line ("Play Next") */
    fun playNext(song: Song) {
        val updated = listOf(song) + _playbackState.value.userQueue
        _playbackState.value = _playbackState.value.copy(userQueue = updated)
    }

    /** Alias for backward compatibility */
    fun addToUpNext(song: Song) {
        addToQueue(song)
    }

    /** Promote a track from context layer into userQueue */
    fun promoteToUserQueue(song: Song) {
        // Pushes track into userQueue without modifying contextOrder or removing from context
        _playbackState.value = _playbackState.value.copy(
            userQueue = _playbackState.value.userQueue + song
        )
    }

    /** Promote a track from context layer into userQueue at specific position */
    fun promoteToUserQueueAt(song: Song, targetIndex: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        val clamped = targetIndex.coerceIn(0, current.size)
        current.add(clamped, song)
        _playbackState.value = _playbackState.value.copy(userQueue = current)
    }

    /** Remove a song from the user-enqueued layer */
    fun removeFromUserQueue(index: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _playbackState.value = _playbackState.value.copy(userQueue = current)
        }
    }

    /** Clear all songs from the user-enqueued layer (scoped to user queue only) */
    fun clearUserQueue() {
        _playbackState.value = _playbackState.value.copy(userQueue = emptyList())
    }

    /** Reorder songs inside the user-enqueued layer */
    fun reorderUserQueue(from: Int, to: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        if (from in current.indices && to in current.indices) {
            val moved = current.removeAt(from)
            current.add(to, moved)
            _playbackState.value = _playbackState.value.copy(userQueue = current)
        }
    }

    /** Immediately play a song from the user-enqueued layer and remove it */
    fun playUserQueueItem(index: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        if (index in current.indices) {
            val songToPlay = current.removeAt(index)
            _playbackState.value = _playbackState.value.copy(userQueue = current)
            playSongDirectly(songToPlay, pushToHistory = false)
        }
    }

    /** Play a song directly from the context queue */
    fun playContextQueueItem(index: Int) {
        if (index in _playbackState.value.contextOrder.indices) {
            val outgoingTrack = _playbackState.value.currentSong
            val wasContext = outgoingTrack != null && _playbackState.value.contextOrder.getOrNull(_playbackState.value.contextIndex)?.id == outgoingTrack.id
            val updatedHistory = if (outgoingTrack != null && wasContext) {
                _playbackState.value.history + outgoingTrack
            } else {
                _playbackState.value.history
            }
            var updatedState = _playbackState.value.copy(
                contextIndex = index,
                history = updatedHistory
            )
            updatedState = ensureContextBuffer(updatedState)
            _playbackState.value = updatedState
            playSongAtIndex(updatedState.contextIndex)
        }
    }

    /** Remove a song from the base/context queue */
    fun removeFromQueue(index: Int) {
        val currentOrder = _playbackState.value.contextOrder.toMutableList()
        if (index in currentOrder.indices) {
            val removedSong = currentOrder.removeAt(index)
            val updatedNatural = _playbackState.value.context?.tracks?.filter { it.id != removedSong.id } ?: currentOrder
            var newIdx = _playbackState.value.contextIndex
            if (index < newIdx) {
                newIdx--
            } else if (index == newIdx && currentOrder.isNotEmpty()) {
                newIdx = newIdx.coerceAtMost(currentOrder.size - 1)
                playSongAtIndex(newIdx)
            }
            val updatedContext = _playbackState.value.context?.copy(
                tracks = updatedNatural,
                trackIds = updatedNatural.map { it.id }
            )
            var updatedState = _playbackState.value.copy(
                context = updatedContext,
                contextOrder = currentOrder,
                contextIndex = newIdx
            )
            updatedState = ensureContextBuffer(updatedState)
            _playbackState.value = updatedState
        }
    }

    fun reorderQueue(from: Int, to: Int) {
        val currentOrder = _playbackState.value.contextOrder.toMutableList()
        if (from in currentOrder.indices && to in currentOrder.indices) {
            val moved = currentOrder.removeAt(from)
            currentOrder.add(to, moved)
            val currSong = _playbackState.value.currentSong
            val newIndex = if (currSong != null) currentOrder.indexOfFirst { it.id == currSong.id } else _playbackState.value.contextIndex
            _playbackState.value = _playbackState.value.copy(
                contextOrder = currentOrder,
                contextIndex = if (newIndex != -1) newIndex else _playbackState.value.contextIndex
            )
        }
    }

    private fun handleTrackEnded() {
        flushCurrentListeningSession()
        if (_stopAtEndOfTrack.value) {
            _stopAtEndOfTrack.value = false
            _isSleepTimerActive.value = false
            _sleepTimerRemainingSeconds.value = null
            sleepTimerJob?.cancel()
            stop()
            return
        }
        advanceTrack(AdvanceReason.ENDED)
    }

    fun updateSongLiked(songId: Int, isLiked: Boolean) {
        val state = _playbackState.value
        val updatedOrder = state.contextOrder.map { if (it.id == songId) it.copy(isLiked = isLiked) else it }
        val updatedSong = if (state.currentSong?.id == songId) state.currentSong.copy(isLiked = isLiked) else state.currentSong
        val updatedUserQueue = state.userQueue.map { if (it.id == songId) it.copy(isLiked = isLiked) else it }
        val updatedContext = state.context?.copy(
            tracks = state.context.tracks.map { if (it.id == songId) it.copy(isLiked = isLiked) else it }
        )

        _playbackState.value = state.copy(
            context = updatedContext,
            contextOrder = updatedOrder,
            currentSong = updatedSong,
            userQueue = updatedUserQueue
        )
    }

    fun resetForLogout() {
        preOfflineSnapshot = null
        lastTransientRetrySongId = null
        player.stop()
        player.clearMediaItems()
        _playbackState.value = PlaybackState()
        try {
            val prefs = com.example.data.preferences.AuthPreferences(context)
            prefs.clearPlaybackState()
        } catch (_: Exception) {}
    }

    private fun extractColorFromExif(pathOrFile: Any?): Color? {
        return try {
            val exif = when (pathOrFile) {
                is File -> if (pathOrFile.exists()) android.media.ExifInterface(pathOrFile.absolutePath) else null
                is String -> {
                    val cleanPath = pathOrFile.removePrefix("file://")
                    val f = File(cleanPath)
                    if (f.exists()) android.media.ExifInterface(f.absolutePath) else null
                }
                else -> null
            } ?: return null

            val rawMeta = exif.getAttribute(android.media.ExifInterface.TAG_USER_COMMENT)
                ?: exif.getAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION)
                ?: exif.getAttribute(android.media.ExifInterface.TAG_SOFTWARE)
            if (!rawMeta.isNullOrBlank()) {
                val hexRegex = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
                val match = hexRegex.find(rawMeta)
                if (match != null) {
                    Color(android.graphics.Color.parseColor(match.value))
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPalette(imageUrl: String, overrideHex: String? = null, songId: Int? = null) {
        paletteJob?.cancel()

        // 1. Check if the song has a locally saved cover artwork file (e.g. downloaded covers)
        var localCoverFile: File? = null
        if (songId != null) {
            val downloadsCover = File(context.filesDir, "downloads/${songId}_cover.jpg")
            if (downloadsCover.exists()) {
                localCoverFile = downloadsCover
            }
        }
        if (localCoverFile == null && (imageUrl.startsWith("file://") || imageUrl.startsWith("/"))) {
            val f = File(imageUrl.removePrefix("file://"))
            if (f.exists()) localCoverFile = f
        }

        // If local cover file exists, read embedded color metadata from the cover image EXIF directly
        if (localCoverFile != null) {
            val exifColor = extractColorFromExif(localCoverFile)
            if (exifColor != null) {
                _playbackState.value = _playbackState.value.copy(
                    dominantColor = exifColor,
                    secondaryColor = exifColor
                )
                return
            }
        }

        // 2. Check Coil disk cache for previously cached cover image file to read EXIF color metadata
        try {
            val snapshot = context.imageLoader.diskCache?.openSnapshot(imageUrl)
            if (snapshot != null) {
                val cachedFile = snapshot.data.toFile()
                snapshot.close()
                if (cachedFile.exists()) {
                    val exifColor = extractColorFromExif(cachedFile)
                    if (exifColor != null) {
                        _playbackState.value = _playbackState.value.copy(
                            dominantColor = exifColor,
                            secondaryColor = exifColor
                        )
                        return
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback to song record overrideHex if the cover file EXIF is not yet cached on disk
        if (!overrideHex.isNullOrBlank()) {
            try {
                val parsed = Color(android.graphics.Color.parseColor(overrideHex))
                _playbackState.value = _playbackState.value.copy(
                    dominantColor = parsed,
                    secondaryColor = parsed
                )
            } catch (_: Exception) {}
        } else {
            // Default fallback
            _playbackState.value = _playbackState.value.copy(
                dominantColor = Color.Transparent,
                secondaryColor = Color.Transparent
            )
        }

        // 4. Asynchronously fetch the cover image and extract embedded color metadata directly from the cover file
        if (imageUrl.isNotBlank() && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            paletteJob = scope.launch(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .allowHardware(false)
                        .build()
                    val result = context.imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val diskSnapshot = context.imageLoader.diskCache?.openSnapshot(imageUrl)
                        if (diskSnapshot != null) {
                            val cachedFile = diskSnapshot.data.toFile()
                            diskSnapshot.close()
                            val exifColor = extractColorFromExif(cachedFile)
                            if (exifColor != null) {
                                withContext(Dispatchers.Main) {
                                    _playbackState.value = _playbackState.value.copy(
                                        dominantColor = exifColor,
                                        secondaryColor = exifColor
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingSeconds = MutableStateFlow<Int?>(null)
    val sleepTimerRemainingSeconds: StateFlow<Int?> = _sleepTimerRemainingSeconds.asStateFlow()

    private val _isSleepTimerActive = MutableStateFlow(false)
    val isSleepTimerActive: StateFlow<Boolean> = _isSleepTimerActive.asStateFlow()

    private val _stopAtEndOfTrack = MutableStateFlow(false)
    val stopAtEndOfTrack: StateFlow<Boolean> = _stopAtEndOfTrack.asStateFlow()

    fun setSleepTimer(minutes: Int, endOfTrack: Boolean = false) {
        sleepTimerJob?.cancel()
        _stopAtEndOfTrack.value = endOfTrack

        if (endOfTrack) {
            _isSleepTimerActive.value = true
            val remainingMs = (player.duration - player.currentPosition).coerceAtLeast(0L)
            _sleepTimerRemainingSeconds.value = (remainingMs / 1000).toInt()
            sleepTimerJob = scope.launch {
                while (isActive && _stopAtEndOfTrack.value) {
                    val rem = ((player.duration - player.currentPosition).coerceAtLeast(0L) / 1000).toInt()
                    _sleepTimerRemainingSeconds.value = rem
                    if (rem <= 0 && !player.isPlaying) {
                        break
                    }
                    delay(500)
                }
            }
            return
        }

        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }

        _isSleepTimerActive.value = true
        sleepTimerJob = scope.launch {
            var remaining = minutes * 60
            while (remaining > 0 && isActive) {
                _sleepTimerRemainingSeconds.value = remaining
                // Gentle audio fade-out during final 4 seconds
                if (remaining <= 4 && player.isPlaying) {
                    val factor = (remaining.toFloat() / 5f).coerceIn(0.1f, 1f)
                    player.volume = factor
                }
                delay(1000)
                remaining--
            }
            if (isActive) {
                _sleepTimerRemainingSeconds.value = null
                _isSleepTimerActive.value = false
                pause()
                player.volume = 1.0f
            }
        }
    }

    fun addMinutesToSleepTimer(extraMinutes: Int) {
        if (_stopAtEndOfTrack.value) {
            setSleepTimer(extraMinutes, false)
            return
        }
        val currentRemaining = _sleepTimerRemainingSeconds.value ?: 0
        val newTotalMinutes = ((currentRemaining + (extraMinutes * 60)) / 60).coerceAtLeast(1)
        setSleepTimer(newTotalMinutes, false)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _stopAtEndOfTrack.value = false
        _isSleepTimerActive.value = false
        _sleepTimerRemainingSeconds.value = null
        player.volume = 1.0f
    }

    companion object {
        const val MIN_PLAYED_SECONDS_THRESHOLD = 20L

        @Volatile
        private var instance: AudioController? = null

        fun getInstance(context: Context): AudioController {
            return instance ?: synchronized(this) {
                instance ?: AudioController(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Maintain at most the last 20 played song IDs in playback sequence.
         * Consecutive duplicate plays (e.g. Loop One or immediate repeat) are not duplicated.
         */
        fun addToRecentPlayedHistory(songId: Int, currentHistory: List<Int>): List<Int> {
            if (currentHistory.lastOrNull() == songId) {
                return currentHistory
            }
            val updated = currentHistory + songId
            return if (updated.size > 20) {
                updated.takeLast(20)
            } else {
                updated
            }
        }

        /**
         * Automatic candidate selection for Shuffle = ON, Loop = ALL:
         * 1. Existing shuffle logic selects candidate.
         * 2. Candidate is checked against the last 20 actually played songs.
         * 3. If in recent 20, candidate is rejected and an eligible alternative is selected.
         * 4. If all candidates are in recent 20 (small libraries), fallback gracefully to avoid infinite loops.
         */
        fun selectShuffleCandidate(
            candidates: List<Song>,
            recentHistory: List<Int>
        ): Song {
            val candidate = candidates.random()
            return if (candidate.id in recentHistory) {
                val eligibleAlternatives = candidates.filter { it.id !in recentHistory }
                if (eligibleAlternatives.isNotEmpty()) {
                    // Reject candidate and select an eligible alternative
                    eligibleAlternatives.random()
                } else {
                    // Graceful fallback for small library where all candidates exist in recent history
                    candidate
                }
            } else {
                // Accept candidate
                candidate
            }
        }

        fun isOfflineBackupContext(context: PlaybackContext?): Boolean {
            if (context == null) return false
            return context.name.equals("Offline Backup", ignoreCase = true) ||
                context.uri.startsWith("alaktra:playlist:offline_backup")
        }

        fun isRestoredContext(context: PlaybackContext?): Boolean {
            if (context == null) return false
            return context.name.equals("Last Session", ignoreCase = true) ||
                context.uri.startsWith("alaktra:restored")
        }

        /**
         * When offline and skipping forward over uncached tracks in a shuffled queue,
         * relocate the next playable track into [desiredSlotIndex] rather than jumping
         * contextIndex past the uncached tracks. That way, when internet returns, none
         * of the unplayed random tracks are lost or skipped over.
         */
        fun relocateOfflinePlayableInShuffleOrder(
            state: PlaybackState,
            desiredSlotIndex: Int,
            playableIndex: Int
        ): PlaybackState {
            val order = state.contextOrder
            if (playableIndex !in order.indices) return state
            if (!state.shuffle) {
                return state.copy(contextIndex = playableIndex)
            }
            val clampedDesired = desiredSlotIndex.coerceIn(0, order.size - 1)
            return if (playableIndex > clampedDesired) {
                val mutable = order.toMutableList()
                val playableTrack = mutable.removeAt(playableIndex)
                mutable.add(clampedDesired, playableTrack)
                state.copy(
                    contextOrder = mutable,
                    contextIndex = clampedDesired
                )
            } else {
                state.copy(contextIndex = playableIndex)
            }
        }

        fun ensureContextBufferStatic(state: PlaybackState): PlaybackState {
            val contextTracks = state.context?.tracks ?: state.contextOrder
            if (contextTracks.isEmpty()) return state

            if (state.repeat != RepeatMode.CONTEXT) {
                return state
            }

            var order = state.contextOrder.toMutableList()
            var cIndex = state.contextIndex.coerceAtLeast(0)

            if (order.isEmpty()) {
                val seed = if (state.shuffle) {
                    ShuffleUtils.artistSpreadShuffle(contextTracks)
                } else {
                    contextTracks
                }
                order.addAll(seed)
                cIndex = 0
            }

            val targetUpcoming = 30
            var upcoming = (order.size - 1) - cIndex

            while (upcoming < targetUpcoming) {
                if (state.shuffle) {
                    val lastId = order.lastOrNull()?.id
                    val candidates = if (contextTracks.size > 1 && lastId != null) {
                        val filtered = contextTracks.filter { it.id != lastId }
                        if (filtered.isNotEmpty()) filtered else contextTracks
                    } else {
                        contextTracks
                    }
                    val selectedSong = selectShuffleCandidate(candidates, state.recentPlayedSongIds)
                    order.add(selectedSong)
                    upcoming += 1
                } else {
                    order.addAll(contextTracks)
                    upcoming += contextTracks.size
                }
            }

            if (cIndex > 40) {
                val trimCount = cIndex - 10
                order = order.drop(trimCount).toMutableList()
                cIndex = 10
            }

            return state.copy(
                contextOrder = order,
                contextIndex = cIndex
            )
        }

        /**
         * Restores playback context and queue order when reconnecting to the internet
         * after an automatic Offline Backup fallback:
         * - If shuffle is ON and was not turned off by the user, restores the exact pre-offline
         *   random queue anchored seamlessly around [currentState.currentSong].
         * - If shuffle was turned ON by the user while offline, shuffles the restored context.
         * - If shuffle was turned OFF by the user (offline or online), restores natural sequential order.
         */
        fun restoreStateFromPreOfflineSnapshot(
            currentState: PlaybackState,
            snapshot: PreOfflineSnapshot,
            freshCatalogSongs: List<Song> = emptyList()
        ): PlaybackState {
            val targetContext = if (isRestoredContext(snapshot.context) && freshCatalogSongs.isNotEmpty()) {
                PlaybackContext(
                    uri = "alaktra:playlist:alaktra_stream",
                    type = ContextType.PLAYLIST,
                    name = "Alaktra Stream",
                    trackIds = freshCatalogSongs.map { it.id },
                    tracks = freshCatalogSongs
                )
            } else if (snapshot.context.uri == "alaktra:playlist:alaktra_stream" && freshCatalogSongs.isNotEmpty()) {
                snapshot.context.copy(
                    trackIds = freshCatalogSongs.map { it.id },
                    tracks = freshCatalogSongs
                )
            } else {
                snapshot.context
            }

            val naturalTracks = targetContext.tracks.ifEmpty { snapshot.contextOrder }
            val currSong = currentState.currentSong

            val (restoredOrder, restoredIndex) = if (currentState.shuffle) {
                if (snapshot.preserveExactRandomOrder && snapshot.contextOrder.isNotEmpty()) {
                    val naturalById = naturalTracks.associateBy { it.id }
                    val refreshedSavedOrder = snapshot.contextOrder.map { naturalById[it.id] ?: it }
                    val existingIds = refreshedSavedOrder.mapTo(HashSet()) { it.id }
                    val missingTracks = naturalTracks.filter { it.id !in existingIds }
                    val fullRandomOrder = if (missingTracks.isNotEmpty()) {
                        refreshedSavedOrder + ShuffleUtils.artistSpreadShuffle(missingTracks)
                    } else {
                        refreshedSavedOrder
                    }

                    if (currSong == null) {
                        val idx = snapshot.resumeIndex.coerceIn(0, (fullRandomOrder.size - 1).coerceAtLeast(0))
                        fullRandomOrder to idx
                    } else if (snapshot.resumeIndex in fullRandomOrder.indices &&
                        fullRandomOrder[snapshot.resumeIndex].id == currSong.id
                    ) {
                        fullRandomOrder to snapshot.resumeIndex
                    } else {
                        val clampedResume = snapshot.resumeIndex.coerceIn(0, fullRandomOrder.size)
                        val before = fullRandomOrder.take(clampedResume).filter { it.id != currSong.id }
                        val after = fullRandomOrder.drop(clampedResume).filter { it.id != currSong.id }
                        val effectiveAfter = if (after.isEmpty() && naturalTracks.size > 1) {
                            ShuffleUtils.artistSpreadShuffle(naturalTracks.filter { it.id != currSong.id })
                        } else {
                            after
                        }
                        (before + listOf(currSong) + effectiveAfter) to before.size
                    }
                } else {
                    if (currSong != null && naturalTracks.isNotEmpty()) {
                        val others = naturalTracks.filter { it.id != currSong.id }
                        (listOf(currSong) + ShuffleUtils.artistSpreadShuffle(others)) to 0
                    } else {
                        ShuffleUtils.artistSpreadShuffle(naturalTracks) to 0
                    }
                }
            } else {
                if (currSong != null && naturalTracks.isNotEmpty()) {
                    val naturalIdx = naturalTracks.indexOfFirst { it.id == currSong.id }
                    if (naturalIdx != -1) {
                        naturalTracks to naturalIdx
                    } else {
                        (listOf(currSong) + naturalTracks) to 0
                    }
                } else {
                    val idx = snapshot.resumeIndex.coerceIn(0, (naturalTracks.size - 1).coerceAtLeast(0))
                    naturalTracks to idx
                }
            }

            val updated = currentState.copy(
                context = targetContext,
                contextOrder = restoredOrder,
                contextIndex = restoredIndex,
                autoplayTracks = emptyList()
            )
            return ensureContextBufferStatic(updated)
        }

        /**
         * Upgrades a Last Session or standalone Offline Backup state to the online Alaktra Stream
         * while strictly honoring [currentState.shuffle].
         */
        fun upgradeToStreamState(
            currentState: PlaybackState,
            songs: List<Song>
        ): PlaybackState {
            if (songs.isEmpty()) return currentState

            val streamCtx = PlaybackContext(
                uri = "alaktra:playlist:alaktra_stream",
                type = ContextType.PLAYLIST,
                name = "Alaktra Stream",
                trackIds = songs.map { it.id },
                tracks = songs
            )
            val currSong = currentState.currentSong

            val (newOrder, newIndex) = if (currentState.shuffle) {
                val songsById = songs.associateBy { it.id }
                val existingOrder = currentState.contextOrder
                val looksSequentialPrefix = songs.size >= 4 &&
                    existingOrder.size >= 4 &&
                    existingOrder.map { it.id } == songs.take(existingOrder.size).map { it.id }

                if (isRestoredContext(currentState.context) && existingOrder.size > 1 && !looksSequentialPrefix) {
                    val preserved = existingOrder.map { songsById[it.id] ?: it }
                    val preservedIds = preserved.mapTo(HashSet()) { it.id }
                    val missing = songs.filter { it.id !in preservedIds }
                    val combined = if (missing.isNotEmpty()) {
                        preserved + ShuffleUtils.artistSpreadShuffle(missing)
                    } else {
                        preserved
                    }
                    if (currSong != null) {
                        val idx = combined.indexOfFirst { it.id == currSong.id }
                        if (idx != -1) {
                            combined to idx
                        } else {
                            (listOf(currSong) + combined) to 0
                        }
                    } else {
                        val idx = currentState.contextIndex.coerceIn(0, combined.size - 1)
                        combined to idx
                    }
                } else {
                    if (currSong != null) {
                        val others = songs.filter { it.id != currSong.id }
                        (listOf(currSong) + ShuffleUtils.artistSpreadShuffle(others)) to 0
                    } else {
                        ShuffleUtils.artistSpreadShuffle(songs) to 0
                    }
                }
            } else {
                if (currSong != null) {
                    val idx = songs.indexOfFirst { it.id == currSong.id }
                    if (idx != -1) {
                        songs to idx
                    } else {
                        (listOf(currSong) + songs) to 0
                    }
                } else {
                    songs to 0
                }
            }

            val updated = currentState.copy(
                context = streamCtx,
                contextOrder = newOrder,
                contextIndex = newIndex,
                autoplayTracks = emptyList()
            )
            return ensureContextBufferStatic(updated)
        }

        /**
         * Updates Nearby Pins queue while preserving active shuffle order when shuffle is enabled.
         */
        fun computeUpdatedNearbyPinsState(
            state: PlaybackState,
            newSongs: List<Song>
        ): PlaybackState {
            val currentSong = state.currentSong ?: return state
            val upcomingCandidates = newSongs.filter { it.id != currentSong.id }
            val naturalTracks = if (newSongs.any { it.id == currentSong.id }) {
                newSongs
            } else {
                listOf(currentSong) + upcomingCandidates
            }

            val newOrder = if (state.shuffle) {
                val candidateById = upcomingCandidates.associateBy { it.id }
                val preservedUpcoming = state.contextOrder
                    .drop((state.contextIndex + 1).coerceAtLeast(0))
                    .mapNotNull { candidateById[it.id] }
                    .distinctBy { it.id }
                val preservedIds = preservedUpcoming.mapTo(HashSet()) { it.id }
                val newlyAdded = upcomingCandidates.filter { it.id !in preservedIds }
                val shuffledNew = ShuffleUtils.artistSpreadShuffle(newlyAdded)
                listOf(currentSong) + preservedUpcoming + shuffledNew
            } else {
                listOf(currentSong) + upcomingCandidates
            }

            val updatedContext = state.context?.copy(
                trackIds = naturalTracks.map { it.id },
                tracks = naturalTracks
            )

            return state.copy(
                context = updatedContext,
                contextOrder = newOrder,
                contextIndex = 0
            )
        }
    }
}
