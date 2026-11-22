package com.example.data

import com.example.data.repository.MusicRepository
import com.example.model.Song
import com.example.soundcapsule.ArtistStat
import com.example.soundcapsule.SongStat
import com.example.soundcapsule.SoundCapsuleSnapshotCodec
import com.example.soundcapsule.SoundCapsuleStats
import com.example.soundcapsule.TimeBlock
import com.example.soundcapsule.TimeBlockStat
import com.example.util.NetworkMonitor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.util.Random

class OfflineStabilityTest {

    @After
    fun tearDown() {
        NetworkMonitor.markServerReachable()
    }

    @Test
    fun selectDailyMixSongs_neverViolatesTimSortContractAndAvoidsDuplicates() {
        val sizes = listOf(0, 1, 10, 30, 32, 60, 150)
        for (size in sizes) {
            val catalog = (1..size).map { id ->
                Song(
                    id = id,
                    title = "Track $id",
                    artist = "Artist ${(id % 7) + 1}",
                    audioUrl = "https://api.example.com/songs/$id.mp3",
                    coverUrl = "https://api.example.com/covers/$id.jpg"
                )
            }
            val usageCount = mutableMapOf<Int, Int>()
            repeat(100) { iter ->
                val picked = MusicRepository.selectDailyMixSongs(
                    catalog = catalog,
                    usageCount = usageCount,
                    targetSize = 30,
                    random = Random((size * 1000L + iter))
                )
                assertEquals(minOf(30, size), picked.size)
                assertEquals(picked.size, picked.map { it.id }.toSet().size)
                picked.forEach { song ->
                    usageCount[song.id] = (usageCount[song.id] ?: 0) + 1
                }
            }
        }
    }

    @Test
    fun networkMonitor_unreachableCooldownRespectsUrlSwitchAndClear() {
        NetworkMonitor.updateActiveServerUrl("https://one.alaktra.test")
        assertFalse(NetworkMonitor.isServerTemporarilyUnreachable("https://one.alaktra.test"))

        NetworkMonitor.markServerUnreachable("https://one.alaktra.test", cooldownMs = 30_000L)
        assertTrue(NetworkMonitor.isServerTemporarilyUnreachable("https://one.alaktra.test"))
        assertFalse(NetworkMonitor.isServerTemporarilyUnreachable("https://two.alaktra.test"))

        // Switching the configured active URL clears prior host's unreachable state
        NetworkMonitor.updateActiveServerUrl("https://two.alaktra.test")
        assertFalse(NetworkMonitor.isServerTemporarilyUnreachable("https://two.alaktra.test"))

        NetworkMonitor.markServerUnreachable("https://two.alaktra.test", cooldownMs = 30_000L)
        assertTrue(NetworkMonitor.isServerTemporarilyUnreachable())
        NetworkMonitor.markServerReachable()
        assertFalse(NetworkMonitor.isServerTemporarilyUnreachable())
    }

    @Test
    fun networkMonitor_identifiesGatewayAndCloudflareTunnelStatuses() {
        assertTrue(NetworkMonitor.isUnreachableHttpStatus(502))
        assertTrue(NetworkMonitor.isUnreachableHttpStatus(503))
        assertTrue(NetworkMonitor.isUnreachableHttpStatus(504))
        assertTrue(NetworkMonitor.isUnreachableHttpStatus(521))
        assertTrue(NetworkMonitor.isUnreachableHttpStatus(530))
        assertFalse(NetworkMonitor.isUnreachableHttpStatus(200))
        assertFalse(NetworkMonitor.isUnreachableHttpStatus(404))
        assertFalse(NetworkMonitor.isUnreachableHttpStatus(500))

        NetworkMonitor.updateActiveServerUrl("https://api.alaktracare.in")
        assertTrue(NetworkMonitor.matchesActiveServer("https://api.alaktracare.in/songs/5/stream"))
        assertFalse(NetworkMonitor.matchesActiveServer("https://cdn.otherdomain.example/img.jpg"))
    }

    @Test
    fun soundCapsuleSnapshotCodec_preserves36MonthsWithArtworkAndStats() {
        val baseMonth = YearMonth.of(2026, 11)
        val feed = (0 until 36).map { offset ->
            val ym = baseMonth.minusMonths(offset.toLong())
            SoundCapsuleStats(
                month = ym,
                totalMinutes = 120 + offset * 5,
                dailyMinutes = List(ym.lengthOfMonth()) { dayIdx -> if (dayIdx == 0) 45 else 2 },
                dailyAverage = 4,
                weeklyAverage = 28,
                longestDayMinutes = 45,
                longestDay = 1,
                timeBlocks = listOf(
                    TimeBlockStat(TimeBlock.NIGHT, 20, 17, 1),
                    TimeBlockStat(TimeBlock.MORNING, 30, 25, 1),
                    TimeBlockStat(TimeBlock.DAY, 40, 33, 2),
                    TimeBlockStat(TimeBlock.EVENING, 30, 25, 1),
                ),
                topArtists = listOf(
                    ArtistStat(
                        artistId = "art_$offset",
                        name = "Artist $offset",
                        artworkUrl = "https://cdn.example.com/artists/$offset.jpg",
                        minutes = 60,
                        plays = 15,
                        sharePercent = 50
                    )
                ),
                topSongs = listOf(
                    SongStat(
                        trackId = "trk_$offset",
                        title = "Song $offset",
                        artistName = "Artist $offset",
                        artworkUrl = "https://cdn.example.com/songs/$offset.jpg",
                        plays = 10,
                        minutes = 35
                    )
                )
            )
        }

        val encoded = SoundCapsuleSnapshotCodec.encodeList(feed, maxMonths = 36)
        val decoded = SoundCapsuleSnapshotCodec.decodeList(encoded)

        assertEquals(36, decoded.size)
        assertEquals(baseMonth, decoded.first().month)
        assertEquals(baseMonth.minusMonths(35), decoded.last().month)
        assertEquals("https://cdn.example.com/artists/35.jpg", decoded.last().topArtists.first().artworkUrl)
        assertEquals("https://cdn.example.com/songs/35.jpg", decoded.last().topSongs.first().artworkUrl)
    }
}
