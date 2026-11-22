package com.example.audio

import com.example.model.Song
import com.example.ui.components.isSongPlayableInUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSeamlessSwitchTest {

    private fun song(
        id: Int,
        isDownloaded: Boolean = false,
        isCached: Boolean = false,
        localPath: String? = null
    ) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist $id",
        duration = 180.0,
        coverUrl = "https://example.com/$id.jpg",
        audioUrl = "https://example.com/$id.mp3",
        isDownloaded = isDownloaded,
        isCached = isCached,
        localPath = localPath
    )

    @Test
    fun `ui never greys out any song when user is online`() {
        val uncached = song(1, isDownloaded = false, isCached = false)
        val cached = song(2, isCached = true)
        val downloaded = song(3, isDownloaded = true)

        assertTrue(isSongPlayableInUi(uncached, isOnline = true, offlinePlayableIds = emptySet(), cacheIndexWarmed = true))
        assertTrue(isSongPlayableInUi(cached, isOnline = true, offlinePlayableIds = emptySet(), cacheIndexWarmed = true))
        assertTrue(isSongPlayableInUi(downloaded, isOnline = true, offlinePlayableIds = emptySet(), cacheIndexWarmed = true))
    }

    @Test
    fun `ui greys out only unplayable songs when offline`() {
        val uncached = song(1)
        val cachedLive = song(2)
        val downloadedLive = song(3)
        val staleEvicted = song(4, isCached = true)

        val playableIds = setOf(2, 3)

        assertFalse(isSongPlayableInUi(uncached, isOnline = false, offlinePlayableIds = playableIds, cacheIndexWarmed = true))
        assertTrue(isSongPlayableInUi(cachedLive, isOnline = false, offlinePlayableIds = playableIds, cacheIndexWarmed = true))
        assertTrue(isSongPlayableInUi(downloadedLive, isOnline = false, offlinePlayableIds = playableIds, cacheIndexWarmed = true))
        assertFalse(
            "Stale isCached flag from old Song instance must be greyed out once cache index confirms eviction",
            isSongPlayableInUi(staleEvicted, isOnline = false, offlinePlayableIds = playableIds, cacheIndexWarmed = true)
        )
    }

    @Test
    fun `ui falls back safely to song flags while cache index is warming offline`() {
        val uncached = song(10)
        val cached = song(11, isCached = true)
        val downloaded = song(12, isDownloaded = true, localPath = "/tmp/12.mp3")

        assertFalse(isSongPlayableInUi(uncached, isOnline = false, offlinePlayableIds = emptySet(), cacheIndexWarmed = false))
        assertTrue(isSongPlayableInUi(cached, isOnline = false, offlinePlayableIds = emptySet(), cacheIndexWarmed = false))
        assertTrue(isSongPlayableInUi(downloaded, isOnline = false, offlinePlayableIds = emptySet(), cacheIndexWarmed = false))
    }

    @Test
    fun `findNextPlayableOfflineIndexStatic picks next playable forward and avoids wrap when repeat off`() {
        val order = listOf(
            song(1, isCached = true),   // idx 0 - already played
            song(2, isCached = true),   // idx 1 - current finished track
            song(3, isCached = false),  // idx 2 - uncached
            song(4, isDownloaded = true)// idx 3 - next playable
        )
        val isPlayable: (Song) -> Boolean = { it.isCached || it.isDownloaded }

        // From index 1 advancing forward (fromIndex = 2) skips index 2 (uncached) -> index 3
        val next = AudioController.findNextPlayableOfflineIndexStatic(
            order = order,
            fromIndex = 2,
            currentIndex = 1,
            allowWrap = false,
            isPlayable = isPlayable
        )
        assertEquals(3, next)

        // From index 3 advancing forward (fromIndex = 4) with repeat OFF -> returns -1 so Offline Backup can take over
        val afterLast = AudioController.findNextPlayableOfflineIndexStatic(
            order = order,
            fromIndex = 4,
            currentIndex = 3,
            allowWrap = false,
            isPlayable = isPlayable
        )
        assertEquals(-1, afterLast)

        // With repeat CONTEXT -> wraps cleanly back to index 0
        val wrapped = AudioController.findNextPlayableOfflineIndexStatic(
            order = order,
            fromIndex = 4,
            currentIndex = 3,
            allowWrap = true,
            isPlayable = isPlayable
        )
        assertEquals(0, wrapped)
    }

    @Test
    fun `early offline truncation marker is consumed once for active track`() {
        AudioCache.clearEarlyOfflineTruncation()
        AudioCache.markEarlyOfflineTruncation(42)

        assertFalse(AudioCache.consumeEarlyOfflineTruncation(99))
        assertTrue(AudioCache.consumeEarlyOfflineTruncation(42))
        assertFalse(AudioCache.consumeEarlyOfflineTruncation(42))
    }
}
