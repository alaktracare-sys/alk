package com.example.soundcapsule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class SoundCapsuleStatsTest {

    private val zone = ZoneId.of("UTC")

    private fun eventAt(
        trackId: String,
        title: String,
        artistId: String,
        artistName: String,
        dateTime: LocalDateTime,
        msPlayed: Long,
        artworkUrl: String? = "http://server/art/$trackId.jpg",
    ): PlayEvent {
        return PlayEvent(
            trackId = trackId,
            trackTitle = title,
            artistId = artistId,
            artistName = artistName,
            artworkUrl = artworkUrl,
            startedAtMillis = dateTime.atZone(zone).toInstant().toEpochMilli(),
            msPlayed = msPlayed,
        )
    }

    @Test
    fun computeSoundCapsule_enforces30sPlayRuleAndTimeBlocks() {
        val october = YearMonth.of(2026, 10)
        val events = listOf(
            // Prior month play (ignored in October totals)
            eventAt("1", "Track One", "a1", "Artist One", LocalDateTime.of(2026, 9, 20, 12, 0), 180_000L),
            // October plays:
            // Track 1: 2 full plays + 1 short skip (< 30s: counts toward minutes, NOT plays)
            eventAt("1", "Track One", "a1", "Artist One", LocalDateTime.of(2026, 10, 1, 22, 15), 240_000L), // Night
            eventAt("1", "Track One", "a1", "Artist One", LocalDateTime.of(2026, 10, 1, 23, 0), 20_000L),   // < 30s skip
            eventAt("1", "Track One", "a1", "Artist One", LocalDateTime.of(2026, 10, 8, 7, 30), 220_000L),  // Morning
            // Second artist & song in October with 3 plays -> #1 song & #1 artist
            eventAt("2", "Fresh Hit", "a2", "New Artist", LocalDateTime.of(2026, 10, 15, 11, 0), 200_000L), // Day
            eventAt("2", "Fresh Hit", "a2", "New Artist", LocalDateTime.of(2026, 10, 15, 16, 0), 200_000L), // Evening
            eventAt("2", "Fresh Hit", "a2", "New Artist", LocalDateTime.of(2026, 10, 29, 2, 0), 200_000L),  // Night (02:00)
        )

        val stats = computeSoundCapsule(events = events, month = october, zone = zone)

        // Total ms in October = 240k + 20k + 220k + 200k + 200k + 200k = 1_080_000 ms = 18 min
        assertEquals(18, stats.totalMinutes)
        assertEquals(31, stats.dailyMinutes.size)

        // Top artist by msPlayed: a2 (600_000 ms = 10 min, 3 plays) vs a1 (480_000 ms = 8 min, 2 plays)
        assertEquals(2, stats.topArtists.size)
        assertEquals("New Artist", stats.topArtists[0].name)
        assertEquals(10, stats.topArtists[0].minutes)
        assertEquals(3, stats.topArtists[0].plays)
        assertEquals(2, stats.topArtists[1].plays) // 20s skip excluded from plays

        // Top song by plays: Fresh Hit (3 plays) vs Track One (2 plays)
        assertEquals("Fresh Hit", stats.topSongs[0].title)
        assertEquals(3, stats.topSongs[0].plays)
        assertEquals("Track One", stats.topSongs[1].title)
        assertEquals(2, stats.topSongs[1].plays)

        // Time-of-day blocks: Night had 240k + 20k + 200k = 460k (~8 min), Morning 220k (~4 min), Day 200k (~3 min), Evening 200k (~3 min)
        val byBlock = stats.timeBlocks.associate { it.block to it.minutes }
        assertEquals(8, byBlock[TimeBlock.NIGHT])
        assertEquals(4, byBlock[TimeBlock.MORNING])
        assertEquals(3, byBlock[TimeBlock.DAY])
        assertEquals(3, byBlock[TimeBlock.EVENING])
    }

    @Test
    fun formatYAxisMinutes_formatsRegularAndThousandsWithFirstTwoDigits() {
        assertEquals("0", formatYAxisMinutes(0))
        assertEquals("59", formatYAxisMinutes(59))
        assertEquals("999", formatYAxisMinutes(999))
        assertEquals("1.0k", formatYAxisMinutes(1_000))
        assertEquals("1.2k", formatYAxisMinutes(1_248))
        assertEquals("12k", formatYAxisMinutes(12_480))
    }

    @Test
    fun monthCapsuleAccumulator_incrementalLiveSessionMatchesFullRecomputeAndSnapshotRoundtrips() {
        val november = YearMonth.of(2026, 11)
        val started1 = LocalDateTime.of(2026, 11, 3, 9, 10).atZone(zone).toInstant().toEpochMilli()
        val started2 = LocalDateTime.of(2026, 11, 3, 18, 30).atZone(zone).toInstant().toEpochMilli()

        val acc = MonthCapsuleAccumulator(month = november, zone = zone)

        // Live session starts at 15s (< 30s play threshold)
        acc.upsertSession("10", "Morning Groove", "5", "Aurora", "http://art/10.jpg", "http://art/a5.jpg", started1, 15_000L)
        assertEquals(0, acc.buildStats().topSongs.first().plays)

        // Live session crosses 30s threshold and reaches 180s (3 min, 1 play)
        acc.upsertSession("10", "Morning Groove", "5", "Aurora", "http://art/10.jpg", "http://art/a5.jpg", started1, 180_000L)
        // Second track played for 240s (4 min, 1 play)
        acc.upsertSession("20", "Sunset Drive", "8", "Nova", "http://art/20.jpg", "http://art/a8.jpg", started2, 240_000L)

        val incremental = acc.buildStats()
        val batchComputed = computeSoundCapsule(
            events = listOf(
                PlayEvent("10", "Morning Groove", "5", "Aurora", "http://art/10.jpg", started1, 180_000L, "http://art/a5.jpg"),
                PlayEvent("20", "Sunset Drive", "8", "Nova", "http://art/20.jpg", started2, 240_000L, "http://art/a8.jpg"),
            ),
            month = november,
            zone = zone,
        )

        assertEquals(batchComputed, incremental)
        assertEquals(7, incremental.totalMinutes)
        assertEquals(1, incremental.topSongs[0].plays)

        // Cold-boot ~1KB snapshot roundtrip preserves exact stats (single & multi-month list)
        val encoded = SoundCapsuleSnapshotCodec.encode(incremental)
        val decoded = SoundCapsuleSnapshotCodec.decode(encoded)
        assertEquals(incremental, decoded)

        val octoberSample = sampleSoundCapsule()
        val encodedList = SoundCapsuleSnapshotCodec.encodeList(listOf(incremental, octoberSample))
        val decodedList = SoundCapsuleSnapshotCodec.decodeList(encodedList)
        assertEquals(2, decodedList.size)
        assertEquals(incremental, decodedList[0])
        assertEquals(octoberSample, decodedList[1])
    }
}

