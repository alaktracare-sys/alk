package com.example.data.fuse

import com.example.model.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FuseCodeCodecTest {

    @Test
    fun `encodeCode and decodeHostUserId roundtrip across small and large user IDs`() {
        val testUserIds = listOf(1, 2, 7, 42, 250, 999, 15_432)
        for (userId in testUserIds) {
            val code = FuseCodeCodec.encodeCode(hostUserId = userId, nonce = 0x4B2F)
            assertTrue("Expected at least 6 chars for $code", code.length >= 6)
            assertEquals(userId, FuseCodeCodec.decodeHostUserId(code))
        }
    }

    @Test
    fun `extractCode handles raw codes, https links, app scheme links, and full share message`() {
        val code = FuseCodeCodec.encodeCode(hostUserId = 12, nonce = 0x1A3C)

        assertEquals(code, FuseCodeCodec.extractCode(code))
        assertEquals(code, FuseCodeCodec.extractCode("  ${code.lowercase()}  "))
        assertEquals(code, FuseCodeCodec.extractCode(FuseCodeCodec.buildShareDeepLink(code)))
        assertEquals(code, FuseCodeCodec.extractCode(FuseCodeCodec.buildAppSchemeLink(code)))
        assertEquals(code, FuseCodeCodec.extractCode("https://alaktra.app/fuse/$code"))

        val shareMessage = FuseCodeCodec.buildShareMessage("Aman", code)
        assertEquals(code, FuseCodeCodec.extractCode(shareMessage))
    }

    @Test
    fun `pending marker encodes 5 minute expiry and detects expiration accurately`() {
        val startMs = 1_700_000_000_000L
        val expiresAtMs = startMs + FuseCodeCodec.FUSE_TTL_MS
        assertEquals(5 * 60 * 1000L, FuseCodeCodec.FUSE_TTL_MS)

        val code = FuseCodeCodec.encodeCode(hostUserId = 5, nonce = 99)
        val markerStr = FuseCodeCodec.buildPendingMarkerName(code, expiresAtMs, "User One")
        assertTrue(FuseCodeCodec.isPendingMarker(markerStr))

        val parsed = FuseCodeCodec.parsePendingMarker(markerStr)
        assertNotNull(parsed)
        assertEquals(code, parsed!!.code)
        assertEquals(expiresAtMs, parsed.expiresAtMs)
        assertEquals("User One", parsed.hostName)

        // Active at 4 min 59 sec
        assertFalse(parsed.isExpired(startMs + 4 * 60 * 1000L + 59_000L))
        // Automatically expired at 5 min
        assertTrue(parsed.isExpired(startMs + 5 * 60 * 1000L))
    }

    @Test
    fun `buildFusePlaylistName formats as user 1 fuse user 2`() {
        val title = FuseCodeCodec.buildFusePlaylistName("Alex", "Sam")
        assertEquals("Alex fuse Sam", title)
        assertTrue(FuseCodeCodec.isFusePlaylistName(title))
    }

    @Test
    fun `computeCommonFuseSongs selects up to 30 shared songs from liked and normal playlists`() {
        val user1LikedAndNormal = (1..45).map { id ->
            Song(
                id = id,
                title = "Track $id",
                artist = "Artist $id",
                audioUrl = "http://server/audio/$id.mp3",
                coverUrl = "http://server/cover/$id.jpg",
                isLiked = id <= 15
            )
        }

        // User 2 shares songs 10..44 (35 common songs across liked & normal) plus unique songs 100..110
        val user2LikedAndNormal = ((10..44) + (100..110)).map { id ->
            Song(
                id = id,
                title = "Track $id",
                artist = "Artist $id",
                audioUrl = "http://server/audio/$id.mp3",
                coverUrl = "http://server/cover/$id.jpg",
                isLiked = id >= 35
            )
        }

        val fusePlaylistSongs = FuseCodeCodec.computeCommonFuseSongs(
            user1Songs = user1LikedAndNormal,
            user2Songs = user2LikedAndNormal,
            maxSongs = 30,
            random = Random(42)
        )

        assertEquals(30, fusePlaylistSongs.size)
        assertEquals(30, fusePlaylistSongs.map { it.id }.distinct().size)
        assertTrue(fusePlaylistSongs.all { it.id in 10..44 })
    }
}
