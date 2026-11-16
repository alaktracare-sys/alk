package com.example.soundcapsule

import com.google.gson.Gson
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/** One listening session of one track. Save one every time a track ends, is skipped or playback stops. */
data class PlayEvent(
    val trackId: String,
    val trackTitle: String,
    val artistId: String,
    val artistName: String,
    val artworkUrl: String?,
    val startedAtMillis: Long, // System.currentTimeMillis() when the track started
    val msPlayed: Long,        // how long it was actually played (pauses excluded)
    val artistArtworkUrl: String? = null,
)

/** A track counts as a "play" only after this much listening (same idea as Spotify's 30 s rule). */
const val MIN_MS_FOR_PLAY = 30_000L

enum class TimeBlock(val label: String) {
    NIGHT("Night"),
    MORNING("Morning"),
    DAY("Day"),
    EVENING("Evening");

    companion object {
        fun forHour(hour: Int): TimeBlock = when (hour) {
            in 3..8 -> MORNING
            in 9..14 -> DAY
            in 15..20 -> EVENING
            else -> NIGHT // 21..23 and 0..2
        }
    }
}

data class ArtistStat(
    val artistId: String,
    val name: String,
    val artworkUrl: String?,
    val minutes: Int,
    val plays: Int,
    val sharePercent: Int,
)

data class SongStat(
    val trackId: String,
    val title: String,
    val artistName: String,
    val artworkUrl: String?,
    val plays: Int,
    val minutes: Int,
)

data class TimeBlockStat(val block: TimeBlock, val minutes: Int, val sharePercent: Int, val minutesPerDay: Int)

data class SoundCapsuleStats(
    val month: YearMonth,
    val totalMinutes: Int,
    val dailyMinutes: List<Int>, // index 0 = day 1 of the month
    val dailyAverage: Int,
    val weeklyAverage: Int,
    val longestDayMinutes: Int,
    val longestDay: Int,         // day of month, 0 when there is no listening
    val timeBlocks: List<TimeBlockStat>, // always 4 items: night, morning, day, evening
    val topArtists: List<ArtistStat>,    // up to 5, best first
    val topSongs: List<SongStat>,        // up to 5, best first
)

/**
 * Compact, single-month incremental accumulator (~6-10 KB RAM) that produces identical output
 * to [computeSoundCapsule] in sub-millisecond time without keeping full PlayEvent lists in memory.
 */
