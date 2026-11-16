package com.example.data.fuse

import com.example.data.local.PlaylistEntity
import com.example.model.Playlist
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
    fun `extractCode handles server random 6 char codes, https links, app scheme links, and share messages`() {
        val serverCodes = listOf(
            "2BCD34", // starts with low digit where legacy decodeHostUserId would be null
            "A7K9P2",
            FuseCodeCodec.encodeCode(hostUserId = 12, nonce = 0x1A3C)
        )

        for (code in serverCodes) {
            assertEquals(code, FuseCodeCodec.extractCode(code))
            assertEquals(code, FuseCodeCodec.extractCode("  ${code.lowercase()}  "))
            assertEquals(code, FuseCodeCodec.extractCode("${code.take(3)}-${code.drop(3)}"))
            assertEquals(code, FuseCodeCodec.extractCode(FuseCodeCodec.buildShareDeepLink(code)))
            assertEquals(code, FuseCodeCodec.extractCode(FuseCodeCodec.buildAppSchemeLink(code)))
            assertEquals(code, FuseCodeCodec.extractCode("https://alaktra.app/fuse/$code"))

            val shareMessage = FuseCodeCodec.buildShareMessage("Aman", code)
            assertEquals(code, FuseCodeCodec.extractCode(shareMessage))
        }
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
    fun `buildFusePlaylistName and parseFuseNamesFromTitle preserve user 1 fuse user 2`() {
        val title = FuseCodeCodec.buildFusePlaylistName("Alex", "Sam")
        assertEquals("Alex fuse Sam", title)
        assertTrue(FuseCodeCodec.isFusePlaylistName(title))

        val (u1, u2) = FuseCodeCodec.parseFuseNamesFromTitle(title)
        assertEquals("Alex", u1)
        assertEquals("Sam", u2)
        assertEquals("A", FuseCodeCodec.extractInitial(u1))
        assertEquals("S", FuseCodeCodec.extractInitial(u2))
    }

    @Test
    fun `PlaylistEntity roundtrips Fuse Blend metadata and enforces non editable and non downloadable flags`() {
        val original = Playlist(
            id = 77,
            name = "Riya fuse Kabir",
            description = "Fuse Blend",
            songCount = 30,
            isFuse = true,
            fuseId = 14,
            fuseMatchScore = 88,
            fuseUser1Name = "Riya",
            fuseUser2Name = "Kabir"
        )
        assertFalse(original.isEditable)
        assertFalse(original.isDownloadable)

        val entity = PlaylistEntity.fromPlaylist(original, userId = 5)
        val restored = entity.toPlaylist()

        assertTrue(restored.isFuse)
        assertEquals(14, restored.fuseId)
        assertEquals(88, restored.fuseMatchScore)
        assertEquals("Riya", restored.fuseUser1Name)
        assertEquals("Kabir", restored.fuseUser2Name)
        assertFalse(restored.isEditable)
        assertFalse(restored.isDownloadable)
    }

    @Test
    fun `computeCommonFuseSongs and computeBlendFallback enforce stream only non downloadable songs and contributor badges`() {
        val user1LikedAndNormal = (1..25).map { id ->
            Song(
                id = id,
                title = "Track $id",
                artist = "Artist ${id % 5}",
                audioUrl = "http://server/audio/$id.mp3",
                coverUrl = "http://server/cover/$id.jpg",
                isLiked = id <= 10,
                isDownloaded = true,
                localPath = "/data/local/$id.mp3"
            )
        }

        // User 2 shares songs 15..25 (11 common songs) plus unique songs 100..125
        val user2LikedAndNormal = ((15..25) + (100..125)).map { id ->
            Song(
                id = id,
                title = "Track $id",
                artist = "Artist ${id % 5}",
                audioUrl = "http://server/audio/$id.mp3",
                coverUrl = "http://server/cover/$id.jpg",
                isLiked = id >= 20,
                isDownloaded = true,
                localPath = "/data/local/$id.mp3"
            )
        }

        val commonOnly = FuseCodeCodec.computeCommonFuseSongs(
            user1Songs = user1LikedAndNormal,
            user2Songs = user2LikedAndNormal,
            maxSongs = 30,
            random = Random(42)
        )
        assertEquals(11, commonOnly.size)
        assertTrue(commonOnly.all { it.id in 15..25 })
        assertTrue(commonOnly.all { !it.isDownloaded && it.localPath == null && it.fuseContributor == "both" })

        val (blended, matchScore) = FuseCodeCodec.computeBlendFallback(
            user1Songs = user1LikedAndNormal,
            user2Songs = user2LikedAndNormal,
            maxSongs = 30,
            random = Random(42)
        )
        assertEquals(30, blended.size)
        assertEquals(30, blended.map { it.id }.distinct().size)
        assertTrue(matchScore in 42..99)
        assertTrue(blended.all { !it.isDownloaded && it.localPath == null })
        assertEquals(11, blended.count { it.fuseContributor == "both" })
        assertTrue(blended.any { it.fuseContributor == "user1" })
        assertTrue(blended.any { it.fuseContributor == "user2" })
    }
}
