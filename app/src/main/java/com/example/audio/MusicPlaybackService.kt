package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var forwardingPlayer: ForwardingPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var headsetClickCount = 0
    private var headsetClickJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun handleHeadsetHookClick(audioController: AudioController) {
        headsetClickCount++
        headsetClickJob?.cancel()
        headsetClickJob = serviceScope.launch {
            delay(350)
            when (headsetClickCount) {
                1 -> audioController.togglePlayPause()
                2 -> audioController.next()
                3 -> audioController.previous()
                else -> audioController.previous()
            }
            headsetClickCount = 0
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_READY)
            if (isPlaying) {
                acquireLocks()
            } else if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                releaseLocks()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                acquireLocks()
            } else {
                releaseLocks()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // 1. Create notification channel for Android O+
        createNotificationChannel()

        // 2. Configure media notification provider with wall-clock time suppression
        try {
            val provider = object : MediaNotification.Provider {
                private val defaultProvider = DefaultMediaNotificationProvider.Builder(this@MusicPlaybackService)
                    .setChannelId(CHANNEL_ID)
                    .build()

                override fun createNotification(
                    mediaSession: MediaSession,
                    customLayout: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback
                ): MediaNotification {
                    val mediaNotification = defaultProvider.createNotification(
                        mediaSession,
                        customLayout,
                        actionFactory,
                        onNotificationChangedCallback
                    )
                    val notif = mediaNotification.notification
                    // Explicitly suppress wall-clock timestamp and chronometer from header.
                    // Media notifications represent ongoing playback controlled via MediaSession seekbar,
                    // not wall-clock events. Showing "when" or chronometer causes an unsynced running timer.
                    notif.`when` = 0L
                    notif.extras.putBoolean(Notification.EXTRA_SHOW_WHEN, false)
                    notif.extras.putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                    return mediaNotification
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: Bundle
                ): Boolean {
                    return defaultProvider.handleCustomCommand(session, action, extras)
                }
            }
            setMediaNotificationProvider(provider)
        } catch (e: Exception) {
            Log.w("MusicPlaybackService", "Failed to set custom notification provider", e)
        }
    }

    private fun initializeSessionIfNeeded() {
        if (mediaSession != null) return
        val audioController = AudioController.getInstance(applicationContext)
        val player = audioController.player
        player.addListener(playerListener)

        val forwarding = object : ForwardingPlayer(player) {
            override fun play() {
                audioController.play()
            }

            override fun pause() {
                audioController.pause()
            }

            override fun stop() {
                audioController.stop()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    audioController.play()
                } else {
                    audioController.pause()
                }
            }

            override fun seekTo(positionMs: Long) {
                audioController.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                audioController.seekTo(positionMs)
            }

            override fun getCurrentPosition(): Long {
                val state = audioController.playbackState.value
                val playerPos = player.currentPosition.coerceAtLeast(0L)
                return if (playerPos > 0L) playerPos else state.currentPositionMs
            }

            override fun getDuration(): Long {
                val playerDur = player.duration
                if (playerDur > 0L && playerDur != androidx.media3.common.C.TIME_UNSET) {
                    return playerDur
                }
                val stateDur = audioController.playbackState.value.durationMs
                return if (stateDur > 0L) stateDur else super.getDuration()
            }

            override fun isPlaying(): Boolean {
                val state = audioController.playbackState.value
                if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                    return false
                }
                return player.isPlaying && state.isPlaying
            }

            override fun getPlayWhenReady(): Boolean {
                val state = audioController.playbackState.value
                if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                    return false
                }
                return player.playWhenReady && state.isPlaying
            }

            override fun getPlaybackState(): Int {
                val state = audioController.playbackState.value
                if (!state.isPlaying && player.playbackState == Player.STATE_READY) {
                    return Player.STATE_READY
                }
                return player.playbackState
            }

            override fun seekToNext() {
                audioController.next()
            }

            override fun seekToNextMediaItem() {
                audioController.next()
            }

            override fun seekToPrevious() {
                audioController.previous()
            }

            override fun seekToPreviousMediaItem() {
                audioController.previous()
            }

            override fun hasNextMediaItem(): Boolean {
                val state = audioController.playbackState.value
                return state.userQueue.isNotEmpty() ||
                        (state.contextIndex + 1 < state.contextOrder.size) ||
                        (state.repeat == RepeatMode.CONTEXT && state.contextOrder.isNotEmpty()) ||
                        state.autoplayTracks.isNotEmpty()
            }

            override fun hasPreviousMediaItem(): Boolean {
                val state = audioController.playbackState.value
                return state.history.isNotEmpty() || state.contextIndex > 0 || (state.currentPositionMs / 1000 > 3)
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_STOP)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_STOP -> true
                    else -> super.isCommandAvailable(command)
                }
            }
        }
        forwardingPlayer = forwarding

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mediaSessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().build()
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_STOP)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }

                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            handleHeadsetHookClick(audioController)
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            audioController.play()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            audioController.pause()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            audioController.next()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            audioController.previous()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            audioController.stop()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }

        val session = MediaSession.Builder(this, forwarding)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(mediaSessionCallback)
            .build()
        mediaSession = session
        addSession(session)

        // Observe playback state changes from AudioController to keep notification and session in sync
        serviceScope.launch {
            var lastPlaying = audioController.playbackState.value.isPlaying
            var lastSongId = audioController.playbackState.value.currentSong?.id
            audioController.playbackState.collect { state ->
                val playingChanged = state.isPlaying != lastPlaying
                val songChanged = state.currentSong?.id != lastSongId
                lastPlaying = state.isPlaying
                lastSongId = state.currentSong?.id

                if (playingChanged || songChanged) {
                    mediaSession?.let { s ->
                        try {
                            onUpdateNotification(s, state.isPlaying)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        if (player.isPlaying) {
            acquireLocks()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            initializeSessionIfNeeded()
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (mediaSession == null) {
            val audioController = AudioController.getInstance(applicationContext)
            if (audioController.playbackState.value.currentSong != null) {
                initializeSessionIfNeeded()
            }
        }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && (player.isPlaying || player.playWhenReady)) {
            // Keep playing when task is removed from recents
            return
        }
        super.onTaskRemoved(rootIntent)
        if (player != null && !player.playWhenReady && player.mediaItemCount == 0) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                // Ignore
            }
            stopSelf()
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlreadyMusic:PlaybackWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3-hour safeguard
            }
        } catch (e: Exception) {
            Log.w("MusicPlaybackService", "Could not acquire WakeLock", e)
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AlreadyMusic:PlaybackWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w("MusicPlaybackService", "Could not acquire WifiLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w("MusicPlaybackService", "Could not release WakeLock", e)
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.w("MusicPlaybackService", "Could not release WifiLock", e)
        }
    }

    override fun onDestroy() {
        headsetClickJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        mediaSession?.player?.removeListener(playerListener)
        mediaSession?.run {
            removeSession(this)
            release()
            mediaSession = null
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w("MusicPlaybackService", "Error stopping foreground on destroy", e)
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "playback_channel_id"
        const val NOTIFICATION_ID = 1001
    }
}
