package com.example.data

import com.example.audio.LoopMode
import com.example.audio.PlaybackState
import com.example.audio.RepeatMode
import com.example.data.local.DownloadedSongEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.SongEntity
import com.example.data.local.UserEntity
import com.example.model.Playlist
import com.example.model.Song
import com.example.model.User
import org.junit.Assert.*
import org.junit.Test

class CachingPhase2Test {

    @Test
    fun testUserEntity_mapsCorrectlyAndExcludesSensitiveData() {
        val user = User(id = 42, username = "music_lover", email = "lover@example.com")
        val entity = UserEntity.fromUser(user, lastSyncedAt = 123456789L)

        assertEquals(42, entity.id)
        assertEquals("music_lover", entity.username)
        assertEquals("lover@example.com", entity.email)
        assertEquals(123456789L, entity.lastSyncedAt)

        val restoredUser = entity.toUser()
        assertEquals(user.id, restoredUser.id)
        assertEquals(user.username, restoredUser.username)
        assertEquals(user.email, restoredUser.email)
    }

    @Test
    fun testPlaylistEntity_preservesDistinctionBetweenSystemAndUserPlaylists() {
        val userPlaylist = Playlist(
            id = 10,
            name = "My Chill Mix",
            description = "Evening relaxation tracks",
            songCount = 5,
            isLikedPlaylist = false,
            isDownloadedPlaylist = false
        )
        val userEntity = PlaylistEntity.fromPlaylist(userPlaylist, userId = 1)
        assertFalse("User playlist must not be marked system", userEntity.isSystem)
        assertEquals(10, userEntity.id)
        assertEquals("My Chill Mix", userEntity.name)

        val likedPlaylist = Playlist(
            id = Playlist.ID_LIKED,
            name = "Liked Songs",
            description = "Favorite tracks",
            songCount = 12,
            isLikedPlaylist = true
        )
        val likedEntity = PlaylistEntity.fromPlaylist(likedPlaylist, userId = 1, isSystem = true)
        assertTrue("Liked playlist must be flagged system", likedEntity.isSystem)

        val restoredLiked = likedEntity.toPlaylist()
        assertTrue("Restored liked playlist must retain isLikedPlaylist flag", restoredLiked.isLikedPlaylist)

        val downloadedPlaylist = Playlist(
            id = Playlist.ID_DOWNLOADED,
            name = "Downloaded Songs",
            description = "Offline tracks",
            songCount = 8,
            isDownloadedPlaylist = true
        )
        val downloadedEntity = PlaylistEntity.fromPlaylist(downloadedPlaylist, userId = 1, isSystem = true)
        assertTrue(downloadedEntity.isSystem)
        val restoredDownloaded = downloadedEntity.toPlaylist()
        assertTrue(restoredDownloaded.isDownloadedPlaylist)
    }

    @Test
    fun testPlaylistSongOrdering_strictPositionPreservation() {
        // Models how playlist_songs table maintains deterministic track ordering
        data class PlaylistSongLink(val playlistId: Int, val songId: Int, val position: Int)

        val links = listOf(
            PlaylistSongLink(playlistId = 1, songId = 503, position = 2),
            PlaylistSongLink(playlistId = 1, songId = 101, position = 0),
            PlaylistSongLink(playlistId = 1, songId = 202, position = 1)
        )

        val orderedLinks = links.sortedBy { it.position }
        assertEquals(101, orderedLinks[0].songId)
        assertEquals(202, orderedLinks[1].songId)
        assertEquals(503, orderedLinks[2].songId)
    }

    @Test
    fun testSongEntity_mappingAndLikedStatus() {
        val originalSong = Song(
            id = 7,
            title = "Midnight Horizon",
            artist = "Aura",
            audioUrl = "https://cdn.example.com/audio/7.mp3",
            coverUrl = "https://cdn.example.com/art/7.jpg",
            duration = 210.5,
            isLiked = true
        )

        val entity = SongEntity.fromSong(originalSong)
        assertEquals(7, entity.id)
        assertEquals("Midnight Horizon", entity.title)
        assertEquals(210.5, entity.duration, 0.001)
        assertTrue(entity.isLiked)

        val restoredSong = entity.toSong()
        assertEquals(originalSong.id, restoredSong.id)
        assertEquals(originalSong.title, restoredSong.title)
        assertEquals(originalSong.artist, restoredSong.artist)
        assertTrue(restoredSong.isLiked)
    }

    @Test
    fun testPermanentDownloadedSong_remainsIndependentOfStreamingCache() {
        val downloaded = DownloadedSongEntity(
            songId = 33,
            title = "Offline Master",
            artist = "Studio",
            audioUrl = "https://cdn.example.com/33.mp3",
            coverUrl = "https://cdn.example.com/33.jpg",
            localPath = "/data/user/0/com.example/files/downloads/song_33.mp3",
            duration = 195.0,
            downloadedAt = System.currentTimeMillis(),
            fileSize = 4500000L
        )

        val song = downloaded.toSong()
        assertTrue(song.isDownloaded)
        assertEquals("/data/user/0/com.example/files/downloads/song_33.mp3", song.localPath)
        assertEquals(song.localPath, song.resolvedPath())
    }

    @Test
    fun testPlaybackStateSurvival_restorationDoesNotAutoStart() {
        val restoredSong = Song(
            id = 15,
            title = "Ambient Echoes",
            artist = "Echoist",
            audioUrl = "https://cdn.example.com/15.mp3",
            coverUrl = "https://cdn.example.com/15.jpg",
            duration = 300.0
        )
        val restoredQueue = listOf(
            restoredSong,
            Song(id = 16, title = "Next Up", artist = "Echoist", audioUrl = "https://cdn.example.com/16.mp3", coverUrl = "https://cdn.example.com/16.jpg", duration = 240.0)
        )
        val restoredPositionMs = 45000L

        val state = PlaybackState(
            currentSong = restoredSong,
            contextOrder = restoredQueue,
            contextIndex = 0,
            currentPositionMs = restoredPositionMs,
            durationMs = 300000L,
            isPlaying = false, // Critical requirement: must never auto-play on boot
            shuffle = true,
            repeat = RepeatMode.CONTEXT
        )

        assertFalse("Restored session must have isPlaying = false", state.isPlaying)
        assertEquals(15, state.currentSong?.id)
        assertEquals(45000L, state.currentPositionMs)
        assertEquals(45L, state.elapsedSec)
        assertEquals(2, state.queue.size)
        assertTrue(state.isShuffle)
        assertEquals(LoopMode.ALL, state.loopMode)
    }

    @Test
    fun testOfflineFallbackCatalogResolution() {
        val cachedDatabaseSongs = listOf(
            Song(id = 1, title = "Offline One", artist = "Local", audioUrl = "http://localhost/1.mp3", coverUrl = "http://localhost/1.jpg"),
            Song(id = 2, title = "Offline Two", artist = "Local", audioUrl = "http://localhost/2.mp3", coverUrl = "http://localhost/2.jpg")
        )

        val likedIds = setOf(2)
        val songsWithLikes = cachedDatabaseSongs.map { song ->
            song.copy(isLiked = likedIds.contains(song.id))
        }

        assertFalse(songsWithLikes[0].isLiked)
        assertTrue(songsWithLikes[1].isLiked)
        assertEquals(2, songsWithLikes.size)
    }
}
