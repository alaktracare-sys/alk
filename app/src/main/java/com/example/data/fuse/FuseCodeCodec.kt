package com.example.data.fuse

import com.example.model.Playlist
import com.example.model.Song
import java.util.Locale
import kotlin.random.Random

data class FuseSession(
    val code: String,
    val hostUserId: Int,
    val hostName: String,
    val tempPlaylistId: Int,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    fun remainingMillis(nowMs: Long = System.currentTimeMillis()): Long {
        return (expiresAtMs - nowMs).coerceAtLeast(0L)
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs >= expiresAtMs
    }
}

data class PendingFuseMarker(
    val code: String,
    val expiresAtMs: Long,
    val hostName: String
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

sealed class FusePollStatus {
    data object Waiting : FusePollStatus()
    data object Expired : FusePollStatus()
    data class Joined(
        val playlist: Playlist,
        val songs: List<Song>
    ) : FusePollStatus()
}

data class FuseJoinResult(
    val playlist: Playlist,
    val songs: List<Song>,
    val hostName: String,
    val joinerName: String
)

object FuseCodeCodec {
    const val FUSE_TTL_MS: Long = 5L * 60L * 1000L // 5 minutes
    const val MAX_FUSE_SONGS: Int = 30
    const val PENDING_MARKER_PREFIX: String = "__FUSE_PENDING__|"

    // 36^5 = 60,466,176 ensures userIds >= 1 always encode into at least 6 uppercase base36 chars without leading zeros
    private const val BASE36_SIX_CHAR_OFFSET: Long = 60_466_176L

    fun generateCode(hostUserId: Int, random: Random = Random.Default): String {
        val safeUserId = hostUserId.coerceAtLeast(1)
        val nonce = random.nextInt(0, 0x10000)
        return encodeCode(safeUserId, nonce)
    }

    fun encodeCode(hostUserId: Int, nonce: Int): String {
        require(hostUserId > 0) { "hostUserId must be positive" }
        val cleanNonce = (nonce and 0xFFFF).toLong()
        val combined = BASE36_SIX_CHAR_OFFSET + (hostUserId.toLong() shl 16) + cleanNonce
        return combined.toString(36).uppercase(Locale.US)
    }

    fun decodeHostUserId(rawCode: String): Int? {
        val cleaned = rawCode.trim()
            .replace("-", "")
            .replace(" ", "")
            .uppercase(Locale.US)
        if (cleaned.length !in 6..10) return null
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'Z' }) return null

        val parsed = cleaned.toLongOrNull(36) ?: return null
        val shifted = parsed - BASE36_SIX_CHAR_OFFSET
        if (shifted < 65_536L) return null
        val userIdLong = shifted ushr 16
        if (userIdLong <= 0L || userIdLong > Int.MAX_VALUE.toLong()) return null
        return userIdLong.toInt()
    }

    fun extractCode(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        // 1. Check query param ?code=XXXXXX or &code=XXXXXX
        val queryRegex = Regex("""[?&]code=([A-Za-z0-9\-]{6,12})""", RegexOption.IGNORE_CASE)
        queryRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { candidate ->
            val normalized = candidate.replace("-", "").uppercase(Locale.US)
            if (decodeHostUserId(normalized) != null) return normalized
        }

        // 2. Check path /fuse/XXXXXX
        val pathRegex = Regex("""/fuse/([A-Za-z0-9\-]{6,12})""", RegexOption.IGNORE_CASE)
        pathRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { candidate ->
            val normalized = candidate.replace("-", "").uppercase(Locale.US)
            if (decodeHostUserId(normalized) != null) return normalized
        }

        // 3. Direct code input (with optional spaces or hyphens)
        val directNormalized = trimmed.replace("-", "").replace(" ", "").uppercase(Locale.US)
        if (decodeHostUserId(directNormalized) != null) {
            return directNormalized
        }

        // 4. Fallback: scan tokens in pasted text (e.g. full share message)
        val tokenRegex = Regex("""\b([A-Za-z0-9]{6,10})\b""")
        for (match in tokenRegex.findAll(trimmed)) {
            val candidate = match.groupValues[1].uppercase(Locale.US)
            if (decodeHostUserId(candidate) != null) {
                return candidate
            }
        }
        return null
    }

    fun buildShareDeepLink(code: String): String {
        val clean = code.trim().uppercase(Locale.US)
        return "https://alaktra.app/fuse?code=$clean"
    }

    fun buildAppSchemeLink(code: String): String {
        val clean = code.trim().uppercase(Locale.US)
        return "alaktra://fuse?code=$clean"
    }

    fun buildShareMessage(hostName: String, code: String): String {
        val clean = code.trim().uppercase(Locale.US)
        val link = buildShareDeepLink(clean)
        return "$hostName invited you to join a Fuse on Alaktra! Tap $link or enter code $clean in Create Fuse (expires in 5 min)."
    }

    fun buildPendingMarkerName(code: String, expiresAtMs: Long, hostName: String): String {
        val cleanCode = code.trim().uppercase(Locale.US)
        val encodedHost = encodeHex(hostName.trim().ifEmpty { "User" })
        return "$PENDING_MARKER_PREFIX$cleanCode|$expiresAtMs|$encodedHost"
    }

    fun isPendingMarker(name: String?): Boolean {
        return name != null && name.startsWith(PENDING_MARKER_PREFIX)
    }

    fun parsePendingMarker(name: String?): PendingFuseMarker? {
        if (name == null || !name.startsWith(PENDING_MARKER_PREFIX)) return null
        val payload = name.removePrefix(PENDING_MARKER_PREFIX)
        val parts = payload.split("|")
        if (parts.size < 3) return null
        val code = parts[0].trim().uppercase(Locale.US)
        val expiresAtMs = parts[1].toLongOrNull() ?: return null
        val hostName = decodeHex(parts[2]).ifBlank { "User" }
        return PendingFuseMarker(
            code = code,
            expiresAtMs = expiresAtMs,
            hostName = hostName
        )
    }

    fun buildFusePlaylistName(user1Name: String, user2Name: String): String {
        val u1 = user1Name.trim().ifEmpty { "user 1" }
        val u2 = user2Name.trim().ifEmpty { "user 2" }
        return "$u1 fuse $u2"
    }

    fun isFusePlaylistName(name: String): Boolean {
        return !isPendingMarker(name) && Regex(""".+\s+fuse\s+.+""", RegexOption.IGNORE_CASE).matches(name.trim())
    }

    /**
     * Computes up to [maxSongs] (default 30) common songs shared between User 1's playlists
     * (liked + normal) and User 2's playlists (liked + normal).
     * Matches by song ID first, and also matches identical normalized (title + artist) pairs.
     */
    fun computeCommonFuseSongs(
        user1Songs: Collection<Song>,
        user2Songs: Collection<Song>,
        maxSongs: Int = MAX_FUSE_SONGS,
        random: Random = Random.Default
    ): List<Song> {
        if (user1Songs.isEmpty() || user2Songs.isEmpty() || maxSongs <= 0) return emptyList()

        val user1Ids = user1Songs.map { it.id }.toHashSet()
        val user1Signatures = user1Songs.mapNotNull { song ->
            normalizeSongSignature(song)
        }.toHashSet()

        val matchedById = LinkedHashMap<Int, Song>()

        // Prefer User 2's Song metadata (which already carries User 2's localPath / isLiked flags when joining)
        for (song in user2Songs) {
            val sig = normalizeSongSignature(song)
            if (song.id in user1Ids || (sig != null && sig in user1Signatures)) {
                matchedById.putIfAbsent(song.id, song)
            }
        }

        // Also check User 1 songs in case signature matched a song not yet keyed
        for (song in user1Songs) {
            if (song.id in matchedById) continue
            val sig = normalizeSongSignature(song)
            val existsInUser2 = user2Songs.any { u2 ->
                u2.id == song.id || (sig != null && normalizeSongSignature(u2) == sig)
            }
            if (existsInUser2) {
                matchedById.putIfAbsent(song.id, song)
            }
        }

        val allCommon = matchedById.values.toList()
        return if (allCommon.size <= maxSongs) {
            allCommon
        } else {
            allCommon.shuffled(random).take(maxSongs)
        }
    }

    private fun normalizeSongSignature(song: Song): String? {
        val t = song.title.trim().lowercase(Locale.US)
        val a = song.artist.trim().lowercase(Locale.US)
        if (t.isEmpty() || t == "untitled track") return null
        return "$t::$a"
    }

    private fun encodeHex(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    private fun decodeHex(hex: String): String {
        return try {
            if (hex.length % 2 != 0) return ""
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                val idx = i * 2
                bytes[i] = hex.substring(idx, idx + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
