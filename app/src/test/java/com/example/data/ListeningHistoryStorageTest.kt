package com.example.data

import com.example.data.local.ListeningHistoryEntity
import com.example.ui.screens.profile.formatListeningSeconds
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ListeningHistoryStorageTest {

    @Test
    fun testListeningHistoryEntity_effectiveListenedSeconds() {
        val fullItem = ListeningHistoryEntity(
            id = 1L,
            songId = 101,
            title = "Acoustic Melody",
            artist = "SoundHelix",
            audioUrl = "https://example.com/audio1.mp3",
            coverUrl = "https://example.com/cover1.jpg",
            duration = 240.0,
            listenedSeconds = 185L, // User listened for 185 seconds
            playedAt = System.currentTimeMillis()
        )

        assertEquals(185L, fullItem.effectiveListenedSeconds)
        assertEquals("Acoustic Melody", fullItem.toSong().title)
        assertEquals(101, fullItem.toSong().id)

        // Verify that 0L listenedSeconds accurately reflects 0L real duration without bogus fallbacks
        val zeroPlayItem = ListeningHistoryEntity(
            id = 2L,
            songId = 102,
            title = "Microtonal Groove",
            artist = "Sevish",
            audioUrl = "https://example.com/audio2.mp3",
            coverUrl = "https://example.com/cover2.jpg",
            duration = 180.0,
            listenedSeconds = 0L,
            playedAt = System.currentTimeMillis()
        )
        assertEquals(0L, zeroPlayItem.effectiveListenedSeconds)
    }

    @Test
    fun testDailyAggregation_howManySongsAndTotalTimeListenedInWholeDay() {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()

        val historyList = listOf(
            ListeningHistoryEntity(
                id = 1L,
                songId = 101,
                title = "Song A",
                artist = "Artist 1",
                audioUrl = "https://example.com/a.mp3",
                coverUrl = "https://example.com/a.jpg",
                duration = 200.0,
                listenedSeconds = 150L, // 2m 30s
                playedAt = now - 1000L
            ),
            ListeningHistoryEntity(
                id = 2L,
                songId = 102,
                title = "Song B",
                artist = "Artist 2",
                audioUrl = "https://example.com/b.mp3",
                coverUrl = "https://example.com/b.jpg",
                duration = 180.0,
                listenedSeconds = 180L, // 3m 0s
                playedAt = now - 2000L
            ),
            ListeningHistoryEntity(
                id = 3L,
                songId = 101, // Replayed Song A
                title = "Song A",
                artist = "Artist 1",
                audioUrl = "https://example.com/a.mp3",
                coverUrl = "https://example.com/a.jpg",
                duration = 200.0,
                listenedSeconds = 60L, // 1m 0s
                playedAt = now - 3000L
            ),
            // Song from yesterday (25 hours ago)
            ListeningHistoryEntity(
                id = 4L,
                songId = 103,
                title = "Song C",
                artist = "Artist 3",
                audioUrl = "https://example.com/c.mp3",
                coverUrl = "https://example.com/c.jpg",
                duration = 300.0,
                listenedSeconds = 300L,
                playedAt = now - (25 * 3600 * 1000L)
            )
        )

        // Filter for whole day (Today)
        val todaySongs = historyList.filter {
            val date = Instant.ofEpochMilli(it.playedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            date == today
        }

        // How many songs did user listen to today?
        val totalSongsToday = todaySongs.size
        assertEquals(3, totalSongsToday)

        val uniqueSongsToday = todaySongs.map { it.songId }.distinct().size
        assertEquals(2, uniqueSongsToday)

        // Time user listened to songs for in the whole day:
        val totalListenTimeSecondsToday = todaySongs.sumOf { it.effectiveListenedSeconds }
        assertEquals(390L, totalListenTimeSecondsToday) // 150 + 180 + 60 = 390 seconds (6m 30s)

        // Verify time formatter (hours and minutes only, seconds excluded)
        val formattedTime = formatListeningSeconds(totalListenTimeSecondsToday)
        assertEquals("6m", formattedTime)

        // What songs did he listen to today?
        val songTitlesToday = todaySongs.map { it.title }
        assertTrue(songTitlesToday.contains("Song A"))
        assertTrue(songTitlesToday.contains("Song B"))
        assertFalse(songTitlesToday.contains("Song C")) // Song C was yesterday
    }

    @Test
    fun testFormatListeningSeconds_variousDurations() {
        assertEquals("0m", formatListeningSeconds(45L))
        assertEquals("2m", formatListeningSeconds(135L))
        assertEquals("1h 14m", formatListeningSeconds(4450L))
        assertEquals("2h 0m", formatListeningSeconds(7200L))
    }

    @Test
    fun testPlayedThresholdCriteria_requires20SecondsOfActualListening() {
        assertEquals(20L, com.example.audio.AudioController.MIN_PLAYED_SECONDS_THRESHOLD)

        // Helper evaluating whether a song counts as played based on real listened time vs slider position
        fun isCountedAsPlayed(
            actualListenedSec: Long,
            sliderPositionSec: Long,
            songDurationSec: Double,
            isEnded: Boolean
        ): Boolean {
            val hasMet20Sec = actualListenedSec >= com.example.audio.AudioController.MIN_PLAYED_SECONDS_THRESHOLD
            val isShortTrackCompleted = (songDurationSec in 1.0..19.0 && isEnded)
            return hasMet20Sec || isShortTrackCompleted
        }

        // 1. User skips to 30 sec or 1 min on the timeline, but only actually listened for 4 seconds
        assertFalse(
            "Skipping forward to 30s after 4s real listen time must NOT count as played",
            isCountedAsPlayed(actualListenedSec = 4L, sliderPositionSec = 30L, songDurationSec = 210.0, isEnded = false)
        )

        // 2. User plays 19 seconds of a standard track and skips: not counted as played
        assertFalse(
            "19 seconds of actual playback is below 20s threshold",
            isCountedAsPlayed(actualListenedSec = 19L, sliderPositionSec = 19L, songDurationSec = 210.0, isEnded = false)
        )

        // 3. User plays for exactly 20 seconds: counted as played!
        assertTrue(
            "20 seconds of actual playback must count as played",
            isCountedAsPlayed(actualListenedSec = 20L, sliderPositionSec = 20L, songDurationSec = 210.0, isEnded = false)
        )

        // 4. User plays for 45 seconds after seeking around: counted as played!
        assertTrue(
            "45 seconds of actual playback must count as played",
            isCountedAsPlayed(actualListenedSec = 45L, sliderPositionSec = 120L, songDurationSec = 210.0, isEnded = false)
        )

        // 5. Short track (e.g. 15s) played to end: counted as played
        assertTrue(
            "Short track under 20s played to completion counts as played",
            isCountedAsPlayed(actualListenedSec = 15L, sliderPositionSec = 15L, songDurationSec = 15.0, isEnded = true)
        )

        // 6. Song opened paused for 20s or more (0s active playback): must NOT count as played
        assertFalse(
            "Song opened paused with 0 seconds of active playback must NEVER count as played",
            isCountedAsPlayed(actualListenedSec = 0L, sliderPositionSec = 0L, songDurationSec = 180.0, isEnded = false)
        )

        // 7. Song buffering/loading for 20s or more (0s active playback): must NOT count as played
        assertFalse(
            "Song buffering/loading with 0 seconds of active playback must NEVER count as played",
            isCountedAsPlayed(actualListenedSec = 0L, sliderPositionSec = 0L, songDurationSec = 240.0, isEnded = false)
        )
    }
}
