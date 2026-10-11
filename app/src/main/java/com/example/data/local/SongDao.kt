package com.example.data.local

import kotlinx.coroutines.flow.Flow

interface SongDao {
    // Downloaded songs (permanent downloads)
    fun getAllDownloadedSongs(): Flow<List<DownloadedSongEntity>>
    suspend fun getDownloadedSongsList(): List<DownloadedSongEntity>
    suspend fun getDownloadedSong(songId: Int): DownloadedSongEntity?
    suspend fun getLocalPath(songId: Int): String?
    suspend fun insertDownloadedSong(song: DownloadedSongEntity)
    suspend fun deleteDownloadedSong(songId: Int)
    suspend fun deleteAll()

    // Cached songs (catalog metadata & liked songs)
    fun getAllCachedSongsFlow(): Flow<List<SongEntity>>
    suspend fun getAllCachedSongs(): List<SongEntity>
    suspend fun getCachedSong(songId: Int): SongEntity?
    suspend fun insertCachedSongs(songs: List<SongEntity>)
    suspend fun insertCachedSong(song: SongEntity)
    suspend fun updateSongLiked(songId: Int, isLiked: Boolean)
    suspend fun getLikedSongs(): List<SongEntity>
    suspend fun searchCachedSongs(query: String): List<SongEntity>
}