class MonthCapsuleAccumulator(
    val month: YearMonth,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val startMs: Long = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs: Long = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private class SessionRecord(
        var msPlayed: Long,
        var artistId: String,
    )

    private class ArtistAgg(
        var name: String,
        var artworkUrl: String?,
        var totalMs: Long = 0L,
        var plays: Int = 0,
    )

    private class SongAgg(
        var title: String,
        var artistName: String,
        var artworkUrl: String?,
        var totalMs: Long = 0L,
        var plays: Int = 0,
    )

    private val dailyMs = LongArray(month.lengthOfMonth())
    private val blockMs = LongArray(TimeBlock.values().size)
    private val sessions = HashMap<String, SessionRecord>(64)
    private val artists = LinkedHashMap<String, ArtistAgg>(32)
    private val songs = LinkedHashMap<String, SongAgg>(32)
    private var totalMs: Long = 0L

    val hasAnyActivity: Boolean
        @Synchronized get() = totalMs > 0L

    @Synchronized
    fun clear() {
        dailyMs.fill(0L)
        blockMs.fill(0L)
        sessions.clear()
        artists.clear()
        songs.clear()
        totalMs = 0L
    }

    @Synchronized
    fun upsertSession(
        trackId: String,
        trackTitle: String,
        artistId: String,
        artistName: String,
        artworkUrl: String?,
        artistArtworkUrl: String?,
        startedAtMillis: Long,
        msPlayed: Long,
    ): Boolean {
        if (startedAtMillis !in startMs until endMs || msPlayed <= 0L) return false

        val cleanArt = artworkUrl?.takeIf { it.isNotBlank() }
        val cleanArtistArt = artistArtworkUrl?.takeIf { it.isNotBlank() } ?: cleanArt
        val sessionKey = "$trackId:$startedAtMillis"
        val existing = sessions[sessionKey]

        if (existing == null) {
            sessions[sessionKey] = SessionRecord(msPlayed = msPlayed, artistId = artistId)
            val zdt = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
            dailyMs[zdt.dayOfMonth - 1] += msPlayed
            blockMs[TimeBlock.forHour(zdt.hour).ordinal] += msPlayed
            totalMs += msPlayed

            val isPlay = if (msPlayed >= MIN_MS_FOR_PLAY) 1 else 0

            val artistBucket = artists.getOrPut(artistId) {
                ArtistAgg(name = artistName, artworkUrl = cleanArtistArt)
            }
            artistBucket.totalMs += msPlayed
            artistBucket.plays += isPlay
            if (artistBucket.artworkUrl == null && cleanArtistArt != null) {
                artistBucket.artworkUrl = cleanArtistArt
            }
            if (artistBucket.name == "Unknown Artist" && artistName.isNotBlank() && artistName != "Unknown Artist") {
                artistBucket.name = artistName
            }

            val songBucket = songs.getOrPut(trackId) {
                SongAgg(title = trackTitle, artistName = artistName, artworkUrl = cleanArt)
            }
            songBucket.totalMs += msPlayed
            songBucket.plays += isPlay
            if (songBucket.artworkUrl == null && cleanArt != null) {
                songBucket.artworkUrl = cleanArt
            }
            if (songBucket.title == "Unknown Song" && trackTitle.isNotBlank() && trackTitle != "Unknown Song") {
                songBucket.title = trackTitle
            }
            return true
        }

        val prevMs = existing.msPlayed
        val mergedMs = maxOf(prevMs, msPlayed)
        val deltaMs = mergedMs - prevMs
        val prevWasPlay = prevMs >= MIN_MS_FOR_PLAY
        val newIsPlay = mergedMs >= MIN_MS_FOR_PLAY
        val playDelta = if (!prevWasPlay && newIsPlay) 1 else 0

        val incomingNumericId = artistId.toIntOrNull() != null
        val targetArtistId = if (incomingNumericId && existing.artistId != artistId) artistId else existing.artistId
        val artistChanged = targetArtistId != existing.artistId

        var metadataChanged = false
        val songBucket = songs[trackId]
        if (songBucket != null && songBucket.artworkUrl == null && cleanArt != null) {
            songBucket.artworkUrl = cleanArt
            metadataChanged = true
        }

        if (deltaMs == 0L && !artistChanged) {
            val artistBucket = artists[targetArtistId]
            if (artistBucket != null && artistBucket.artworkUrl == null && cleanArtistArt != null) {
                artistBucket.artworkUrl = cleanArtistArt
                metadataChanged = true
            }
            return metadataChanged
        }

        existing.msPlayed = mergedMs

        if (deltaMs > 0L) {
            val zdt = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
            dailyMs[zdt.dayOfMonth - 1] += deltaMs
            blockMs[TimeBlock.forHour(zdt.hour).ordinal] += deltaMs
            totalMs += deltaMs
        }

        if (artistChanged) {
            val oldArtist = artists[existing.artistId]
            if (oldArtist != null) {
                oldArtist.totalMs = (oldArtist.totalMs - prevMs).coerceAtLeast(0L)
                if (prevWasPlay) oldArtist.plays = (oldArtist.plays - 1).coerceAtLeast(0)
                if (oldArtist.totalMs == 0L && oldArtist.plays == 0) {
                    artists.remove(existing.artistId)
                }
            }
            existing.artistId = targetArtistId
            val newArtist = artists.getOrPut(targetArtistId) {
                ArtistAgg(name = artistName, artworkUrl = cleanArtistArt)
            }
            newArtist.totalMs += mergedMs
            if (newIsPlay) newArtist.plays += 1
            if (newArtist.artworkUrl == null && cleanArtistArt != null) {
                newArtist.artworkUrl = cleanArtistArt
            }
        } else {
            val artistBucket = artists.getOrPut(targetArtistId) {
                ArtistAgg(name = artistName, artworkUrl = cleanArtistArt)
            }
            artistBucket.totalMs += deltaMs
            artistBucket.plays += playDelta
            if (artistBucket.artworkUrl == null && cleanArtistArt != null) {
                artistBucket.artworkUrl = cleanArtistArt
            }
        }

        val targetSong = songs.getOrPut(trackId) {
            SongAgg(title = trackTitle, artistName = artistName, artworkUrl = cleanArt)
        }
        targetSong.totalMs += deltaMs
        targetSong.plays += playDelta
        return true
    }

    @Synchronized
    fun buildStats(today: LocalDate = LocalDate.now(zone)): SoundCapsuleStats {
        val daysInMonth = dailyMs.size
        fun minutes(ms: Long) = (ms / 60_000.0).roundToInt()
        fun percent(ms: Long) = if (totalMs == 0L) 0 else (ms * 100.0 / totalMs).roundToInt()

        val daysForAverage = if (month == YearMonth.from(today)) today.dayOfMonth else daysInMonth
        val perDayMinutes = totalMs / 60_000.0 / daysForAverage

        var longestIdx = 0
        var longestMs = 0L
        for (i in dailyMs.indices) {
            if (dailyMs[i] > longestMs) {
                longestMs = dailyMs[i]
                longestIdx = i
            }
        }

        val topArtists = artists.entries
            .filter { it.value.totalMs > 0L }
            .map { (id, agg) ->
                agg.totalMs to ArtistStat(
                    artistId = id,
                    name = agg.name,
                    artworkUrl = agg.artworkUrl,
                    minutes = minutes(agg.totalMs),
                    plays = agg.plays,
                    sharePercent = percent(agg.totalMs),
                )
            }
            .sortedByDescending { it.first }
            .take(5)
            .map { it.second }

        val topSongs = songs.entries
            .filter { it.value.totalMs > 0L }
            .map { (id, agg) ->
                SongStat(
                    trackId = id,
                    title = agg.title,
                    artistName = agg.artistName,
                    artworkUrl = agg.artworkUrl,
                    plays = agg.plays,
                    minutes = minutes(agg.totalMs),
                )
            }
            .sortedWith(compareByDescending<SongStat> { it.plays }.thenByDescending { it.minutes })
            .take(5)

        return SoundCapsuleStats(
            month = month,
            totalMinutes = minutes(totalMs),
            dailyMinutes = dailyMs.map { minutes(it) },
            dailyAverage = perDayMinutes.roundToInt(),
            weeklyAverage = (perDayMinutes * 7).roundToInt(),
            longestDayMinutes = minutes(longestMs),
            longestDay = if (longestMs > 0L) longestIdx + 1 else 0,
            timeBlocks = TimeBlock.values().map { b ->
                val ms = blockMs[b.ordinal]
                TimeBlockStat(b, minutes(ms), percent(ms), (ms / 60_000.0 / daysForAverage).roundToInt())
            },
            topArtists = topArtists,
            topSongs = topSongs,
        )
    }
}

