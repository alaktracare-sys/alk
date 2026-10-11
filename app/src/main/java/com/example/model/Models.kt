package com.example.model

data class Song(
    val id: Int,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val duration: Double = 0.0,
    val isLiked: Boolean = false,
    val localPath: String? = null,
    val isDownloaded: Boolean = false,
    val isCached: Boolean = false
) {
    fun resolvedPath(): String = localPath ?: audioUrl
}

data class Playlist(
    val id: Int,
    val name: String,
    val description: String = "",
    val songCount: Int = 0,
    val isLikedPlaylist: Boolean = false,
    val isDownloadedPlaylist: Boolean = false,
    val isOfflineBackupPlaylist: Boolean = false
) {
    companion object {
        const val ID_LIKED = -1
        const val ID_DOWNLOADED = -2
        const val ID_OFFLINE_BACKUP = -3
    }
}

data class User(
    val id: Int,
    val username: String,
    val email: String,
    val name: String = "",
    val avatarUrl: String? = null
)

data class OfflineAction(
    val id: String,
    val type: String, // create_playlist, delete_playlist, rename_playlist, add_song, remove_song
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)
