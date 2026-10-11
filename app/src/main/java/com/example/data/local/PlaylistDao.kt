package com.example.data.local

import kotlinx.coroutines.flow.Flow

interface PlaylistDao {
    fun getUserPlaylistsFlow(userId: Int): Flow<List<PlaylistEntity>>
    suspend fun getUserPlaylists(userId: Int): List<PlaylistEntity>
    suspend fun getPlaylistById(playlistId: Int): PlaylistEntity?
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)
    suspend fun insertPlaylist(playlist: PlaylistEntity)
    suspend fun deletePlaylist(playlistId: Int)
    suspend fun updatePlaylistName(playlistId: Int, newName: String)

    suspend fun getPlaylistSongs(playlistId: Int): List<SongEntity>
    suspend fun setPlaylistSongs(playlistId: Int, songs: List<SongEntity>)
    suspend fun addSongToPlaylist(playlistId: Int, songId: Int, position: Int)
    suspend fun removeSongFromPlaylist(playlistId: Int, songId: Int)
    suspend fun remapPlaylistId(oldId: Int, newId: Int)
}
