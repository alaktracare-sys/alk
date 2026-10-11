package com.example.data.local

import com.example.model.Song

data class ListeningHistoryEntity(
    val id: Long = 0L,
    val songId: Int,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val duration: Double,
    val listenedSeconds: Long = 0L,
    val playedAt: Long // Epoch timestamp in milliseconds
) {
    fun toSong(): Song {
        return Song(
            id = songId,
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            coverUrl = coverUrl,
            duration = duration
        )
    }

    /**
     * Returns the active listening duration in seconds recorded for this track session.
     */
    val effectiveListenedSeconds: Long
        get() = listenedSeconds.coerceAtLeast(0L)
}
