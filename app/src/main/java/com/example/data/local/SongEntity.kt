package com.example.data.local

import com.example.model.ArtistCreditJsonCodec
import com.example.model.Song

data class SongEntity(
    val id: Int,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val duration: Double,
    val isLiked: Boolean = false,
    val dominantColorHex: String? = null,
    val primaryArtistId: Int? = null,
    val artistsJson: String? = null,
    val lastSyncedAt: Long = System.currentTimeMillis()
) {
    fun toSong(): Song {
        val credits = ArtistCreditJsonCodec.decode(artistsJson)
        return Song(
            id = id,
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            coverUrl = coverUrl,
            duration = duration,
            isLiked = isLiked,
            dominantColorHex = dominantColorHex,
            primaryArtistId = primaryArtistId ?: credits.firstOrNull { it.role == "primary" }?.id ?: credits.firstOrNull()?.id,
            artists = credits
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
                dominantColorHex = song.dominantColorHex,
                primaryArtistId = song.primaryArtistId,
                artistsJson = ArtistCreditJsonCodec.encode(song.artists),
                lastSyncedAt = lastSyncedAt
            )
        }
    }
}
