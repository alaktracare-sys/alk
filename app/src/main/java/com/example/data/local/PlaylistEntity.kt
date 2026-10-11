package com.example.data.local

import com.example.model.Playlist

data class PlaylistEntity(
    val id: Int,
    val userId: Int,
    val name: String,
    val description: String = "",
    val isSystem: Boolean = false,
    val songCount: Int = 0,
    val lastSyncedAt: Long = System.currentTimeMillis()
) {
    fun toPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            description = description,
            songCount = songCount,
            isLikedPlaylist = isSystem && (id == Playlist.ID_LIKED || name.contains("Liked", ignoreCase = true)),
            isDownloadedPlaylist = isSystem && (id == Playlist.ID_DOWNLOADED || name.contains("Download", ignoreCase = true))
        )
    }

    companion object {
        fun fromPlaylist(
            playlist: Playlist,
            userId: Int,
            isSystem: Boolean = false,
            lastSyncedAt: Long = System.currentTimeMillis()
        ): PlaylistEntity {
            return PlaylistEntity(
                id = playlist.id,
                userId = userId,
                name = playlist.name,
                description = playlist.description,
                isSystem = isSystem || playlist.isLikedPlaylist || playlist.isDownloadedPlaylist,
                songCount = playlist.songCount,
                lastSyncedAt = lastSyncedAt
            )
        }
    }
}
