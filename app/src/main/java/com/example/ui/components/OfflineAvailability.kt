package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.compositionLocalOf
import com.example.model.Song

/**
 * Provides reactive connectivity and offline-playability state down the Compose tree
 * with a single root collector, avoiding per-row Flow overhead on low-end devices.
 */
val LocalIsOnline = compositionLocalOf { true }
val LocalOfflinePlayableIds = compositionLocalOf<Set<Int>> { emptySet() }
val LocalCacheIndexWarmed = compositionLocalOf { false }

/**
 * Determines whether a track should appear enabled (non-greyed-out) in the UI.
 *
 * Strictly guarantees tracks are NEVER greyed out when online (`isOnline == true`).
 * When offline (`isOnline == false`), only tracks that are downloaded or 100% cached remain active.
 */
fun isSongPlayableInUi(
    song: Song,
    isOnline: Boolean,
    offlinePlayableIds: Set<Int>,
    cacheIndexWarmed: Boolean
): Boolean {
    if (isOnline) return true
    if (song.id in offlinePlayableIds) return true
    if (!cacheIndexWarmed) {
        return song.isDownloaded || song.isCached || !song.localPath.isNullOrBlank()
    }
    return false
}

private var lastOfflineToastTimeMs: Long = 0L

fun showOfflineUnplayableToast(context: Context) {
    val now = System.currentTimeMillis()
    if (now - lastOfflineToastTimeMs < 1500L) return
    lastOfflineToastTimeMs = now
    Toast.makeText(
        context,
        "Connect to internet or server to play this track",
        Toast.LENGTH_SHORT
    ).show()
}
