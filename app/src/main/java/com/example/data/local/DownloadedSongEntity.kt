package com.example.data.local

import com.example.model.Song

data class DownloadedSongEntity(
    val songId: Int,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val localPath: String,
    val duration: Double,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0L
) {
    fun toSong(): Song {
        return Song(
            id = songId,
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            coverUrl = coverUrl,
            duration = duration,
            localPath = localPath,
            isDownloaded = true
        )
    }

    companion object {
        fun fromSong(song: Song, localPath: String, fileSize: Long = 0L): DownloadedSongEntity {
            return DownloadedSongEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                audioUrl = song.audioUrl,
                coverUrl = song.coverUrl,
                localPath = localPath,
                duration = song.duration,
                downloadedAt = System.currentTimeMillis(),
                fileSize = fileSize
            )
        }
    }
}
