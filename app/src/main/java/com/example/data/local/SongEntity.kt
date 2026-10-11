package com.example.data.local

import com.example.model.Song

data class SongEntity(
    val id: Int,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val duration: Double,
    val isLiked: Boolean = false,
    val lastSyncedAt: Long = System.currentTimeMillis()
) {
    fun toSong(): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            coverUrl = coverUrl,
            duration = duration,
            isLiked = isLiked
        )
    }

    companion object {
        fun fromSong(
            song: Song,
            isLiked: Boolean = song.isLiked,
            lastSyncedAt: Long = System.currentTimeMillis()
        ): SongEntity {
            return SongEntity(
                id = song.id,
                title = song.title,
                artist = song.artist,
                audioUrl = song.audioUrl,
                coverUrl = song.coverUrl,
                duration = song.duration,
                isLiked = isLiked,
                lastSyncedAt = lastSyncedAt
            )
        }
    }
}
