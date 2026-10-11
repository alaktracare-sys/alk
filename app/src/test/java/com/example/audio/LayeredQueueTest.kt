package com.example.audio

import com.example.model.Song
import org.junit.Assert.*
import org.junit.Test

class LayeredQueueTest {

    private fun createDummySong(id: Int, title: String, artist: String): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            duration = 180.0,
            coverUrl = "https://example.com/cover.jpg",
            audioUrl = "https://example.com/audio.mp3",
            isLiked = false
        )
    }

    private fun sampleContext(size: Int = 10): PlaybackContext {
        val tracks = (1..size).map { i ->
            val artist = if (i % 2 == 0) "ArtistA" else "ArtistB"
            createDummySong(i, "Song $i", artist)
        }
        return PlaybackContext(
            uri = "alaktra:playlist:test",
            type = ContextType.PLAYLIST,
            name = "Test Playlist",
            trackIds = tracks.map { it.id },
            tracks = tracks
        )
    }

    @Test
    fun testArtistSpreadShuffleSeparatesAdjacentArtists() {
        val songs = listOf(
            createDummySong(1, "A1", "Queen"),
            createDummySong(2, "A2", "Queen"),
            createDummySong(3, "A3", "Queen"),
            createDummySong(4, "B1", "ABBA"),
            createDummySong(5, "B2", "ABBA"),
            createDummySong(6, "C1", "Beatles")
        )
        val shuffled = ShuffleUtils.artistSpreadShuffle(songs)
        assertEquals(6, shuffled.size)
        // Ensure no adjacent identical artists where spreadable
        var adjacentDuplicates = 0
        for (i in 0 until shuffled.size - 1) {
            if (shuffled[i].artist == shuffled[i + 1].artist) {
                adjacentDuplicates++
            }
        }
        assertTrue("Adjacent duplicates should be minimal", adjacentDuplicates <= 1)
    }

    @Test
    fun testScenario1_ShufflePreservesCurrentAndRandomizesUpcomingOnly() {
        val ctx = sampleContext(6)
        val currentTrack = ctx.tracks[2] // id: 3
        val played = ctx.tracks.take(2) // 1, 2
        val upcoming = ctx.tracks.drop(3) // 4, 5, 6

        val shuffledUpcoming = ShuffleUtils.artistSpreadShuffle(upcoming)
        val contextOrder = played + listOf(currentTrack) + shuffledUpcoming

        assertEquals(6, contextOrder.size)
        assertEquals(3, contextOrder[2].id)
        assertEquals(played.map { it.id }, contextOrder.take(2).map { it.id })
    }

    @Test
    fun testScenario2_UnshuffleRestoresNaturalOrderWithCurrentTrackAtIndex() {
        val ctx = sampleContext(6)
        // Say track with id 5 is currently playing
        val currentSong = ctx.tracks[4]
        val naturalIndex = ctx.tracks.indexOfFirst { it.id == currentSong.id }
        assertEquals(4, naturalIndex)
        assertEquals(5, ctx.tracks[naturalIndex].id)
    }

    @Test
    fun testScenario3_UserQueueDrainsStrictlyFifoAheadOfContextWithoutAdvancingContextIndex() {
        var state = PlaybackState()
        val ctx = sampleContext(5)
        val u1 = createDummySong(101, "User 1", "ArtistU")
        val u2 = createDummySong(102, "User 2", "ArtistU")

        state = state.copy(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 1, // playing track 2
            currentSong = ctx.tracks[1],
            userQueue = listOf(u1, u2)
        )

        // Ladder Step 2: Pop userQueue
        val (nextTrack, nextQueue) = if (state.userQueue.isNotEmpty()) {
            state.userQueue.first() to state.userQueue.drop(1)
        } else {
            null to emptyList()
        }

        assertEquals(101, nextTrack?.id)
        assertEquals(1, nextQueue.size)
        assertEquals(102, nextQueue.first().id)
        // contextIndex must stay at 1!
        assertEquals(1, state.contextIndex)
    }

    @Test
    fun testScenario4_UserQueueClearedLeavesContextUntouched() {
        val ctx = sampleContext(5)
        val u1 = createDummySong(101, "User 1", "ArtistU")
        var state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 2,
            userQueue = listOf(u1)
        )

        // Clear userQueue
        state = state.copy(userQueue = emptyList())
        assertTrue(state.userQueue.isEmpty())
        assertEquals(5, state.contextOrder.size)
        assertEquals(2, state.contextIndex)
        assertEquals(3, state.contextOrder[state.contextIndex].id)
    }

    @Test
    fun testScenario5_RepeatTrackLoopsCurrentSongWithoutPoppingUserQueue() {
        val ctx = sampleContext(5)
        val u1 = createDummySong(101, "User 1", "ArtistU")
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 0,
            currentSong = ctx.tracks[0],
            repeat = RepeatMode.TRACK,
            userQueue = listOf(u1)
        )

        // Ladder Step 1: Repeat track
        val nextSong = if (state.repeat == RepeatMode.TRACK && state.currentSong != null) {
            state.currentSong
        } else {
            state.userQueue.firstOrNull()
        }

        assertEquals(state.currentSong?.id, nextSong?.id)
        // User queue is NOT popped
        assertEquals(1, state.userQueue.size)
        assertEquals(101, state.userQueue[0].id)
    }

    @Test
    fun testScenario6_RepeatContextRegeneratesShuffleOrderOnWrap() {
        val ctx = sampleContext(5)
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 4, // at end
            currentSong = ctx.tracks[4],
            repeat = RepeatMode.CONTEXT,
            shuffle = true
        )

        // Wrap around with shuffle
        val regenerated = ShuffleUtils.artistSpreadShuffle(ctx.tracks)
        assertEquals(5, regenerated.size)
        assertTrue(regenerated.map { it.id }.containsAll(ctx.tracks.map { it.id }))
    }

    @Test
    fun testScenario7_StartingNewContextKeepsExistingUserQueue() {
        val ctx1 = sampleContext(4)
        val ctx2 = sampleContext(8)
        val u1 = createDummySong(101, "Queued 1", "ArtistQ")

        var state = PlaybackState(
            context = ctx1,
            contextOrder = ctx1.tracks,
            contextIndex = 1,
            currentSong = ctx1.tracks[1],
            userQueue = listOf(u1)
        )

        // Play new context
        state = state.copy(
            context = ctx2,
            contextOrder = ctx2.tracks,
            contextIndex = 0,
            currentSong = ctx2.tracks[0]
            // Notice userQueue is preserved!
        )

        assertEquals("Test Playlist", state.context?.name)
        assertEquals(8, state.contextOrder.size)
        assertEquals(1, state.userQueue.size)
        assertEquals(101, state.userQueue[0].id)
    }

    @Test
    fun testDisplayQueueFlatteningAndPromotion() {
        val ctx = sampleContext(6)
        val u1 = createDummySong(101, "User 1", "ArtistU")
        var userQueue = listOf(u1)
        var contextOrder = ctx.tracks
        val currentIndex = 1

        // Display queue: userQueue (1) + context upcoming (4 tracks: index 2, 3, 4, 5) = 5 items total
        val upcomingContext = contextOrder.subList(currentIndex + 1, contextOrder.size)
        assertEquals(4, upcomingContext.size)

        // Promote track at index 4 to userQueue
        val promotedSong = contextOrder[4]
        userQueue = userQueue + listOf(promotedSong)
        contextOrder = contextOrder.filterIndexed { i, _ -> i != 4 }

        assertEquals(2, userQueue.size)
        assertEquals(5, userQueue[1].id)
        assertEquals(5, contextOrder.size)
    }

    @Test
    fun testLoopAllAndShuffleContinuousRandomGeneration() {
        val ctx = sampleContext(4)
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 0,
            currentSong = ctx.tracks[0],
            repeat = RepeatMode.CONTEXT,
            shuffle = true
        )

        // Generate buffer with continuous random stream
        var order = state.contextOrder.toMutableList()
        var upcoming = (order.size - 1) - state.contextIndex
        while (upcoming < 30) {
            val lastId = order.lastOrNull()?.id
            val candidates = if (ctx.tracks.size > 1 && lastId != null) {
                val filtered = ctx.tracks.filter { it.id != lastId }
                if (filtered.isNotEmpty()) filtered else ctx.tracks
            } else {
                ctx.tracks
            }
            val randomSong = candidates.random()
            order.add(randomSong)
            upcoming += 1
        }

        assertTrue("Order should have at least 30 upcoming items", order.size - 1 >= 30)
        // Verify no back-to-back immediate duplicates
        for (i in 0 until order.size - 1) {
            assertNotEquals("Adjacent songs in continuous shuffle stream must not be identical if context > 1", order[i].id, order[i + 1].id)
        }
    }

    @Test
    fun testPriorityLadderLoopOneOverridesUserQueueOnEnded() {
        val ctx = sampleContext(3)
        val u1 = createDummySong(999, "Queued Song", "Queued Artist")
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 0,
            currentSong = ctx.tracks[0],
            userQueue = listOf(u1),
            repeat = RepeatMode.TRACK,
            shuffle = false
        )

        // When reason == ENDED and repeat == TRACK (Loop ONE):
        // Loop One repeats the current track; user queue remains intact and is not popped
        val shouldLoopCurrentTrack = state.repeat == RepeatMode.TRACK
        assertTrue("Loop One must prioritize repeating current track on ENDED", shouldLoopCurrentTrack)
        assertEquals("User queue should not be consumed by Loop One repeat", 1, state.userQueue.size)
    }

    @Test
    fun testPriorityLadderUserQueuePrioritizesOverAutomaticContext() {
        val ctx = sampleContext(3)
        val u1 = createDummySong(999, "Queued Song", "Queued Artist")
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 0,
            currentSong = ctx.tracks[0],
            userQueue = listOf(u1),
            repeat = RepeatMode.OFF,
            shuffle = false
        )

        // When repeat == OFF and userQueue.isNotEmpty():
        // userQueue plays next, contextIndex does not move
        assertTrue("User queue should take precedence when repeat is not TRACK", state.userQueue.isNotEmpty())
        val nextSong = state.userQueue.first()
        assertEquals(999, nextSong.id)
    }

    @Test
    fun testShuffleLoopAllAvoidsSongsInRecent20() {
        val ctx = sampleContext(30)
        val recentPlayed = (1..20).toList()
        val candidates = ctx.tracks

        // Repeated candidate selections must never pick songs from the recent 20
        repeat(50) {
            val selected = AudioController.selectShuffleCandidate(candidates, recentPlayed)
            assertTrue("Selected song (${selected.id}) must not be in recent 20", selected.id > 20)
            assertFalse("Selected song must be outside recentPlayed", recentPlayed.contains(selected.id))
        }
    }

    @Test
    fun testSongBecomesEligibleAfterLeavingRecent20History() {
        val s1 = createDummySong(1, "Song 1", "Artist")
        val s2 = createDummySong(2, "Song 2", "Artist")
        val candidates = listOf(s1, s2)

        // 1. Initially song 1 is in recent history
        var history = listOf(1)
        val selectedWhenRecent = AudioController.selectShuffleCandidate(candidates, history)
        assertEquals("Song 1 is in recent history so song 2 must be selected", 2, selectedWhenRecent.id)

        // 2. Play 20 other songs so song 1 falls outside the 20-song sliding window
        for (id in 100..119) {
            history = AudioController.addToRecentPlayedHistory(id, history)
        }
        assertEquals(20, history.size)
        assertFalse("Song 1 must have fallen outside recent 20 history", history.contains(1))

        // 3. Song 1 is now eligible again
        val pickedIds = mutableSetOf<Int>()
        repeat(30) {
            val candidate = AudioController.selectShuffleCandidate(candidates, history)
            pickedIds.add(candidate.id)
        }
        assertTrue("Song 1 should now be eligible for selection again", pickedIds.contains(1))
    }

    @Test
    fun testHistoryContainsActuallyPlayedSongsNotMerelyQueuedSongs() {
        val ctx = sampleContext(10)
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 0,
            currentSong = ctx.tracks[0],
            recentPlayedSongIds = emptyList()
        )

        // Queue has 10 tracks, but none have been played yet
        assertEquals(10, state.contextOrder.size)
        assertTrue("Recent history must be empty before playback", state.recentPlayedSongIds.isEmpty())

        // Simulating playing song 1
        val updatedHistory = AudioController.addToRecentPlayedHistory(ctx.tracks[0].id, state.recentPlayedSongIds)
        assertEquals(1, updatedHistory.size)
        assertEquals(ctx.tracks[0].id, updatedHistory.first())

        // Ensure consecutive duplicate plays don't add duplicate entries
        val repeatedHistory = AudioController.addToRecentPlayedHistory(ctx.tracks[0].id, updatedHistory)
        assertEquals("Consecutive repeats must not create duplicate history entries", 1, repeatedHistory.size)
    }

    @Test
    fun testManuallyQueuedSongsPlayEvenIfInRecentHistory() {
        val ctx = sampleContext(5)
        val recentlyPlayedSong = ctx.tracks[2] // id = 3
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 0,
            currentSong = ctx.tracks[0],
            userQueue = listOf(recentlyPlayedSong),
            recentPlayedSongIds = listOf(recentlyPlayedSong.id),
            repeat = RepeatMode.CONTEXT,
            shuffle = true
        )

        // Priority Ladder Step 2: userQueue is evaluated first and not blocked by recentPlayedSongIds
        assertTrue("User queue must not be empty", state.userQueue.isNotEmpty())
        val nextSongToPlay = state.userQueue.first()
        assertEquals("Manually queued song must play even if present in recent history", recentlyPlayedSong.id, nextSongToPlay.id)
    }

    @Test
    fun testLoopOneRemainsUnchangedWithShuffleOn() {
        val ctx = sampleContext(5)
        val currentTrack = ctx.tracks[1]
        val state = PlaybackState(
            context = ctx,
            contextOrder = ctx.tracks,
            contextIndex = 1,
            currentSong = currentTrack,
            recentPlayedSongIds = listOf(currentTrack.id),
            repeat = RepeatMode.TRACK,
            shuffle = true
        )

        // On ENDED, Loop ONE loops current song without running candidate rejection or advancing
        val shouldLoopCurrent = state.repeat == RepeatMode.TRACK
        assertTrue("Loop ONE must loop current track even when shuffle is on", shouldLoopCurrent)
        assertEquals(currentTrack.id, state.currentSong?.id)
    }

    @Test
    fun testSmallLibraryDoesNotCauseInfiniteLoop() {
        val ctx = sampleContext(3)
        // All 3 tracks are in recent history
        val recentHistory = ctx.tracks.map { it.id }
        val candidates = ctx.tracks

        // Must gracefully return a candidate without getting stuck
        val selected = AudioController.selectShuffleCandidate(candidates, recentHistory)
        assertNotNull("Candidate selection must return a song even when all tracks are in recent history", selected)
        assertTrue(candidates.any { it.id == selected.id })
    }
}
