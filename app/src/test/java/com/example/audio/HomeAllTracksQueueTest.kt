package com.example.audio

import com.example.model.Song
import org.junit.Assert.*
import org.junit.Test

class HomeAllTracksQueueTest {

    private fun song(id: Int): Song = Song(
        id = id,
        title = "Track $id",
        artist = "Artist ${(id % 5) + 1}",
        duration = 190.0,
        coverUrl = "https://example.com/$id.jpg",
        audioUrl = "https://example.com/$id.mp3",
        isLiked = false
    )

    private fun catalog(count: Int): List<Song> = (1..count).map { song(it) }

    @Test
    fun `mergeRemainingContextTracks expands sequential Home queue without changing playing track index`() {
        val initialWindow = catalog(12)
        val fullCatalog = catalog(45)
        val initialCtx = PlaybackContext(
            uri = AudioController.HOME_STREAM_URI,
            type = ContextType.PLAYLIST,
            name = "Alaktra Stream",
            trackIds = initialWindow.map { it.id },
            tracks = initialWindow
        )
        val state = PlaybackState(
            context = initialCtx,
            contextOrder = initialWindow,
            contextIndex = 3,
            currentSong = initialWindow[3],
            shuffle = false,
            repeat = RepeatMode.OFF,
            isPlaying = true
        )

        val merged = AudioController.mergeRemainingContextTracks(state, fullCatalog)

        assertEquals(45, merged.context?.tracks?.size)
        assertEquals(45, merged.contextOrder.size)
        assertEquals(3, merged.contextIndex)
        assertEquals(4, merged.currentSong?.id)
        assertEquals((1..45).toList(), merged.contextOrder.map { it.id })
    }

    @Test
    fun `mergeRemainingContextTracks with shuffle ON preserves active track and includes all new tracks`() {
        val initialWindow = catalog(10)
        val fullCatalog = catalog(35)
        val shuffledInitial = listOf(1, 5, 3, 7, 2, 9, 4, 10, 6, 8).map { song(it) }
        val initialCtx = PlaybackContext(
            uri = AudioController.HOME_STREAM_URI,
            type = ContextType.PLAYLIST,
            name = "Alaktra Stream",
            trackIds = initialWindow.map { it.id },
            tracks = initialWindow
        )
        val state = PlaybackState(
            context = initialCtx,
            contextOrder = shuffledInitial,
            contextIndex = 2, // Currently playing id=3
            currentSong = shuffledInitial[2],
            shuffle = true,
            repeat = RepeatMode.CONTEXT,
            isPlaying = true
        )

        val merged = AudioController.mergeRemainingContextTracks(state, fullCatalog)

        assertEquals(35, merged.context?.tracks?.size)
        assertEquals(2, merged.contextIndex)
        assertEquals(3, merged.contextOrder[2].id)
        // Prefix up through current index stays identical
        assertEquals(listOf(1, 5, 3), merged.contextOrder.take(3).map { it.id })
        // All 35 distinct tracks are represented across the hydrated order
        assertEquals((1..35).toSet(), merged.contextOrder.map { it.id }.toSet())
    }

    @Test
    fun `mergeRemainingContextTracks ignores updates if user switched to a different context`() {
        val otherSongs = catalog(5)
        val fullCatalog = catalog(50)
        val state = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:playlist:user_mix",
                type = ContextType.PLAYLIST,
                name = "User Mix",
                trackIds = otherSongs.map { it.id },
                tracks = otherSongs
            ),
            contextOrder = otherSongs,
            contextIndex = 0,
            currentSong = otherSongs[0],
            shuffle = false,
            isPlaying = true
        )

        val result = AudioController.mergeRemainingContextTracks(state, fullCatalog)
        assertEquals(state, result)
    }
}
