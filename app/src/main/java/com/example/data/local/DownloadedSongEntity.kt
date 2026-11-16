package com.example.data.local

import com.example.model.ArtistCreditJsonCodec
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
    val fileSize: Long = 0L,
    val dominantColorHex: String? = null,
    val primaryArtistId: Int? = null,
    val artistsJson: String? = null
) {
    fun toSong(): Song {
        val credits = ArtistCreditJsonCodec.decode(artistsJson)
        return Song(
            id = songId,
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            coverUrl = coverUrl,
            duration = duration,
            localPath = localPath,
            isDownloaded = true,
            dominantColorHex = dominantColorHex,
            primaryArtistId = primaryArtistId ?: credits.firstOrNull { it.role == "primary" }?.id ?: credits.firstOrNull()?.id,
            artists = credits
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
                fileSize = fileSize,
                dominantColorHex = song.dominantColorHex,
                primaryArtistId = song.primaryArtistId,
                artistsJson = ArtistCreditJsonCodec.encode(song.artists)
            )
        }
    }
}
