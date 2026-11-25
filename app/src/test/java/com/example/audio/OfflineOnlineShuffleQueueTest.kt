package com.example.audio

import com.example.model.Song
import org.junit.Assert.*
import org.junit.Test

class OfflineOnlineShuffleQueueTest {

    private fun song(id: Int): Song {
        return Song(
            id = id,
            title = "Song $id",
            artist = "Artist ${(id % 4) + 1}",
            duration = 180.0,
            coverUrl = "https://example.com/$id.jpg",
            audioUrl = "https://example.com/$id.mp3",
            isLiked = false
        )
    }

    private fun catalog(count: Int = 10): List<Song> = (1..count).map { song(it) }

    @Test
    fun `random queue playing before offline is restored intact when internet reconnects`() {
        val naturalTracks = catalog(8) // [1, 2, 3, 4, 5, 6, 7, 8]
        val randomOrder = listOf(7, 2, 8, 1, 5, 3, 6, 4).map { id -> naturalTracks.first { it.id == id } }

        val originalContext = PlaybackContext(
            uri = "alaktra:playlist:alaktra_stream",
            type = ContextType.PLAYLIST,
            name = "Alaktra Stream",
            trackIds = naturalTracks.map { it.id },
            tracks = naturalTracks
        )

        // Snapshot captured when going offline while playing index 1 (Song 2) in random queue
        val snapshot = PreOfflineSnapshot(
            context = originalContext,
            contextOrder = randomOrder,
            resumeIndex = 1,
            preserveExactRandomOrder = true
        )

        // While offline, Offline Backup played cached Song 5 with shuffle still ON
        val offlineSongs = listOf(song(5), song(3))
        val offlineState = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:playlist:offline_backup",
                type = ContextType.PLAYLIST,
                name = "Offline Backup",
                trackIds = offlineSongs.map { it.id },
                tracks = offlineSongs
            ),
            contextOrder = offlineSongs,
            contextIndex = 0,
            currentSong = offlineSongs[0], // Song 5 currently playing
            shuffle = true,
            isPlaying = true
        )

        // Internet comes back online
        val restored = AudioController.restoreStateFromPreOfflineSnapshot(
            currentState = offlineState,
            snapshot = snapshot,
            freshCatalogSongs = naturalTracks
        )

        assertTrue("Shuffle must remain enabled", restored.shuffle)
        assertEquals("Alaktra Stream", restored.context?.name)
        assertEquals("Currently playing song must continue without interruption", 5, restored.currentSong?.id)
        assertEquals(5, restored.contextOrder[restored.contextIndex].id)

        // Upcoming queue after Song 5 must be the exact remaining pre-offline random order [2, 8, 1, 3, 6, 4]
        val upcomingIds = restored.contextOrder.drop(restored.contextIndex + 1).map { it.id }
        assertEquals(listOf(2, 8, 1, 3, 6, 4), upcomingIds)
        assertNotEquals("Queue must NOT revert to sequential order", listOf(1, 2, 3, 4, 6, 7, 8), upcomingIds)
    }

    @Test
    fun `custom playlist random queue is restored on reconnect`() {
        val playlistTracks = listOf(10, 20, 30, 40, 50).map { song(it) }
        val randomOrder = listOf(40, 10, 50, 20, 30).map { id -> playlistTracks.first { it.id == id } }
        val playlistCtx = PlaybackContext(
            uri = "alaktra:playlist:42",
            type = ContextType.PLAYLIST,
            name = "Night Drive",
            trackIds = playlistTracks.map { it.id },
            tracks = playlistTracks
        )

        val snapshot = PreOfflineSnapshot(
            context = playlistCtx,
            contextOrder = randomOrder,
            resumeIndex = 0,
            preserveExactRandomOrder = true
        )

        // Offline backup played cached Song 99 (outside playlist)
        val offlineSong = song(99)
        val offlineState = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:playlist:offline_backup",
                type = ContextType.PLAYLIST,
                name = "Offline Backup",
                trackIds = listOf(99),
                tracks = listOf(offlineSong)
            ),
            contextOrder = listOf(offlineSong),
            contextIndex = 0,
            currentSong = offlineSong,
            shuffle = true
        )

        val restored = AudioController.restoreStateFromPreOfflineSnapshot(
            currentState = offlineState,
            snapshot = snapshot
        )

        assertTrue(restored.shuffle)
        assertEquals("alaktra:playlist:42", restored.context?.uri)
        assertEquals("Night Drive", restored.context?.name)
        assertEquals(0, restored.contextIndex)
        assertEquals(99, restored.contextOrder[0].id)
        assertEquals(
            "Exact random playlist order must follow current offline song",
            listOf(40, 10, 50, 20, 30),
            restored.contextOrder.drop(1).map { it.id }
        )
    }

    @Test
    fun `turning shuffle off while offline keeps queue sequential when internet reconnects`() {
        val naturalTracks = catalog(6) // [1, 2, 3, 4, 5, 6]
        val randomOrder = listOf(6, 3, 1, 5, 2, 4).map { id -> naturalTracks.first { it.id == id } }
        val originalContext = PlaybackContext(
            uri = "alaktra:playlist:alaktra_stream",
            type = ContextType.PLAYLIST,
            name = "Alaktra Stream",
            trackIds = naturalTracks.map { it.id },
            tracks = naturalTracks
        )

        // User explicitly toggled shuffle OFF while offline -> preserveExactRandomOrder = false, shuffle = false
        val snapshotAfterOfflineUnshuffle = PreOfflineSnapshot(
            context = originalContext,
            contextOrder = randomOrder,
            resumeIndex = 0,
            preserveExactRandomOrder = false
        )

        val currentOfflineSong = naturalTracks[2] // Song 3
        val offlineState = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:playlist:offline_backup",
                type = ContextType.PLAYLIST,
                name = "Offline Backup",
                trackIds = listOf(3, 5),
                tracks = listOf(naturalTracks[2], naturalTracks[4])
            ),
            contextOrder = listOf(naturalTracks[2], naturalTracks[4]),
            contextIndex = 0,
            currentSong = currentOfflineSong,
            shuffle = false
        )

        val restored = AudioController.restoreStateFromPreOfflineSnapshot(
            currentState = offlineState,
            snapshot = snapshotAfterOfflineUnshuffle,
            freshCatalogSongs = naturalTracks
        )

        assertFalse("Shuffle must remain OFF because user turned it off offline", restored.shuffle)
        assertEquals("Queue must be natural sequential order", listOf(1, 2, 3, 4, 5, 6), restored.contextOrder.map { it.id })
        assertEquals("Index must anchor at Song 3 in sequential order", 2, restored.contextIndex)
    }

    @Test
    fun `turning shuffle on while offline keeps queue shuffled when internet reconnects`() {
        val naturalTracks = catalog(12)
        val originalContext = PlaybackContext(
            uri = "alaktra:playlist:alaktra_stream",
            type = ContextType.PLAYLIST,
            name = "Alaktra Stream",
            trackIds = naturalTracks.map { it.id },
            tracks = naturalTracks
        )

        // Originally sequential before offline
        val snapshot = PreOfflineSnapshot(
            context = originalContext,
            contextOrder = naturalTracks,
            resumeIndex = 0,
            preserveExactRandomOrder = false
        )

        // User turned shuffle ON while offline
        val offlineState = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:playlist:offline_backup",
                type = ContextType.PLAYLIST,
                name = "Offline Backup",
                trackIds = listOf(4),
                tracks = listOf(naturalTracks[3])
            ),
            contextOrder = listOf(naturalTracks[3]),
            contextIndex = 0,
            currentSong = naturalTracks[3], // Song 4
            shuffle = true
        )

        val restored = AudioController.restoreStateFromPreOfflineSnapshot(
            currentState = offlineState,
            snapshot = snapshot,
            freshCatalogSongs = naturalTracks
        )

        assertTrue(restored.shuffle)
        assertEquals(0, restored.contextIndex)
        assertEquals(4, restored.contextOrder[0].id)
        assertEquals(12, restored.contextOrder.size)
        val naturalWithout4 = naturalTracks.filter { it.id != 4 }.map { it.id }
        assertEquals(naturalWithout4.toSet(), restored.contextOrder.drop(1).map { it.id }.toSet())
    }

    @Test
    fun `skipping uncached tracks offline in shuffle mode relocates playable track without losing skipped random tracks`() {
        val tracks = catalog(6)
        // Random queue: [5, 2, 6, 1, 4, 3]; Song 5 (idx 0) finished, Song 2 & 6 uncached, Song 4 (idx 4) cached
        val randomOrder = listOf(5, 2, 6, 1, 4, 3).map { id -> tracks.first { it.id == id } }
        val state = PlaybackState(
            contextOrder = randomOrder,
            contextIndex = 0,
            currentSong = randomOrder[0],
            shuffle = true
        )

        val relocated = AudioController.relocateOfflinePlayableInShuffleOrder(
            state = state,
            desiredSlotIndex = 1,
            playableIndex = 4 // Song 4
        )

        assertEquals(1, relocated.contextIndex)
        assertEquals(4, relocated.contextOrder[1].id)
        assertEquals(
            "Uncached random tracks [2, 6, 1] must remain upcoming after Song 4 for when internet returns",
            listOf(5, 4, 2, 6, 1, 3),
            relocated.contextOrder.map { it.id }
        )
    }

    @Test
    fun `upgradeToStreamState preserves shuffle order instead of resetting to sequential songs`() {
        val catalogSongs = catalog(10)
        val savedRandomIds = listOf(8, 3, 10, 1, 6, 2, 9, 4, 7, 5)
        val savedRandomOrder = savedRandomIds.map { id -> catalogSongs.first { it.id == id } }

        val restoredSessionState = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:restored",
                type = ContextType.PLAYLIST,
                name = "Last Session",
                trackIds = savedRandomIds,
                tracks = savedRandomOrder
            ),
            contextOrder = savedRandomOrder,
            contextIndex = 2, // Song 10
            currentSong = savedRandomOrder[2],
            shuffle = true
        )

        val upgraded = AudioController.upgradeToStreamState(restoredSessionState, catalogSongs)

        assertTrue(upgraded.shuffle)
        assertEquals("Alaktra Stream", upgraded.context?.name)
        assertEquals(savedRandomIds, upgraded.contextOrder.map { it.id })
        assertEquals(2, upgraded.contextIndex)
        assertEquals(10, upgraded.contextOrder[upgraded.contextIndex].id)
    }

    @Test
    fun `computeUpdatedNearbyPinsState preserves existing random upcoming order when shuffle is enabled`() {
        val pinSongs = catalog(6)
        val shuffledOrder = listOf(3, 6, 1, 5, 2, 4).map { id -> pinSongs.first { it.id == id } }
        val state = PlaybackState(
            context = PlaybackContext(
                uri = "alaktra:playlist:nearby_pins",
                type = ContextType.PLAYLIST,
                name = "Nearby Pins",
                trackIds = pinSongs.map { it.id },
                tracks = pinSongs
            ),
            contextOrder = shuffledOrder,
            contextIndex = 0,
            currentSong = shuffledOrder[0], // Song 3
            shuffle = true
        )

        val updated = AudioController.computeUpdatedNearbyPinsState(state, pinSongs)
        assertTrue(updated.shuffle)
        assertEquals(listOf(3, 6, 1, 5, 2, 4), updated.contextOrder.map { it.id })
    }
}