/**
 * Lightweight (~1 KB) JSON serializer for persisting the active month's [SoundCapsuleStats]
 * so cold app starts can display accurate Sound Capsule numbers at 0ms before SQLite opens.
 */
object SoundCapsuleSnapshotCodec {
    private val gson = Gson()

    private data class SnapshotPayload(
        val year: Int = 2026,
        val monthValue: Int = 1,
        val totalMinutes: Int = 0,
        val dailyMinutes: List<Int> = emptyList(),
        val dailyAverage: Int = 0,
        val weeklyAverage: Int = 0,
        val longestDayMinutes: Int = 0,
        val longestDay: Int = 0,
        val timeBlocks: List<TimeBlockStat> = emptyList(),
        val topArtists: List<ArtistStat> = emptyList(),
        val topSongs: List<SongStat> = emptyList(),
    )

    private data class SnapshotListPayload(
        val months: List<SnapshotPayload> = emptyList(),
    )

    private fun toPayload(stats: SoundCapsuleStats): SnapshotPayload = SnapshotPayload(
        year = stats.month.year,
        monthValue = stats.month.monthValue,
        totalMinutes = stats.totalMinutes,
        dailyMinutes = stats.dailyMinutes,
        dailyAverage = stats.dailyAverage,
        weeklyAverage = stats.weeklyAverage,
        longestDayMinutes = stats.longestDayMinutes,
        longestDay = stats.longestDay,
        timeBlocks = stats.timeBlocks,
        topArtists = stats.topArtists,
        topSongs = stats.topSongs,
    )

    private fun fromPayload(p: SnapshotPayload?): SoundCapsuleStats? {
        if (p == null || p.year <= 0 || p.monthValue !in 1..12) return null
        val ym = YearMonth.of(p.year, p.monthValue)
        if (p.dailyMinutes.size != ym.lengthOfMonth() || p.timeBlocks.size != TimeBlock.values().size) {
            return null
        }
        return SoundCapsuleStats(
            month = ym,
            totalMinutes = p.totalMinutes,
            dailyMinutes = p.dailyMinutes,
            dailyAverage = p.dailyAverage,
            weeklyAverage = p.weeklyAverage,
            longestDayMinutes = p.longestDayMinutes,
            longestDay = p.longestDay,
            timeBlocks = p.timeBlocks,
            topArtists = p.topArtists,
            topSongs = p.topSongs,
        )
    }

