package com.example.model

import com.example.soundcapsule.PlayEvent
import com.example.soundcapsule.computeSoundCapsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.YearMonth

class ArtistCreditsTest {

    @Test
    fun `encode and decode multi-artist credits preserves roles positions and verification`() {
        val credits = listOf(
            SongArtistCredit(
                id = 11,
                name = "Karan Aujla",
                role = "primary",
                position = 0,
                avatarUrl = "http://localhost:8000/artist-media/karan.jpg",
                isVerified = true
            ),
            SongArtistCredit(
                id = 27,
                name = "Ikky",
                role = "featured",
                position = 1,
                avatarUrl = "http://localhost:8000/artist-media/ikky.jpg",
                isVerified = false
            )
        )

        val encoded = ArtistCreditJsonCodec.encode(credits)
        assertNotNull(encoded)

        val decoded = ArtistCreditJsonCodec.decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals(11, decoded[0].id)
        assertEquals("Karan Aujla", decoded[0].name)
        assertEquals("primary", decoded[0].role)
        assertTrue(decoded[0].isVerified)

        assertEquals(27, decoded[1].id)
        assertEquals("Ikky", decoded[1].name)
        assertEquals("featured", decoded[1].role)
        assertFalse(decoded[1].isVerified)
    }

    @Test
    fun `song helper properties distinguish primary artist and multi-artist collaborations`() {
        val collabSong = Song(
            id = 500,
            title = "Softly",
            artist = "Karan Aujla, Ikky",
            audioUrl = "http://localhost:8000/songs/500/stream",
            coverUrl = "http://localhost:8000/covers/500.jpg",
            primaryArtistId = 11,
            artists = listOf(
                SongArtistCredit(id = 27, name = "Ikky", role = "featured", position = 1),
                SongArtistCredit(id = 11, name = "Karan Aujla", role = "primary", position = 0, isVerified = true)
            )
        )

        assertTrue(collabSong.hasMultipleArtists)
        assertEquals(11, collabSong.primaryArtistCredit?.id)
        assertEquals("Karan Aujla", collabSong.primaryArtistName)

        val soloSong = Song(
            id = 501,
            title = "Winning Speech",
            artist = "Karan Aujla",
            audioUrl = "http://localhost:8000/songs/501/stream",
            coverUrl = "http://localhost:8000/covers/501.jpg",
            primaryArtistId = 11,
            artists = listOf(
                SongArtistCredit(id = 11, name = "Karan Aujla", role = "primary", position = 0)
            )
        )
        assertFalse(soloSong.hasMultipleArtists)
        assertEquals("Karan Aujla", soloSong.primaryArtistName)
    }

    @Test
    fun `sound capsule prefers dedicated artist avatar over track cover url`() {
        val zone = ZoneId.of("UTC")
        val ts = ZonedDateTime.of(2026, 11, 5, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val events = listOf(
            PlayEvent(
                trackId = "500",
                trackTitle = "Softly",
                artistId = "11",
                artistName = "Karan Aujla",
                artworkUrl = "http://localhost:8000/covers/500.jpg",
                startedAtMillis = ts,
                msPlayed = 180_000L,
                artistArtworkUrl = "http://localhost:8000/artist-media/karan_avatar.jpg"
            )
        )

        val stats = computeSoundCapsule(events, YearMonth.of(2026, 11), zone)
        assertEquals(1, stats.topArtists.size)
        assertEquals("11", stats.topArtists[0].artistId)
        assertEquals("http://localhost:8000/artist-media/karan_avatar.jpg", stats.topArtists[0].artworkUrl)
        assertEquals("http://localhost:8000/covers/500.jpg", stats.topSongs[0].artworkUrl)
    }
}
