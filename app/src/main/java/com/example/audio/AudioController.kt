package com.example.audio
import kotlin.math.abs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.example.data.repository.MusicRepository
import com.example.model.Song
import com.example.util.NetworkMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    private val _stablePlaybackState = MutableStateFlow(PlaybackState())
    val stablePlaybackState: StateFlow<PlaybackState> = _stablePlaybackState.asStateFlow()

    private fun publishState(newState: PlaybackState, stableChanged: Boolean = true) {
        _playbackState.value = newState
        if (stableChanged) {
            _stablePlaybackState.value = if (newState.currentPositionMs == 0L) {
                newState
            } else {
                newState.copy(currentPositionMs = 0L)
            }
        }
    }

    val recentPlayedSongIds: List<Int> get() = _playbackState.value.recentPlayedSongIds

    private var progressJob: Job? = null
    private var paletteJob: Job? = null
    private var homeQueueHydrationJob: Job? = null
    private val authPrefs by lazy { com.example.data.preferences.AuthPreferences(context) }
    @Volatile
    private var lastPersistedOrderIdentity: Any? = null
    @Volatile
    private var lastPersistedContextIdentity: Any? = null
    @Volatile
    private var lastPersistedRecentIdentity: Any? = null
    @Volatile
    private var paletteResolvedFromArtSongId: Int? = null
    private val resolvedArtColorCache = java.util.concurrent.ConcurrentHashMap<Int, Color>()

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
    private var offlineBackupJob: Job? = null
    private var restoreOnlineJob: Job? = null
    private var lastTransientRetrySongId: Int? = null
    @Volatile
    private var consecutiveOfflineErrors: Int = 0
    @Volatile
    private var offlineBufferingSinceMs: Long = 0L
    @Volatile
    private var lastRefreshedFullyCachedSongId: Int? = null
    @Volatile
    private var pendingSeekTargetMs: Long = -1L
    @Volatile
    private var pendingSeekDeadlineMs: Long = 0L
    @Volatile
    private var durationLookupJob: Job? = null
    @Volatile
    private var lastDurationPersistedSongId: Int? = null
    private val failedOfflineSongIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    init {
        AudioCache.warmUpCacheIndexAsync(context)
        restoreSavedStateAsync()
        observeNetworkState()
    }

    private fun clearPreOfflineSnapshotAndHint() {
        preOfflineSnapshot = null
        scope.launch(Dispatchers.IO) {
            try {
                com.example.data.preferences.AuthPreferences(context).clearPreOfflineHint()
            } catch (_: Exception) {}
        }
    }

    private fun savePreOfflineSnapshotArtifacts(snapshot: PreOfflineSnapshot) {
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = com.example.data.preferences.AuthPreferences(context)
                if (snapshot.contextOrder.isNotEmpty()) {
                    prefs.saveQueue(snapshot.contextOrder)
                }
                if (!isRestoredContext(snapshot.context) && snapshot.context.tracks.isNotEmpty()) {
                    prefs.savePlaybackContext(
                        uri = snapshot.context.uri,
                        typeName = snapshot.context.type.name,
                        name = snapshot.context.name,
                        tracks = snapshot.context.tracks
                    )
                }
                prefs.savePreOfflineHint(
                    uri = snapshot.context.uri,
                    resumeIndex = snapshot.resumeIndex,
                    preserveExactRandomOrder = snapshot.preserveExactRandomOrder
                )
            } catch (_: Exception) {}
        }
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

        val existing = preOfflineSnapshot
        val preserveOrder = if (existing != null && existing.context.uri == currentContext.uri && state.shuffle) {
            existing.preserveExactRandomOrder || state.shuffle
        } else {
            state.shuffle
        }

        val snapshot = PreOfflineSnapshot(
            context = currentContext,
            contextOrder = state.contextOrder,
            resumeIndex = targetResumeIndex,
            preserveExactRandomOrder = preserveOrder
        )
        preOfflineSnapshot = snapshot
        savePreOfflineSnapshotArtifacts(snapshot)
    }

    private fun observeNetworkState() {
        scope.launch {
            NetworkMonitor.observeNetwork(context)
                .distinctUntilChanged()
                .collect { isOnline ->
                    val state = _playbackState.value
                    val currentSong = state.currentSong
                    val currentContext = state.context

                    if (!isOnline) {
                        restoreOnlineJob?.cancel()
                        restoreOnlineJob = null
                        offlineBufferingSinceMs = 0L
                        if (currentContext != null && !isOfflineBackupContext(currentContext)) {
                            capturePreOfflineSnapshotIfNeeded(resumeFromNext = false)
                        }
                        if (state.isPlaying && currentSong != null && !isSongPlayableOffline(currentSong)) {
                            val hasBufferedAudio = try {
                                (player.bufferedPosition > player.currentPosition + 500L) ||
                                    AudioCache.hasCachedAudioBytes(context, currentSong.id)
                            } catch (_: Exception) {
                                AudioCache.hasCachedAudioBytes(context, currentSong.id)
                            }
                            if (!hasBufferedAudio) {
                                // No cached/buffered tail for current track: smoothly hand off to next playable offline song
                                handleOfflineFallback(autoShuffle = state.shuffle, resumeFromNext = false)
                            }
                        }
                    } else {
                        val backupWasInFlight = offlineBackupJob?.isActive == true
                        offlineBackupJob?.cancel()
                        offlineBackupJob = null
                        offlineBufferingSinceMs = 0L
                        failedOfflineSongIds.clear()
                        consecutiveOfflineErrors = 0

                        // If current track hit a partial-cache EOF while offline and we reconnected mid-playout,
                        // seamlessly resume full online streaming from the current playback position.
                        if (currentSong != null && AudioCache.consumeEarlyOfflineTruncation(currentSong.id)) {
                            try {
                                val resumePos = player.currentPosition.coerceAtLeast(state.currentPositionMs).coerceAtLeast(0L)
                                val wasPlaying = player.playWhenReady || state.isPlaying
                                val uri = if (currentSong.localPath != null && File(currentSong.localPath).exists()) {
                                    Uri.fromFile(File(currentSong.localPath))
                                } else {
                                    Uri.parse(currentSong.audioUrl)
                                }
                                val mediaMetadata = MediaMetadata.Builder()
                                    .setTitle(currentSong.title)
                                    .setArtist(currentSong.artist)
                                    .setArtworkUri(Uri.parse(currentSong.coverUrl))
                                    .build()
                                val mediaItem = MediaItem.Builder()
                                    .setUri(uri)
                                    .setMediaId(currentSong.id.toString())
                                    .setCustomCacheKey("song:${currentSong.id}")
                                    .setMediaMetadata(mediaMetadata)
                                    .build()
                                AudioCache.cancelAllActivePreloads()
                                player.setMediaItem(mediaItem)
                                player.prepare()
                                if (resumePos > 0L) {
                                    player.seekTo(resumePos)
                                }
                                if (wasPlaying) {
                                    player.play()
                                }
                            } catch (_: Exception) {}
                        }

                        if (isOfflineBackupContext(currentContext) || isRestoredContext(currentContext)) {
                            restoreOnlineContextOrStream()
                        } else if (preOfflineSnapshot != null) {
                            val playerIdleOrDead = try {
                                player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE
                            } catch (_: Exception) {
                                true
                            }
                            if (playerIdleOrDead && (currentSong != null || backupWasInFlight)) {
                                restoreOnlineContextOrStream()
                            } else if (!backupWasInFlight) {
                                clearPreOfflineSnapshotAndHint()
                            }
                        }
                        ensureCurrentSongPalette()
                    }
                }
        }
    }

    private fun restoreOnlineContextOrStream() {
        val snapshotToRestore = preOfflineSnapshot
        restoreOnlineJob?.cancel()
        restoreOnlineJob = scope.launch(Dispatchers.IO) {
            val prefs = com.example.data.preferences.AuthPreferences(context)
            val effectiveSnapshot = snapshotToRestore ?: run {
                val hint = prefs.getPreOfflineHint()
                val savedCtx = prefs.getSavedPlaybackContext()
                val savedQueue = prefs.getSavedQueue()
                if (savedCtx != null && (hint == null || hint.uri == savedCtx.uri)) {
                    val ctxType = try { ContextType.valueOf(savedCtx.typeName) } catch (_: Exception) { ContextType.PLAYLIST }
                    val ctx = PlaybackContext(
                        uri = savedCtx.uri,
                        type = ctxType,
                        name = savedCtx.name,
                        trackIds = savedCtx.tracks.map { it.id },
                        tracks = savedCtx.tracks
                    )
                    val order = if (savedQueue.isNotEmpty()) savedQueue else savedCtx.tracks
                    PreOfflineSnapshot(
                        context = ctx,
                        contextOrder = order,
                        resumeIndex = (hint?.resumeIndex ?: 0).coerceIn(0, order.size),
                        preserveExactRandomOrder = hint?.preserveExactRandomOrder ?: _playbackState.value.shuffle
                    )
                } else {
                    null
                }
            }

            val repo = MusicRepository.getInstance(context)
            val needsCatalogSongs = effectiveSnapshot == null ||
                isRestoredContext(effectiveSnapshot.context) ||
                effectiveSnapshot.context.uri == "alaktra:playlist:alaktra_stream"
            val catalogSongs = if (needsCatalogSongs) {
                try {
                    repo.getCachedSongs().ifEmpty { repo.getSongs(forceRefresh = false) }
                } catch (_: Exception) {
                    try { repo.getCachedSongs() } catch (_: Exception) { emptyList() }
                }
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (!NetworkMonitor.isOnline(context)) return@withContext
                val currentState = _playbackState.value
                val currentContext = currentState.context
                val activeSnapshot = preOfflineSnapshot ?: effectiveSnapshot

                if ((isOfflineBackupContext(currentContext) || preOfflineSnapshot != null) && activeSnapshot != null) {
                    val restoredState = restoreStateFromPreOfflineSnapshot(
                        currentState = currentState,
                        snapshot = activeSnapshot,
                        freshCatalogSongs = catalogSongs
                    )
                    clearPreOfflineSnapshotAndHint()
                    publishState(restoredState)
                    checkNearbyPinsLocationTracking(restoredState.context?.name)
                    persistCurrentState()
                    val playerDead = try {
                        player.currentMediaItem == null
                    } catch (_: Exception) {
                        false
                    }
                    if (playerDead && restoredState.currentSong != null && currentState.isPlaying) {
                        playSongAtIndex(restoredState.contextIndex)
                    }
                    ensureCurrentSongPalette()
                } else if (isOfflineBackupContext(currentContext) || isRestoredContext(currentContext)) {
                    if (catalogSongs.isNotEmpty()) {
                        val upgradedState = upgradeToStreamState(currentState, catalogSongs)
                        clearPreOfflineSnapshotAndHint()
                        publishState(upgradedState)
                        persistCurrentState()
                        ensureCurrentSongPalette()
                    }
                }
            }
        }
    }

    private fun restoreSavedStateAsync() {
        scope.launch(Dispatchers.IO) {
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

                // Warm AudioCache + download index while already on IO thread
                AudioCache.getFullyCachedSongIds(context)

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
                    val localFileExists = targetSong.localPath != null && File(targetSong.localPath).exists()

                    withContext(Dispatchers.Main) {
                        // Do not clobber active playback if user already tapped play during cold start
                        if (_playbackState.value.currentSong != null || _playbackState.value.isPlaying) {
                            return@withContext
                        }
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
                        publishState(restoredState)
                        extractPalette(targetSong.coverUrl, targetSong.dominantColorHex, targetSong.id)

                        try {
                            val uri = if (localFileExists && targetSong.localPath != null) {
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
                }
            } catch (e: Exception) {
                Log.w("AudioController", "Failed to restore saved playback state", e)
            }
        }
    }

    private fun persistCurrentState(
        positionOnly: Boolean = false,
        forceFull: Boolean = false
    ) {
        val state = _playbackState.value
        val hasSnapshot = preOfflineSnapshot != null
        val ctx = state.context
        val order = state.contextOrder
        val recentIds = state.recentPlayedSongIds
        val song = state.currentSong
        val posMs = state.currentPositionMs
        val shuffle = state.shuffle
        val repeatName = state.repeat.name

        val saveOrder = !positionOnly && (forceFull || order !== lastPersistedOrderIdentity)
        val saveCtx = !positionOnly && (forceFull || ctx !== lastPersistedContextIdentity)
        val saveRecent = !positionOnly && recentIds.isNotEmpty() && (forceFull || recentIds !== lastPersistedRecentIdentity)
        if (saveOrder) lastPersistedOrderIdentity = order
        if (saveCtx) lastPersistedContextIdentity = ctx
        if (saveRecent) lastPersistedRecentIdentity = recentIds

        scope.launch(Dispatchers.IO) {
            try {
                authPrefs.savePlaybackState(
                    song = song,
                    positionMs = posMs,
                    shuffle = shuffle,
                    repeatModeName = repeatName
                )
                if (!positionOnly) {
                    if (!isOfflineBackupContext(ctx)) {
                        if (saveOrder && order.isNotEmpty()) {
                            authPrefs.saveQueue(order)
                        }
                        if (saveCtx && ctx != null && !isRestoredContext(ctx) && ctx.tracks.isNotEmpty()) {
                            authPrefs.savePlaybackContext(
                                uri = ctx.uri,
                                typeName = ctx.type.name,
                                name = ctx.name,
                                tracks = ctx.tracks
                            )
                        }
                        if (!hasSnapshot) {
                            authPrefs.clearPreOfflineHint()
                        }
                    } else if (saveOrder && !hasSnapshot && authPrefs.getSavedPlaybackContext() == null && order.isNotEmpty()) {
                        authPrefs.saveQueue(order)
                    }
                    if (saveRecent) {
                        authPrefs.saveRecentPlayedSongIds(recentIds)
                    }
                }
            } catch (_: Exception) {}
        }
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
                60_000,   // minBufferMs: 60s hysteresis floor
                600_000,  // maxBufferMs: 10 minutes so full songs stream smoothly in one continuous pass
                2_000,    // bufferForPlaybackMs: 2s start cushion prevents immediate stutter/rebuffer
                3_500     // bufferForPlaybackAfterRebufferMs: prevent stop-start micro-oscillation
            )
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(3) {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                return if (!NetworkMonitor.isOnline(context)) {
                    C.TIME_UNSET
                } else {
                    super.getRetryDelayMsFor(loadErrorInfo)
                }
            }

            override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                return if (!NetworkMonitor.isOnline(context)) {
                    0
                } else {
                    super.getMinimumLoadableRetryCount(dataType)
                }
            }
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(
            AudioCache.createCacheDataSourceFactory(context),
            extractorsFactory
        ).setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val exo = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (!isLoading) {
                    val track = _playbackState.value.currentSong ?: return
                    val knownDur = exo.duration
                    val fullyBuffered = knownDur != C.TIME_UNSET && knownDur > 0L && exo.bufferedPosition >= knownDur - 1500L
                    if (fullyBuffered && lastRefreshedFullyCachedSongId != track.id) {
                        lastRefreshedFullyCachedSongId = track.id
                        AudioCache.refreshSongCachedStatusAsync(context, track.id, track.duration)
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                syncPlaybackStatus()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                    consecutiveOfflineErrors = 0
                    offlineBufferingSinceMs = 0L
                    failedOfflineSongIds.clear()
                    equalizerManager.attachToCurrentSession()
                }
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                    offlineBufferingSinceMs = 0L
                    AudioCache.clearEarlyOfflineTruncation()
                    _playbackState.value.currentSong?.let { endedSong ->
                        lastRefreshedFullyCachedSongId = endedSong.id
                        AudioCache.refreshSongCachedStatusAsync(context, endedSong.id, endedSong.duration)
                    }
                    handleTrackEnded()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val rawPos = exo.currentPosition.coerceAtLeast(0L)
                val effectivePos = resolveEffectivePositionMs(rawPos)
                val resolvedDur = resolvePlayerDurationMs(_playbackState.value.currentSong, exo)
                val prev = _playbackState.value
                val nextDur = if (resolvedDur > 0L) resolvedDur else prev.durationMs
                publishState(
                    prev.copy(
                        currentPositionMs = effectivePos,
                        durationMs = nextDur
                    ),
                    stableChanged = (prev.durationMs != nextDur)
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("AudioController", "Playback error: ${error.errorCodeName} (${error.errorCode}): ${error.message}")
                val state = _playbackState.value
                val failedSong = state.currentSong
                val cause = error.cause
                val isRemoteSong = failedSong == null || (!failedSong.audioUrl.startsWith("file://") && failedSong.localPath.isNullOrBlank())
                if (isRemoteSong && NetworkMonitor.hasNetworkTransport(context)) {
                    if (failedSong == null || NetworkMonitor.matchesActiveServer(failedSong.audioUrl)) {
                        val targetUrl = failedSong?.audioUrl ?: ""
                        when {
                            cause is HttpDataSource.InvalidResponseCodeException -> {
                                if (NetworkMonitor.isUnreachableHttpStatus(cause.responseCode)) {
                                    NetworkMonitor.reportSuspectedServerFailure(
                                        context = context,
                                        serverUrl = targetUrl,
                                        error = cause,
                                        httpCode = cause.responseCode
                                    )
                                }
                            }
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                                NetworkMonitor.reportSuspectedServerFailure(
                                    context = context,
                                    serverUrl = targetUrl,
                                    error = cause,
                                    verifyFirst = false
                                )
                            }
                            cause is HttpDataSource.HttpDataSourceException ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                                NetworkMonitor.reportSuspectedServerFailure(
                                    context = context,
                                    serverUrl = targetUrl,
                                    error = cause,
                                    verifyFirst = true
                                )
                            }
                        }
                    }
                }
                val isOnline = NetworkMonitor.isOnline(context)

                if (!isOnline) {
                    val playedPos = try {
                        exo.currentPosition.coerceAtLeast(state.currentPositionMs)
                    } catch (_: Exception) {
                        state.currentPositionMs
                    }
                    offlineBufferingSinceMs = 0L
                    AudioCache.clearEarlyOfflineTruncation()
                    exo.stop()
                    exo.clearMediaItems()
                    failedSong?.id?.let { failedOfflineSongIds.add(it) }
                    consecutiveOfflineErrors++
                    if (consecutiveOfflineErrors <= 5) {
                        handleOfflineFallback(autoShuffle = state.shuffle, resumeFromNext = (playedPos > 5_000L))
                        return
                    }
                    consecutiveOfflineErrors = 0
                    publishState(_playbackState.value.copy(isPlaying = false))
                    stopProgressTracker()
                    scope.launch {
                        Toast.makeText(context, "Offline tracks are currently unavailable", Toast.LENGTH_SHORT).show()
                    }
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
                        AudioCache.cancelAllActivePreloads()
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
                publishState(_playbackState.value.copy(isPlaying = false))
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

    private var currentCapsuleSessionStartMs: Long = 0L
    private var currentCapsuleMsPlayed: Long = 0L
    private var lastCapsulePersistedMs: Long = 0L
    private var lastCapsuleTickMs: Long = 0L
    private val soundCapsuleRepo by lazy { MusicRepository.getInstance(context).soundCapsuleRepository }

    private fun persistCurrentCapsuleSession(
        syncToServer: Boolean = false,
        finalizeSession: Boolean = false
    ) {
        val song = currentSessionSong ?: _playbackState.value.currentSong ?: return
        val startedAt = currentCapsuleSessionStartMs
        val msPlayed = currentCapsuleMsPlayed
        if (startedAt > 0L && msPlayed > 0L && (finalizeSession || syncToServer || msPlayed > lastCapsulePersistedMs)) {
            lastCapsulePersistedMs = msPlayed
            scope.launch(Dispatchers.IO) {
                try {
                    soundCapsuleRepo.recordSessionPlayEvent(
                        song = song,
                        startedAtMillis = startedAt,
                        msPlayed = msPlayed,
                        syncToServer = syncToServer,
                        sessionCompleted = finalizeSession
                    )
                } catch (e: Exception) {
                    Log.w("AudioController", "Failed to persist Sound Capsule PlayEvent", e)
                }
            }
        }
        if (finalizeSession) {
            val now = System.currentTimeMillis()
            currentCapsuleSessionStartMs = 0L
            currentCapsuleMsPlayed = 0L
            lastCapsulePersistedMs = 0L
            lastCapsuleTickMs = now
        }
    }

    /**
     * Synchronously flushes any active or paused track's up-to-the-millisecond listen duration
     * into SoundCapsuleRepository's in-memory accumulator so opening Sound Capsule displays
     * exact current stats on its very first frame without main-thread disk I/O.
     */
    fun flushLiveSessionForUi() {
        val song = currentSessionSong ?: _playbackState.value.currentSong ?: return
        if (player.isPlaying && currentCapsuleSessionStartMs > 0L) {
            val now = System.currentTimeMillis()
            val delta = (now - lastCapsuleTickMs).coerceIn(0L, 2_000L)
            if (delta > 0L) {
                lastCapsuleTickMs = now
                currentCapsuleMsPlayed += delta
            }
        }
        checkAndRecordIfPlayedThresholdMet()
        val sessionId = currentSessionHistoryId
        val listenedSec = currentSessionListenedSec
        if (sessionId != null && listenedSec > 0L) {
            scope.launch(Dispatchers.IO) {
                try {
                    com.example.data.local.AppDatabase.getInstance(context)
                        .updateListeningDuration(sessionId, listenedSec)
                } catch (_: Exception) {}
            }
        }
        val startedAt = currentCapsuleSessionStartMs
        val msPlayed = currentCapsuleMsPlayed
        if (startedAt > 0L && msPlayed > 0L) {
            soundCapsuleRepo.applyLiveSessionInMemory(
                song = song,
                startedAtMillis = startedAt,
                msPlayed = msPlayed
            )
            if (msPlayed - lastCapsulePersistedMs >= 2_000L) {
                persistCurrentCapsuleSession(syncToServer = false, finalizeSession = false)
            }
        }
    }

    private fun checkAndRecordIfPlayedThresholdMet() {
        if (currentSongRecordedAsPlayed) return
        val song = currentSessionSong ?: _playbackState.value.currentSong ?: return
        // Counted as played ONLY and ONLY if the song has been actively played for at least 20 seconds
        val hasMetThreshold = currentSessionListenedSec >= MIN_PLAYED_SECONDS_THRESHOLD

        if (hasMetThreshold) {
            recordSongAsPlayed(song, currentSessionListenedSec)
        }
    }

    private fun resolveEffectivePositionMs(rawPlayerPosMs: Long): Long {
        val pending = pendingSeekTargetMs
        if (pending >= 0L) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val settled = abs(rawPlayerPosMs - pending) <= 1_500L && player.playbackState == Player.STATE_READY
            if (settled || nowElapsed >= pendingSeekDeadlineMs) {
                pendingSeekTargetMs = -1L
                pendingSeekDeadlineMs = 0L
                return rawPlayerPosMs
            }
            return pending
        }
        return rawPlayerPosMs
    }

    private fun resolvePlayerDurationMs(song: Song?, p: Player): Long {
        val directDur = p.duration
        if (directDur != C.TIME_UNSET && directDur > 0L) {
            return directDur
        }
        val contentDur = p.contentDuration
        if (contentDur != C.TIME_UNSET && contentDur > 0L) {
            return contentDur
        }
        val metaDur = p.mediaMetadata.durationMs
        if (metaDur != null && metaDur > 0L) {
            return metaDur
        }
        var detectedBitrate = 0
        try {
            val groups = p.currentTracks.groups
            for (g in groups) {
                for (i in 0 until g.length) {
                    val fmt = g.getTrackFormat(i)
                    if (fmt.bitrate > 0 && detectedBitrate <= 0) {
                        detectedBitrate = fmt.bitrate
                    }
                    val meta = fmt.metadata ?: continue
                    for (j in 0 until meta.length()) {
                        val entry = meta.get(j)
                        if (entry is TextInformationFrame && entry.id.equals("TLEN", ignoreCase = true)) {
                            val parsedMs = entry.values.firstOrNull()?.trim()?.toLongOrNull()
                            if (parsedMs != null && parsedMs > 0L) {
                                return parsedMs
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val songDurMs = ((song?.duration ?: 0.0) * 1000.0).toLong()
        if (songDurMs > 0L) {
            return songDurMs
        }

        if (song != null && detectedBitrate > 0) {
            val contentLength = AudioCache.getCachedContentLengthFast(song.id)
            if (contentLength > 0L) {
                val estimatedMs = (contentLength * 8_000L) / detectedBitrate.toLong()
                if (estimatedMs in 1_000L..7_200_000L) {
                    return estimatedMs
                }
            }
        }
        return 0L
    }

    private fun updateResolvedDurationInStateAndDb(songId: Int, durationMs: Long) {
        if (songId <= 0 || durationMs <= 0L) return
        val durationSec = durationMs / 1000.0
        val prev = _playbackState.value
        val curSong = prev.currentSong
        val shouldUpdateSong = curSong != null && curSong.id == songId && curSong.duration <= 0.0
        val nextSong = if (shouldUpdateSong) curSong!!.copy(duration = durationSec) else curSong
        val nextOrder = if (shouldUpdateSong && prev.contextOrder.any { it.id == songId && it.duration <= 0.0 }) {
            prev.contextOrder.map { if (it.id == songId && it.duration <= 0.0) it.copy(duration = durationSec) else it }
        } else {
            prev.contextOrder
        }
        val nextContext = if (shouldUpdateSong && prev.context?.tracks?.any { it.id == songId && it.duration <= 0.0 } == true) {
            prev.context.copy(
                tracks = prev.context.tracks.map { if (it.id == songId && it.duration <= 0.0) it.copy(duration = durationSec) else it }
            )
        } else {
            prev.context
        }
        if (shouldUpdateSong || (curSong?.id == songId && prev.durationMs <= 0L)) {
            publishState(
                prev.copy(
                    currentSong = nextSong,
                    context = nextContext,
                    contextOrder = nextOrder,
                    durationMs = durationMs
                ),
                stableChanged = true
            )
        }
        if (lastDurationPersistedSongId != songId) {
            lastDurationPersistedSongId = songId
            scope.launch(Dispatchers.IO) {
                try {
                    com.example.data.local.AppDatabase.getInstance(context)
                        .songDao()
                        .updateSongDuration(songId, durationSec)
                } catch (_: Exception) {}
            }
        }
    }

    private fun resolveMissingDurationAsync(song: Song) {
        durationLookupJob?.cancel()
        if (song.duration > 0.0) return
        val targetId = song.id
        durationLookupJob = scope.launch(Dispatchers.IO) {
            try {
                val dao = com.example.data.local.AppDatabase.getInstance(context).songDao()
                val dbDurSec = dao.getDownloadedSong(targetId)?.duration?.takeIf { it > 0.0 }
                    ?: dao.getCachedSong(targetId)?.duration?.takeIf { it > 0.0 }
                if (dbDurSec != null) {
                    val durMs = (dbDurSec * 1000.0).toLong()
                    withContext(Dispatchers.Main) {
                        if (_playbackState.value.currentSong?.id == targetId) {
                            updateResolvedDurationInStateAndDb(targetId, durMs)
                        }
                    }
                    return@launch
                }

                val cacheLen = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                    AudioCache.getSimpleCache(context).getContentMetadata("song:$targetId")
                )
                if (cacheLen > 0L) {
                    AudioCache.updateCachedContentLength(targetId, cacheLen)
                }

                val localPath = song.localPath?.takeIf { File(it).exists() }
                    ?: File(File(context.filesDir, "downloads"), "$targetId.mp3").takeIf { it.exists() }?.absolutePath
                if (localPath != null) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(localPath)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val parsedMs = durStr?.toLongOrNull() ?: 0L
                        if (parsedMs > 0L) {
                            withContext(Dispatchers.Main) {
                                if (_playbackState.value.currentSong?.id == targetId) {
                                    updateResolvedDurationInStateAndDb(targetId, parsedMs)
                                }
                            }
                        }
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun syncPlaybackStatus() {
        val rawPos = player.currentPosition.coerceAtLeast(0L)
        val pos = resolveEffectivePositionMs(rawPos)
        val prev = _playbackState.value
        val dur = resolvePlayerDurationMs(prev.currentSong, player)
        val isEnded = player.playbackState == Player.STATE_ENDED

        val effectivePlaying = player.playWhenReady && !isEnded && player.playbackState != Player.STATE_IDLE
        val newDur = if (dur > 0L) dur else prev.durationMs
        val stableChanged = prev.isPlaying != effectivePlaying || prev.durationMs != newDur
        publishState(
            prev.copy(
                isPlaying = effectivePlaying,
                currentPositionMs = pos,
                durationMs = newDur
            ),
            stableChanged = stableChanged
        )
        val activeSong = prev.currentSong
        if (activeSong != null && activeSong.duration <= 0.0 && newDur > 0L) {
            updateResolvedDurationInStateAndDb(activeSong.id, newDur)
        }

        if (effectivePlaying) {
            startProgressTracker()
        } else {
            stopProgressTracker()
        }
    }

    private fun flushCurrentListeningSession() {
        persistCurrentCapsuleSession(syncToServer = false, finalizeSession = false)
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
        val startNow = System.currentTimeMillis()
        lastTickTimeMs = startNow
        lastCapsuleTickMs = startNow
        progressJob = scope.launch {
            val selfJob = coroutineContext[Job]
            try {
                var counter = 0
                while (isActive) {
                    val rawPos = player.currentPosition.coerceAtLeast(0L)
                    val pos = resolveEffectivePositionMs(rawPos)
                    val isActivelyPlaying = player.isPlaying

                    val prevState = _playbackState.value
                    val dur = resolvePlayerDurationMs(prevState.currentSong, player)
                    val nextIsPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
                    val nextDuration = if (dur > 0L) dur else prevState.durationMs
                    val stableChanged = prevState.isPlaying != nextIsPlaying || prevState.durationMs != nextDuration
                    if (stableChanged || pos != prevState.currentPositionMs) {
                        publishState(
                            prevState.copy(
                                isPlaying = nextIsPlaying,
                                currentPositionMs = pos,
                                durationMs = nextDuration
                            ),
                            stableChanged = stableChanged
                        )
                    }
                    val activeSong = prevState.currentSong
                    if (activeSong != null && activeSong.duration <= 0.0 && nextDuration > 0L) {
                        updateResolvedDurationInStateAndDb(activeSong.id, nextDuration)
                    }

                    val now = System.currentTimeMillis()
                    val currentTrack = prevState.currentSong

                    // Refresh reactive cache badge as soon as ExoPlayer finishes buffering current track to disk
                    if (dur > 0L && currentTrack != null && player.bufferedPosition >= dur - 1500L && lastRefreshedFullyCachedSongId != currentTrack.id) {
                        lastRefreshedFullyCachedSongId = currentTrack.id
                        AudioCache.refreshSongCachedStatusAsync(context, currentTrack.id, currentTrack.duration)
                    }

                    // Seamless offline transition when partial buffer / cached prefix finishes without stalling
                    val remainingBufferedMs = (player.bufferedPosition - pos).coerceAtLeast(0L)
                    val currentlyOnline = NetworkMonitor.isOnline(context)
                    if (!currentlyOnline && player.playWhenReady && currentTrack != null && !isSongPlayableOffline(currentTrack)) {
                        if (player.playbackState == Player.STATE_BUFFERING && remainingBufferedMs <= 1200L) {
                            if (offlineBufferingSinceMs == 0L) {
                                offlineBufferingSinceMs = now
                            } else if (now - offlineBufferingSinceMs >= 700L) {
                                offlineBufferingSinceMs = 0L
                                AudioCache.clearEarlyOfflineTruncation()
                                handleOfflineFallback(autoShuffle = prevState.shuffle, resumeFromNext = true)
                                break
                            }
                        } else {
                            offlineBufferingSinceMs = 0L
                        }
                    } else {
                        offlineBufferingSinceMs = 0L
                    }

                    if (isActivelyPlaying) {
                        if (currentCapsuleSessionStartMs <= 0L) {
                            val activeSong = currentSessionSong ?: _playbackState.value.currentSong
                            if (activeSong != null) {
                                currentSessionSong = activeSong
                                currentSessionSongId = activeSong.id
                                currentCapsuleSessionStartMs = now
                                currentCapsuleMsPlayed = 0L
                                lastCapsulePersistedMs = 0L
                                lastCapsuleTickMs = now
                            }
                        }
                        val capsuleDeltaMs = (now - lastCapsuleTickMs).coerceIn(0L, 2_000L)
                        lastCapsuleTickMs = now
                        if (capsuleDeltaMs > 0L) {
                            val prevMs = currentCapsuleMsPlayed
                            currentCapsuleMsPlayed += capsuleDeltaMs
                            val crossedPlayThreshold = prevMs < 30_000L && currentCapsuleMsPlayed >= 30_000L
                            val crossedMinuteMark = (prevMs / 15_000L) != (currentCapsuleMsPlayed / 15_000L)
                            if (crossedPlayThreshold || crossedMinuteMark) {
                                val activeSong = currentSessionSong ?: _playbackState.value.currentSong
                                if (activeSong != null && currentCapsuleSessionStartMs > 0L) {
                                    soundCapsuleRepo.applyLiveSessionInMemory(
                                        song = activeSong,
                                        startedAtMillis = currentCapsuleSessionStartMs,
                                        msPlayed = currentCapsuleMsPlayed
                                    )
                                }
                            }
                            if (currentCapsuleMsPlayed - lastCapsulePersistedMs >= 15_000L) {
                                persistCurrentCapsuleSession(syncToServer = false, finalizeSession = false)
                            }
                        }

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
                        // Continuously reset lastTickTimeMs & lastCapsuleTickMs so time spent paused or loading is NEVER credited!
                        lastTickTimeMs = now
                        lastCapsuleTickMs = now

                        // Check if player has truly paused or stopped
                        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                            publishState(_playbackState.value.copy(isPlaying = false))
                            break
                        }
                    }

                    counter++
                    if (counter >= 40) { // Every ~10 seconds (lightweight position save only)
                        counter = 0
                        persistCurrentState(positionOnly = true)
                    }
                    delay(250)
                }
            } catch (e: CancellationException) {
                // Expected on cancellation
            } catch (e: Exception) {
                Log.e("AudioController", "Exception in progress tracker loop", e)
            } finally {
                if (progressJob === selfJob) {
                    progressJob = null
                }
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
        failedOfflineSongIds.clear()
        consecutiveOfflineErrors = 0
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
        clearPreOfflineSnapshot: Boolean = true,
        preserveHomeHydration: Boolean = false
    ) {
        if (!preserveHomeHydration) {
            homeQueueHydrationJob?.cancel()
            homeQueueHydrationJob = null
        }
        val songs = context.tracks
        if (songs.isEmpty()) return
        val shouldShuffle = autoShuffle || _playbackState.value.shuffle
        if (clearPreOfflineSnapshot) {
            clearPreOfflineSnapshotAndHint()
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
                        publishState(_playbackState.value.copy(shuffle = shouldShuffle))
                        val snapshot = PreOfflineSnapshot(
                            context = context,
                            contextOrder = fallbackSeedOrder,
                            resumeIndex = if (shouldShuffle) 0 else startIndex.coerceIn(0, songs.size - 1),
                            preserveExactRandomOrder = shouldShuffle
                        )
                        preOfflineSnapshot = snapshot
                        savePreOfflineSnapshotArtifacts(snapshot)
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
        publishState(updatedState)
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

    /**
     * Plays a song from the paginated Home "All Tracks" list:
     * starts immediately using [initialSongs] for zero-latency playback, then lazily hydrates
     * the remaining catalog songs (from SQLite + remaining paged API batches) into the active
     * queue in the background without storing or prewarming everything at startup.
     */
    fun playHomeAllTracks(
        initialSongs: List<Song>,
        startIndex: Int = 0,
        repository: MusicRepository
    ) {
        if (initialSongs.isEmpty()) return
        failedOfflineSongIds.clear()
        consecutiveOfflineErrors = 0
        homeQueueHydrationJob?.cancel()

        val initialCtx = PlaybackContext(
            uri = HOME_STREAM_URI,
            type = ContextType.PLAYLIST,
            name = "Alaktra Stream",
            trackIds = initialSongs.map { it.id },
            tracks = initialSongs
        )
        playContextInternal(
            context = initialCtx,
            startIndex = startIndex,
            autoShuffle = false,
            clearPreOfflineSnapshot = true,
            preserveHomeHydration = true
        )

        homeQueueHydrationJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Expand quickly from SQLite cached catalog if more tracks are already on device
                val cachedAll = runCatching { repository.getCachedSongs() }.getOrDefault(emptyList())
                if (cachedAll.size > initialSongs.size) {
                    val combinedCached = (initialSongs + cachedAll).distinctBy { it.id }
                    withContext(Dispatchers.Main) {
                        val curState = _playbackState.value
                        if (curState.context?.uri == HOME_STREAM_URI) {
                            val merged = mergeRemainingContextTracks(curState, combinedCached)
                            if (merged != curState) {
                                publishState(merged)
                            }
                        }
                    }
                }

                // 2. If online, fetch remaining pages beyond initialSongs in clean bounded batches
                if (!NetworkMonitor.shouldSkipNetworkRequests(context)) {
                    val collected = LinkedHashMap<Int, Song>(initialSongs.size + 128)
                    initialSongs.forEach { collected[it.id] = it }
                    var offset = initialSongs.size
                    val pageLimit = 100
                    while (isActive && collected.size < 2000) {
                        if (_playbackState.value.context?.uri != HOME_STREAM_URI) break
                        val page = runCatching {
                            repository.getSongsPage(limit = pageLimit, offset = offset, forceRefresh = false)
                        }.getOrNull() ?: break
                        if (page.songs.isEmpty()) break
                        var addedAny = false
                        for (s in page.songs) {
                            if (collected.putIfAbsent(s.id, s) == null) {
                                addedAny = true
                            }
                        }
                        if (!page.hasMore || !addedAny) break
                        offset += page.songs.size
                    }

                    val fullList = collected.values.toList()
                    if (isActive && fullList.size > initialSongs.size) {
                        withContext(Dispatchers.Main) {
                            val curState = _playbackState.value
                            if (curState.context?.uri == HOME_STREAM_URI) {
                                val merged = mergeRemainingContextTracks(curState, fullList)
                                if (merged != curState) {
                                    publishState(merged)
                                    persistCurrentState(forceFull = true)
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AudioController", "Lazy home queue hydration skipped: ${e.message}")
            }
        }
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
                        val repo = MusicRepository.getInstance(context)
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
        publishState(updatedState)
    }

    fun playSong(song: Song, source: String = "Now Playing") {
        failedOfflineSongIds.clear()
        consecutiveOfflineErrors = 0
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
            publishState(
                _playbackState.value.copy(
                    contextIndex = existingIndex
                )
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
        lastRefreshedFullyCachedSongId = null
        lastDurationPersistedSongId = null
        pendingSeekTargetMs = -1L
        pendingSeekDeadlineMs = 0L
        offlineBufferingSinceMs = 0L
        AudioCache.clearEarlyOfflineTruncation()
        val state = _playbackState.value
        val updatedHistory = if (pushToHistory && state.currentSong != null) {
            val wasContext = state.contextOrder.getOrNull(state.contextIndex)?.id == state.currentSong.id
            if (wasContext) state.history + state.currentSong else state.history
        } else {
            state.history
        }
        val updatedRecentPlayed = addToRecentPlayedHistory(song.id, state.recentPlayedSongIds)

        publishState(
            state.copy(
                currentSong = song,
                currentPositionMs = 0L,
                durationMs = (song.duration * 1000).toLong().coerceAtLeast(0L),
                isPlaying = true,
                history = updatedHistory,
                recentPlayedSongIds = updatedRecentPlayed
            )
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

        AudioCache.cancelAllActivePreloads()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        startProgressTracker()
        resolveMissingDurationAsync(song)

        ensureServiceStarted()

        // Extract palette from cover
        extractPalette(song.coverUrl, song.dominantColorHex, song.id)

        // Prepare listening session (counted as played only after >= 20s active playback)
        prepareNewSongListeningSession(song)

        persistCurrentState()
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
                    publishState(relocatedState)
                }
                playSongAtIndex(relocatedState.contextIndex)
                return
            }
            // Context has no cached songs: switch seamlessly to Offline Backup
            playOfflineBackup(autoShuffle = state.shuffle, isAutomaticFallback = true, resumeFromNext = false)
            return
        }

        lastTransientRetrySongId = null
        lastRefreshedFullyCachedSongId = null
        lastDurationPersistedSongId = null
        pendingSeekTargetMs = -1L
        pendingSeekDeadlineMs = 0L
        offlineBufferingSinceMs = 0L
        AudioCache.clearEarlyOfflineTruncation()
        val updatedRecentPlayed = addToRecentPlayedHistory(song.id, state.recentPlayedSongIds)
        publishState(
            state.copy(
                currentSong = song,
                contextIndex = index,
                currentPositionMs = 0L,
                durationMs = (song.duration * 1000).toLong().coerceAtLeast(0L),
                isPlaying = true,
                recentPlayedSongIds = updatedRecentPlayed
            )
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

        AudioCache.cancelAllActivePreloads()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        startProgressTracker()
        resolveMissingDurationAsync(song)

        ensureServiceStarted()

        // Extract palette from cover
        extractPalette(song.coverUrl, song.dominantColorHex, song.id)

        // Prepare listening session (counted as played only after >= 20s active playback)
        prepareNewSongListeningSession(song)

        persistCurrentState()
    }

    fun getUpcomingTracks(maxCount: Int = 4): List<Song> {
        if (maxCount <= 0) return emptyList()
        val state = _playbackState.value
        val currentId = state.currentSong?.id
        val seenIds = HashSet<Int>()
        if (currentId != null) seenIds.add(currentId)
        val result = ArrayList<Song>(maxCount)

        fun tryAdd(song: Song) {
            if (result.size >= maxCount) return
            if (seenIds.add(song.id)) {
                result.add(song)
            }
        }

        // 1. User-queued tracks first
        for (song in state.userQueue) {
            tryAdd(song)
            if (result.size >= maxCount) return result
        }

        // 2. Context queue starting after current index
        val order = state.contextOrder
        if (order.isNotEmpty()) {
            val startIdx = (state.contextIndex + 1).coerceAtLeast(0)
            for (i in startIdx until order.size) {
                tryAdd(order[i])
                if (result.size >= maxCount) return result
            }
            if (state.repeat == RepeatMode.CONTEXT) {
                val wrapEnd = state.contextIndex.coerceIn(0, order.size)
                for (i in 0 until wrapEnd) {
                    tryAdd(order[i])
                    if (result.size >= maxCount) return result
                }
            }
        }

        // 3. Autoplay fallback tracks
        for (song in state.autoplayTracks) {
            tryAdd(song)
            if (result.size >= maxCount) return result
        }

        return result
    }

    fun getUpcomingTrack(): Song? = getUpcomingTracks(1).firstOrNull()

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
        // Flush previous session if active and cache outgoing track's PlayEvent locally on skip/switch
        flushCurrentListeningSession()
        persistCurrentCapsuleSession(syncToServer = false, finalizeSession = true)

        val isDifferentSong = song.id != currentSessionSongId
        currentSessionSong = song
        currentSessionSongId = song.id
        if (isDifferentSong) {
            currentSessionListenedSec = 0L
            currentSessionHistoryId = null
            currentSongRecordedAsPlayed = false
        }
        val now = System.currentTimeMillis()
        lastTickTimeMs = now
        currentCapsuleSessionStartMs = now
        currentCapsuleMsPlayed = 0L
        lastCapsulePersistedMs = 0L
        lastCapsuleTickMs = now
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
        val sessionPlayedAt = currentCapsuleSessionStartMs.takeIf { it > 0L } ?: now

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
                        playedAt = sessionPlayedAt
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
        publishState(
            _playbackState.value.copy(
                isPlaying = false,
                currentPositionMs = if (currentPos > 0L) currentPos else _playbackState.value.currentPositionMs
            )
        )
        stopProgressTracker()
        flushCurrentListeningSession()
        persistCurrentState()
    }

    fun stop() {
        stopNearbyPinsTracking()
        AudioCache.cancelAllActivePreloads()
        player.pause()
        player.seekTo(0)
        publishState(
            _playbackState.value.copy(
                isPlaying = false,
                currentPositionMs = 0L
            )
        )
        stopProgressTracker()
        persistCurrentCapsuleSession(syncToServer = false, finalizeSession = true)
        persistCurrentState()
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
            publishState(_playbackState.value.copy(isPlaying = true))
            startProgressTracker()
            ensureCurrentSongPalette()
            persistCurrentState()
        }
    }

    fun seekTo(positionMs: Long) {
        val stateDur = _playbackState.value.durationMs
        val target = if (stateDur > 0L) {
            positionMs.coerceIn(0L, stateDur)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        pendingSeekTargetMs = target
        pendingSeekDeadlineMs = SystemClock.elapsedRealtime() + 900L
        if (player.currentMediaItem != null) {
            player.seekTo(target)
        } else if (_playbackState.value.currentSong != null) {
            val song = _playbackState.value.currentSong!!
            playSongDirectly(song, pushToHistory = false)
            player.seekTo(target)
        }
        publishState(_playbackState.value.copy(currentPositionMs = target))
        if (player.playWhenReady) {
            startProgressTracker()
        }
        persistCurrentState()
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
                publishState(state.copy(userQueue = remainingUserQueue))
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
                publishState(updatedState)
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
            publishState(state.copy(userQueue = remainingUserQueue))
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
            publishState(updatedState)
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
            publishState(updatedState)
            playSongAtIndex(updatedState.contextIndex)
            return
        } else {
            // While repeat !== 'off', autoplay never triggers
            if (state.repeat == RepeatMode.OFF) {
                if (state.autoplayTracks.isNotEmpty()) {
                    val nextAutoplay = state.autoplayTracks.first()
                    val remainingAutoplay = state.autoplayTracks.drop(1)
                    publishState(state.copy(autoplayTracks = remainingAutoplay))
                    playSongDirectly(nextAutoplay, pushToHistory = false)
                    return
                } else {
                    val candidateTracks = state.context?.tracks ?: emptyList()
                    if (candidateTracks.isNotEmpty()) {
                        val generated = candidateTracks.shuffled().take(5)
                        val first = generated.first()
                        val remaining = generated.drop(1)
                        publishState(state.copy(autoplayTracks = remaining))
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
                publishState(
                    state.copy(
                        history = remHistory,
                        contextIndex = if (orderIdx != -1) orderIdx else state.contextIndex
                    )
                )
                playSongDirectly(prevInHistory, pushToHistory = false)
                return
            }

            val prevPlayable = findPreviousPlayableOfflineIndex(state.contextIndex)
            if (prevPlayable != -1 && prevPlayable != state.contextIndex) {
                publishState(state.copy(contextIndex = prevPlayable))
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
            publishState(
                state.copy(
                    history = remainingHistory,
                    contextIndex = newIndex
                )
            )
            playSongDirectly(lastPlayed, pushToHistory = false)
        } else if (state.contextIndex > 0) {
            val prevIndex = state.contextIndex - 1
            publishState(state.copy(contextIndex = prevIndex))
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
        if (song.id in failedOfflineSongIds) return false
        return AudioCache.isSongPlayableOfflineFast(context, song)
    }

    /**
     * Finds next playable offline track index in contextOrder from [fromIndex].
     * Searches forward first, and only wraps backward when repeating the context or inside Offline Backup.
     */
    fun findNextPlayableOfflineIndex(fromIndex: Int): Int {
        val state = _playbackState.value
        return findNextPlayableOfflineIndexStatic(
            order = state.contextOrder,
            fromIndex = fromIndex,
            currentIndex = state.contextIndex,
            allowWrap = state.repeat == RepeatMode.CONTEXT || isOfflineBackupContext(state.context),
            isPlayable = ::isSongPlayableOffline
        )
    }

    /**
     * Finds previous playable offline track index in contextOrder from [fromIndex].
     */
    fun findPreviousPlayableOfflineIndex(fromIndex: Int): Int {
        val state = _playbackState.value
        val order = state.contextOrder
        if (order.isEmpty()) return -1
        val start = (fromIndex - 1).coerceIn(-1, order.size - 1)
        for (i in start downTo 0) {
            if (isSongPlayableOffline(order[i])) return i
        }
        val wrapEnd = (start + 1).coerceIn(0, order.size - 1)
        for (i in (order.size - 1) downTo wrapEnd) {
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
                publishState(relocatedState)
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
            clearPreOfflineSnapshotAndHint()
        }
        val shouldShuffle = autoShuffle || _playbackState.value.shuffle
        offlineBackupJob?.cancel()
        offlineBackupJob = scope.launch(Dispatchers.IO) {
            val offlineSongs = try {
                val repo = MusicRepository.getInstance(context)
                repo.getOfflineBackupSongs().filter { it.id !in failedOfflineSongIds }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AudioController", "Failed to load offline backup songs", e)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (isAutomaticFallback && NetworkMonitor.isOnline(context)) {
                    val curState = _playbackState.value
                    val playerIdleOrDead = try {
                        player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE
                    } catch (_: Exception) {
                        true
                    }
                    if (isOfflineBackupContext(curState.context) || isRestoredContext(curState.context) || (preOfflineSnapshot != null && playerIdleOrDead)) {
                        restoreOnlineContextOrStream()
                    }
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
                } else {
                    player.stop()
                    player.clearMediaItems()
                    publishState(_playbackState.value.copy(isPlaying = false))
                    stopProgressTracker()
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
            publishState(updated)
        }
    }

    fun toggleShuffle(forceEnable: Boolean? = null) {
        val state = _playbackState.value
        val newShuffle = forceEnable ?: !state.shuffle
        val naturalTracks = state.context?.tracks ?: state.contextOrder

        if (!newShuffle) {
            val updatedSnap = preOfflineSnapshot?.copy(preserveExactRandomOrder = false)
            preOfflineSnapshot = updatedSnap
            if (updatedSnap != null) {
                savePreOfflineSnapshotArtifacts(updatedSnap)
            }
        }

        if (naturalTracks.isEmpty()) {
            publishState(state.copy(shuffle = newShuffle))
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
        publishState(newState)
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
        publishState(state)
        persistCurrentState()
    }

    fun toggleLoop() {
        cycleRepeatMode()
    }

    /** Add song to the end of user queue ("Next in Queue") - Destructive FIFO */
    fun addToQueue(song: Song) {
        val updated = _playbackState.value.userQueue + song
        publishState(_playbackState.value.copy(userQueue = updated))
    }

    /** Add song immediately next in line ("Play Next") */
    fun playNext(song: Song) {
        val updated = listOf(song) + _playbackState.value.userQueue
        publishState(_playbackState.value.copy(userQueue = updated))
    }

    /** Alias for backward compatibility */
    fun addToUpNext(song: Song) {
        addToQueue(song)
    }

    /** Promote a track from context layer into userQueue */
    fun promoteToUserQueue(song: Song) {
        // Pushes track into userQueue without modifying contextOrder or removing from context
        publishState(
            _playbackState.value.copy(
                userQueue = _playbackState.value.userQueue + song
            )
        )
    }

    /** Promote a track from context layer into userQueue at specific position */
    fun promoteToUserQueueAt(song: Song, targetIndex: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        val clamped = targetIndex.coerceIn(0, current.size)
        current.add(clamped, song)
        publishState(_playbackState.value.copy(userQueue = current))
    }

    /** Remove a song from the user-enqueued layer */
    fun removeFromUserQueue(index: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            publishState(_playbackState.value.copy(userQueue = current))
        }
    }

    /** Clear all songs from the user-enqueued layer (scoped to user queue only) */
    fun clearUserQueue() {
        publishState(_playbackState.value.copy(userQueue = emptyList()))
    }

    /** Reorder songs inside the user-enqueued layer */
    fun reorderUserQueue(from: Int, to: Int) {
        val current = _playbackState.value.userQueue.toMutableList()
        if (from in current.indices && to in current.indices) {
            val moved = current.removeAt(from)
            current.add(to, moved)
            publishState(_playbackState.value.copy(userQueue = current))
        }
    }

    /** Immediately play a song from the user-enqueued layer and remove it */
    fun playUserQueueItem(index: Int) {
        failedOfflineSongIds.clear()
        consecutiveOfflineErrors = 0
        val current = _playbackState.value.userQueue.toMutableList()
        if (index in current.indices) {
            val songToPlay = current.removeAt(index)
            publishState(_playbackState.value.copy(userQueue = current))
            playSongDirectly(songToPlay, pushToHistory = false)
        }
    }

    /** Play a song directly from the context queue */
    fun playContextQueueItem(index: Int) {
        failedOfflineSongIds.clear()
        consecutiveOfflineErrors = 0
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
            publishState(updatedState)
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
            publishState(updatedState)
        }
    }

    fun reorderQueue(from: Int, to: Int) {
        val currentOrder = _playbackState.value.contextOrder.toMutableList()
        if (from in currentOrder.indices && to in currentOrder.indices) {
            val moved = currentOrder.removeAt(from)
            currentOrder.add(to, moved)
            val currSong = _playbackState.value.currentSong
            val newIndex = if (currSong != null) currentOrder.indexOfFirst { it.id == currSong.id } else _playbackState.value.contextIndex
            publishState(
                _playbackState.value.copy(
                    contextOrder = currentOrder,
                    contextIndex = if (newIndex != -1) newIndex else _playbackState.value.contextIndex
                )
            )
        }
    }

    private fun handleTrackEnded() {
        flushCurrentListeningSession()
        persistCurrentCapsuleSession(syncToServer = false, finalizeSession = true)
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

        publishState(
            state.copy(
                context = updatedContext,
                contextOrder = updatedOrder,
                currentSong = updatedSong,
                userQueue = updatedUserQueue
            )
        )
    }

    fun resetForLogout() {
        persistCurrentCapsuleSession(syncToServer = false, finalizeSession = true)
        homeQueueHydrationJob?.cancel()
        homeQueueHydrationJob = null
        AudioCache.cancelAllActivePreloads()
        lastPersistedOrderIdentity = null
        lastPersistedContextIdentity = null
        lastPersistedRecentIdentity = null
        preOfflineSnapshot = null
        lastTransientRetrySongId = null
        failedOfflineSongIds.clear()
        consecutiveOfflineErrors = 0
        player.stop()
        player.clearMediaItems()
        publishState(PlaybackState())
        try {
            authPrefs.clearPlaybackState()
        } catch (_: Exception) {}
    }

    fun ensureCurrentSongPalette() {
        val song = _playbackState.value.currentSong ?: return
        val currentDom = _playbackState.value.dominantColor
        val hasValidColor = currentDom != Color.Unspecified && currentDom != Color.Transparent
        if (paletteResolvedFromArtSongId != song.id || !hasValidColor) {
            if (paletteJob?.isActive != true) {
                extractPalette(
                    imageUrl = song.coverUrl,
                    overrideHex = song.dominantColorHex,
                    songId = song.id,
                    forceRetry = true
                )
            }
        }
    }

    fun onCurrentSongArtworkLoaded(
        songId: Int,
        imageUrl: String
    ) {
        val currentSong = _playbackState.value.currentSong ?: return
        if (currentSong.id != songId) return
        val currentDom = _playbackState.value.dominantColor
        val hasValidColor = currentDom != Color.Unspecified && currentDom != Color.Transparent
        if (paletteResolvedFromArtSongId == songId && hasValidColor) return

        scope.launch(Dispatchers.IO) {
            // 1. Check image metadata (DOMINANT_COLOR tag) in local / disk-cached cover file
            val urls = buildList {
                if (imageUrl.isNotBlank()) add(imageUrl)
                if (currentSong.coverUrl.isNotBlank()) add(currentSong.coverUrl)
            }
            var metadataHex: String? = null
            val localCover = ArtworkColorExtractor.resolveLocalCoverFile(context, songId, imageUrl)
            if (localCover != null) {
                metadataHex = ArtworkColorExtractor.extractMetadataColorHexFromFile(localCover)
            }
            if (metadataHex == null) {
                for (attempt in 0 until 3) {
                    if (attempt > 0) delay(60L)
                    metadataHex = ArtworkColorExtractor.extractFromCaches(context, urls)
                    if (metadataHex != null) break
                }
            }

            if (metadataHex != null) {
                applyResolvedSongPalette(songId = songId, hex = metadataHex, resolvedFromArt = true)
                return@launch
            }

            // 2. Fallback ONLY to server-provided dominant color stored on Song / SQLite (never visual pixel extraction)
            val dbDao = com.example.data.local.AppDatabase.getInstance(context).songDao()
            val storedHex = currentSong.dominantColorHex?.takeIf { it.isNotBlank() }
                ?: dbDao.getDownloadedSong(songId)?.dominantColorHex?.takeIf { it.isNotBlank() }
                ?: dbDao.getCachedSong(songId)?.dominantColorHex?.takeIf { it.isNotBlank() }

            if (storedHex != null) {
                applyResolvedSongPalette(songId = songId, hex = storedHex, resolvedFromArt = true)
            }
        }
    }

    private suspend fun applyResolvedSongPalette(
        songId: Int?,
        hex: String,
        resolvedFromArt: Boolean
    ) {
        val normalizedHex = ArtworkColorExtractor.normalizeHex(hex) ?: return
        val parsedColor = ArtworkColorExtractor.parseHexColor(normalizedHex) ?: return

        if (resolvedFromArt && songId != null) {
            if (resolvedArtColorCache.size >= 128) {
                resolvedArtColorCache.clear()
            }
            resolvedArtColorCache[songId] = parsedColor
            paletteResolvedFromArtSongId = songId
        }

        var shouldPersistPlayback = false
        withContext(Dispatchers.Main) {
            val state = _playbackState.value
            val activeSong = state.currentSong
            if (songId == null || activeSong?.id == songId) {
                val updatedSong = if (activeSong != null && activeSong.dominantColorHex != normalizedHex) {
                    shouldPersistPlayback = true
                    activeSong.copy(dominantColorHex = normalizedHex)
                } else {
                    activeSong
                }
                val updatedOrder = if (songId != null && state.contextOrder.any { it.id == songId && it.dominantColorHex != normalizedHex }) {
                    state.contextOrder.map { s ->
                        if (s.id == songId) s.copy(dominantColorHex = normalizedHex) else s
                    }
                } else {
                    state.contextOrder
                }
                publishState(
                    state.copy(
                        currentSong = updatedSong,
                        contextOrder = updatedOrder,
                        dominantColor = parsedColor,
                        secondaryColor = parsedColor
                    )
                )
            }
        }

        if (resolvedFromArt && songId != null) {
            try {
                com.example.data.local.AppDatabase.getInstance(context)
                    .songDao()
                    .updateSongDominantColor(songId, normalizedHex)
            } catch (_: Exception) {}
            if (shouldPersistPlayback) {
                persistCurrentState()
            }
        }
    }

    private fun extractPalette(
        imageUrl: String,
        overrideHex: String? = null,
        songId: Int? = null,
        forceRetry: Boolean = false
    ) {
        paletteJob?.cancel()

        // Fast 0ms session cache hit if this song's artwork metadata was already extracted
        if (songId != null && !forceRetry) {
            val cachedColor = resolvedArtColorCache[songId]
            if (cachedColor != null) {
                paletteResolvedFromArtSongId = songId
                publishState(
                    _playbackState.value.copy(
                        dominantColor = cachedColor,
                        secondaryColor = cachedColor
                    )
                )
                return
            }
        }

        if (paletteResolvedFromArtSongId != songId) {
            paletteResolvedFromArtSongId = null
        }

        // Apply provisional color immediately while background IO reads cover art metadata
        val provisionalColor = ArtworkColorExtractor.parseHexColor(overrideHex)
        if (provisionalColor != null) {
            publishState(
                _playbackState.value.copy(
                    dominantColor = provisionalColor,
                    secondaryColor = provisionalColor
                )
            )
        } else if (!forceRetry) {
            publishState(
                _playbackState.value.copy(
                    dominantColor = Color.Transparent,
                    secondaryColor = Color.Transparent
                )
            )
        }

        paletteJob = scope.launch(Dispatchers.IO) {
            val dbDao = try {
                com.example.data.local.AppDatabase.getInstance(context).songDao()
            } catch (_: Exception) {
                null
            }
            val downloadedEntity = if (songId != null) {
                try { dbDao?.getDownloadedSong(songId) } catch (_: Exception) { null }
            } else null
            val cachedEntity = if (songId != null) {
                try { dbDao?.getCachedSong(songId) } catch (_: Exception) { null }
            } else null

            // Hydrate provisional color from local SQLite if overrideHex was missing (e.g. offline/history/restored state)
            val dbFallbackHex = downloadedEntity?.dominantColorHex?.takeIf { it.isNotBlank() }
                ?: cachedEntity?.dominantColorHex?.takeIf { it.isNotBlank() }
            if (provisionalColor == null && dbFallbackHex != null) {
                applyResolvedSongPalette(songId = songId, hex = dbFallbackHex, resolvedFromArt = false)
            }

            val candidateUrls = buildList {
                if (imageUrl.isNotBlank()) add(imageUrl)
                downloadedEntity?.coverUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
                cachedEntity?.coverUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.distinct()

            val retryDelaysMs = longArrayOf(0L, 600L)
            for ((attemptIdx, delayMs) in retryDelaysMs.withIndex()) {
                if (!isActive) return@launch
                if (delayMs > 0L) delay(delayMs)
                if (songId != null && _playbackState.value.currentSong?.id != songId) return@launch
                if (songId != null && paletteResolvedFromArtSongId == songId) return@launch

                // 1. Check local cover file(s) directly (works 100% offline & online)
                val localCoverFile = ArtworkColorExtractor.resolveLocalCoverFile(
                    context = context,
                    songId = songId,
                    imageUrl = downloadedEntity?.coverUrl?.takeIf { it.isNotBlank() } ?: imageUrl
                )
                if (localCoverFile != null) {
                    val fileHex = ArtworkColorExtractor.extractMetadataColorHexFromFile(localCoverFile)
                    if (fileHex != null) {
                        applyResolvedSongPalette(songId = songId, hex = fileHex, resolvedFromArt = true)
                        return@launch
                    }
                }

                // 2. Check Coil DiskCache (`image_cache`) and OkHttp (`okhttp_image_cache`) directly (works 100% offline!)
                val cachedHex = ArtworkColorExtractor.extractFromCaches(context, candidateUrls)
                if (cachedHex != null) {
                    applyResolvedSongPalette(songId = songId, hex = cachedHex, resolvedFromArt = true)
                    return@launch
                }

                // 3. Execute full-quality Coil ImageRequest to populate disk cache so metadata tags can be read
                val isOnline = NetworkMonitor.isOnline(context)
                val requestDataList = buildList<Any> {
                    if (localCoverFile != null) add(localCoverFile)
                    addAll(candidateUrls)
                }

                for (dataTarget in requestDataList) {
                    if (!isActive) return@launch
                    try {
                        val isRemoteTarget = dataTarget is String &&
                            (dataTarget.startsWith("http://", ignoreCase = true) ||
                                dataTarget.startsWith("https://", ignoreCase = true))
                        val reqBuilder = ImageRequest.Builder(context)
                            .data(dataTarget)
                            .size(Size.ORIGINAL)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        if (!isOnline && isRemoteTarget) {
                            // Force offline cache lookup so Coil never aborts with OfflineException before checking caches
                            reqBuilder.networkCachePolicy(coil.request.CachePolicy.DISABLED)
                        }

                        val result = context.imageLoader.execute(reqBuilder.build())
                        if (result is coil.request.SuccessResult) {
                            // Poll briefly in case a concurrent SongCover DiskCache.Editor is finishing its write lock
                            var postFetchHex: String? = localCoverFile?.let {
                                ArtworkColorExtractor.extractMetadataColorHexFromFile(it)
                            }
                            if (postFetchHex == null) {
                                for (poll in 0 until 3) {
                                    if (poll > 0) delay(60L)
                                    postFetchHex = ArtworkColorExtractor.extractFromCaches(context, candidateUrls)
                                    if (postFetchHex != null) break
                                }
                            }

                            if (postFetchHex != null) {
                                applyResolvedSongPalette(songId = songId, hex = postFetchHex, resolvedFromArt = true)
                                return@launch
                            }

                            // No embedded DOMINANT_COLOR tag in file: use ONLY server/DB color if present
                            val serverOrDbHex = overrideHex?.takeIf { it.isNotBlank() } ?: dbFallbackHex
                            if (serverOrDbHex != null) {
                                applyResolvedSongPalette(songId = songId, hex = serverOrDbHex, resolvedFromArt = true)
                                return@launch
                            }
                        }
                    } catch (_: Exception) {}
                }

                // When offline, stop after 2 local/cache attempts; onCurrentSongArtworkLoaded or network reconnect will retry
                if (!isOnline && attemptIdx >= 1) {
                    break
                }
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
        const val HOME_STREAM_URI = "alaktra:playlist:alaktra_stream"

        /**
         * Merges lazily hydrated catalog tracks into an active Home "Alaktra Stream" playback state
         * without interrupting the current song or losing upcoming shuffle randomness.
         */
        fun mergeRemainingContextTracks(
            state: PlaybackState,
            fullTracks: List<Song>
        ): PlaybackState {
            val ctx = state.context ?: return state
            if (ctx.uri != HOME_STREAM_URI || fullTracks.isEmpty()) return state

            val fullById = LinkedHashMap<Int, Song>(fullTracks.size)
            for (song in fullTracks) {
                fullById.putIfAbsent(song.id, song)
            }
            val existingIds = ctx.tracks.mapTo(HashSet()) { it.id }
            val newlyAdded = fullById.values.filter { it.id !in existingIds }
            if (newlyAdded.isEmpty()) return state

            val mergedNatural = ctx.tracks.map { fullById[it.id] ?: it } + newlyAdded
            val updatedCtx = ctx.copy(
                trackIds = mergedNatural.map { it.id },
                tracks = mergedNatural
            )
            val currSong = state.currentSong

            val (updatedOrder, updatedIndex) = if (state.shuffle) {
                val safeCurrIdx = state.contextIndex.coerceIn(0, (state.contextOrder.size - 1).coerceAtLeast(0))
                val prefixAndCurrent = if (state.contextOrder.isNotEmpty()) {
                    state.contextOrder.take(safeCurrIdx + 1)
                } else if (currSong != null) {
                    listOf(currSong)
                } else {
                    emptyList()
                }
                val prefixIds = prefixAndCurrent.mapTo(HashSet()) { it.id }
                val existingUpcoming = state.contextOrder
                    .drop(safeCurrIdx + 1)
                    .distinctBy { it.id }
                    .filter { it.id !in prefixIds }
                val existingUpcomingIds = existingUpcoming.mapTo(HashSet()) { it.id }
                val unseenNew = newlyAdded.filter { it.id !in prefixIds && it.id !in existingUpcomingIds }
                val combinedUpcoming = ShuffleUtils.artistSpreadShuffle(existingUpcoming + unseenNew)
                val newOrder = prefixAndCurrent + combinedUpcoming
                val newIdx = if (prefixAndCurrent.isNotEmpty()) prefixAndCurrent.size - 1 else 0
                newOrder to newIdx
            } else {
                val newIdx = if (currSong != null) {
                    mergedNatural.indexOfFirst { it.id == currSong.id }.takeIf { it != -1 }
                        ?: state.contextIndex.coerceIn(0, (mergedNatural.size - 1).coerceAtLeast(0))
                } else {
                    state.contextIndex.coerceIn(0, (mergedNatural.size - 1).coerceAtLeast(0))
                }
                mergedNatural to newIdx
            }

            val updated = state.copy(
                context = updatedCtx,
                contextOrder = updatedOrder,
                contextIndex = updatedIndex
            )
            return ensureContextBufferStatic(updated)
        }

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
         * Pure helper for selecting the next offline-playable index in [order].
         * Scans forward from [fromIndex] first. Only wraps around before [currentIndex]
         * if [allowWrap] is enabled so Repeat=OFF can fall through cleanly to Offline Backup.
         */
        fun findNextPlayableOfflineIndexStatic(
            order: List<Song>,
            fromIndex: Int,
            currentIndex: Int,
            allowWrap: Boolean,
            isPlayable: (Song) -> Boolean
        ): Int {
            if (order.isEmpty()) return -1
            val start = fromIndex.coerceIn(0, order.size)
            for (i in start until order.size) {
                if (isPlayable(order[i])) return i
            }
            if (allowWrap) {
                val wrapLimit = if (currentIndex in order.indices) currentIndex else start
                for (i in 0 until wrapLimit) {
                    if (isPlayable(order[i])) return i
                }
                if (currentIndex in order.indices && isPlayable(order[currentIndex])) {
                    return currentIndex
                }
            }
            return -1
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
                        val clampedResume = snapshot.resumeIndex.coerceIn(0, naturalTracks.size)
                        val before = naturalTracks.take(clampedResume)
                        val after = naturalTracks.drop(clampedResume)
                        (before + listOf(currSong) + after) to before.size
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