    fun encode(stats: SoundCapsuleStats): String = gson.toJson(toPayload(stats))

    fun decode(raw: String?): SoundCapsuleStats? {
        if (raw.isNullOrBlank()) return null
        return try {
            fromPayload(gson.fromJson(raw, SnapshotPayload::class.java))
        } catch (_: Exception) {
            null
        }
    }

    fun encodeList(list: List<SoundCapsuleStats>, maxMonths: Int = 12): String {
        val capped = list.take(maxMonths).map(::toPayload)
        return gson.toJson(SnapshotListPayload(months = capped))
    }

    fun decodeList(raw: String?): List<SoundCapsuleStats> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val trimmed = raw.trim()
            val listPayload = gson.fromJson(trimmed, SnapshotListPayload::class.java)
            val fromList = listPayload?.months?.mapNotNull(::fromPayload).orEmpty()
            if (fromList.isNotEmpty()) {
                fromList
            } else {
                // Backwards-compatible fallback if single-month snapshot was stored previously
                listOfNotNull(decode(trimmed))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Builds every number the Sound Capsule screens need for [month].
 */
fun computeSoundCapsule(
    events: List<PlayEvent>,
    month: YearMonth,
    zone: ZoneId = ZoneId.systemDefault(),
): SoundCapsuleStats {
    val acc = MonthCapsuleAccumulator(month = month, zone = zone)
    for (e in events) {
        acc.upsertSession(
            trackId = e.trackId,
            trackTitle = e.trackTitle,
            artistId = e.artistId,
            artistName = e.artistName,
            artworkUrl = e.artworkUrl,
            artistArtworkUrl = e.artistArtworkUrl,
            startedAtMillis = e.startedAtMillis,
            msPlayed = e.msPlayed,
        )
    }
    return acc.buildStats(today = LocalDate.now(zone))
}

/** Fake data matching the design mockup, so the screens can be tested before real tracking exists. */
fun sampleSoundCapsule(): SoundCapsuleStats = SoundCapsuleStats(
    month = YearMonth.of(2026, 10),
    totalMinutes = 1248,
    dailyMinutes = listOf(45, 33, 42, 56, 43, 28, 29, 44, 39, 41, 50, 46, 34, 25, 39, 44, 45, 44, 43, 41, 26, 32, 45, 52, 41, 37, 45, 31, 27, 42, 59),
    dailyAverage = 40,
    weeklyAverage = 282,
    longestDayMinutes = 59,
    longestDay = 31,
    timeBlocks = listOf(
        TimeBlockStat(TimeBlock.NIGHT, 215, 17, 7),
        TimeBlockStat(TimeBlock.MORNING, 187, 15, 6),
        TimeBlockStat(TimeBlock.DAY, 421, 34, 14),
        TimeBlockStat(TimeBlock.EVENING, 425, 34, 14),
    ),
    topArtists = listOf(
        ArtistStat("a1", "Artist One", null, 240, 61, 19),
        ArtistStat("a2", "Artist Two", null, 186, 48, 15),
        ArtistStat("a3", "Artist Three", null, 154, 39, 12),
        ArtistStat("a4", "Artist Four", null, 121, 31, 10),
        ArtistStat("a5", "Artist Five", null, 96, 25, 8),
    ),
    topSongs = listOf(
        SongStat("s1", "Track Title One", "Artist One", null, 38, 122),
        SongStat("s2", "Track Title Two", "Artist Two", null, 31, 99),
        SongStat("s3", "Track Title Three", "Artist One", null, 27, 86),
        SongStat("s4", "Track Title Four", "Artist Three", null, 22, 70),
        SongStat("s5", "Track Title Five", "Artist Four", null, 19, 61),
    ),
)

/**
 * Formats Y-axis minute tick values:
 * - Under 1,000: exact integer (e.g., 45 -> "45")
 * - In thousands: uses first 2 digits and "k" (e.g., 1,248 -> "1.2k", 12,480 -> "12k")
 */
fun formatYAxisMinutes(mins: Int): String {
    val safe = mins.coerceAtLeast(0)
    return when {
        safe < 1_000 -> "$safe"
        safe < 10_000 -> "${safe / 1_000}.${(safe % 1_000) / 100}k"
        else -> "${safe.toString().take(2)}k"
    }
}
