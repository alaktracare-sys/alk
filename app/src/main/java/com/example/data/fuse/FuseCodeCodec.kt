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
    val expiresAtMs: Long,
    val serverManaged: Boolean = false
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

data class FusePlaylistBundle(
    val playlist: Playlist,
    val songs: List<Song>
)

sealed class FusePollStatus {
    data object Waiting : FusePollStatus()
    data object Expired : FusePollStatus()
    data class Joined(
        val playlist: Playlist,
        val songs: List<Song>,
        val matchScore: Int = playlist.fuseMatchScore
    ) : FusePollStatus()
}

data class FuseJoinResult(
    val playlist: Playlist,
    val songs: List<Song>,
    val hostName: String,
    val joinerName: String,
    val matchScore: Int = playlist.fuseMatchScore
)

object FuseCodeCodec {
    const val FUSE_TTL_MS: Long = 5L * 60L * 1000L // 5 minutes
    const val MAX_FUSE_SONGS: Int = 30
    const val PENDING_MARKER_PREFIX: String = "__FUSE_PENDING__|"

    // 36^5 = 60,466,176 ensures legacy encoded userIds >= 1 always encode into >= 6 uppercase base36 chars
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
        if (!isValidFuseCodeFormat(cleaned)) return null

        val parsed = cleaned.toLongOrNull(36) ?: return null
        val shifted = parsed - BASE36_SIX_CHAR_OFFSET
        if (shifted < 65_536L) return null
        val userIdLong = shifted ushr 16
        if (userIdLong <= 0L || userIdLong > Int.MAX_VALUE.toLong()) return null
        return userIdLong.toInt()
    }

    fun isValidFuseCodeFormat(candidate: String): Boolean {
        if (candidate.length !in 6..10) return false
        return candidate.all { it in '0'..'9' || it in 'A'..'Z' }
    }

    /**
     * Extracts a 6–10 character uppercase Fuse invite code from raw user input, deep links, or share messages.
     * Supports both server-generated random 6-char codes (e.g. "A7K9P2", "2BCD34") and legacy encoded codes.
     */
    fun extractCode(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        // 1. Check query param ?code=XXXXXX or &code=XXXXXX
        val queryRegex = Regex("""[?&]code=([A-Za-z0-9\-]{6,12})""", RegexOption.IGNORE_CASE)
        queryRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { candidate ->
            val normalized = candidate.replace("-", "").uppercase(Locale.US)
            if (isValidFuseCodeFormat(normalized)) return normalized
        }

        // 2. Check path /fuse/XXXXXX
        val pathRegex = Regex("""/fuse/([A-Za-z0-9\-]{6,12})""", RegexOption.IGNORE_CASE)
        pathRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { candidate ->
            val normalized = candidate.replace("-", "").uppercase(Locale.US)
            if (isValidFuseCodeFormat(normalized)) return normalized
        }

        // 3. Direct code input (with optional spaces or hyphens, when not a URL/sentence)
        if (!trimmed.contains("://") && !trimmed.contains("/") && trimmed.length <= 14) {
            val directNormalized = trimmed.replace("-", "").replace(" ", "").uppercase(Locale.US)
            if (isValidFuseCodeFormat(directNormalized)) {
                return directNormalized
            }
        }

        // 4. Explicit "code XXXXXX" pattern inside shared text
        val explicitCodeRegex = Regex("""\bcode\s+([A-Za-z0-9\-]{6,12})\b""", RegexOption.IGNORE_CASE)
        explicitCodeRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { candidate ->
            val normalized = candidate.replace("-", "").uppercase(Locale.US)
            if (isValidFuseCodeFormat(normalized)) return normalized
        }

        // 5. Fallback: scan tokens in pasted text (prefer tokens containing a digit or valid base36 hostId)
        val tokenRegex = Regex("""\b([A-Za-z0-9]{6,10})\b""")
        for (match in tokenRegex.findAll(trimmed)) {
            val rawToken = match.groupValues[1]
            val candidate = rawToken.uppercase(Locale.US)
            if (candidate == "ALAKTRA" || candidate == "EXPIRES" || candidate == "INVITED" || candidate == "MINUTES") {
                continue
            }
            if (rawToken.any { it.isDigit() } || decodeHostUserId(candidate) != null) {
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
        return "$hostName invited you to join a Fuse Blend on Alaktra! Tap $link or enter code $clean in Create Fuse (expires in 5 min)."
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

    fun parseFuseNamesFromTitle(playlistName: String): Pair<String, String> {
        val trimmed = playlistName.trim()
        val parts = trimmed.split(Regex("""\s+fuse\s+""", RegexOption.IGNORE_CASE), limit = 2)
        return if (parts.size == 2) {
            parts[0].trim().ifEmpty { "User 1" } to parts[1].trim().ifEmpty { "User 2" }
        } else {
            "User 1" to "User 2"
        }
    }

    fun extractInitial(name: String, fallback: String = "U"): String {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return fallback
        val firstLetter = cleaned.firstOrNull { it.isLetterOrDigit() }
        return firstLetter?.uppercaseChar()?.toString() ?: fallback
    }

    /**
     * Computes up to [maxSongs] (default 30) common songs shared between User 1's playlists
     * (liked + normal) and User 2's playlists (liked + normal).
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

        for (song in user2Songs) {
            val sig = normalizeSongSignature(song)
            if (song.id in user1Ids || (sig != null && sig in user1Signatures)) {
                matchedById.putIfAbsent(
                    song.id,
                    song.copy(localPath = null, isDownloaded = false, fuseContributor = "both")
                )
            }
        }

        for (song in user1Songs) {
            if (song.id in matchedById) continue
            val sig = normalizeSongSignature(song)
            val existsInUser2 = user2Songs.any { u2 ->
                u2.id == song.id || (sig != null && normalizeSongSignature(u2) == sig)
            }
            if (existsInUser2) {
                matchedById.putIfAbsent(
                    song.id,
                    song.copy(localPath = null, isDownloaded = false, fuseContributor = "both")
                )
            }
        }

        val allCommon = matchedById.values.toList()
        return if (allCommon.size <= maxSongs) {
            allCommon
        } else {
            allCommon.shuffled(random).take(maxSongs)
        }
    }

    /**
     * Client-side Blend fallback used ONLY if offline / legacy server lacks `/fuse/` endpoints.
     * Prioritizes shared songs ("both") first, then interleaves User 1 & User 2 tracks up to 30,
     * and computes a Spotify Blend Taste Match % score.
     */
    fun computeBlendFallback(
        user1Songs: Collection<Song>,
        user2Songs: Collection<Song>,
        maxSongs: Int = MAX_FUSE_SONGS,
        random: Random = Random.Default
    ): Pair<List<Song>, Int> {
        val shared = computeCommonFuseSongs(user1Songs, user2Songs, maxSongs = maxSongs, random = random)
        val pickedIds = shared.map { it.id }.toMutableSet()
        val result = shared.toMutableList()

        val u1Only = user1Songs.filter { it.id !in pickedIds }
            .map { it.copy(localPath = null, isDownloaded = false, fuseContributor = "user1") }
        val u2Only = user2Songs.filter { it.id !in pickedIds }
            .map { it.copy(localPath = null, isDownloaded = false, fuseContributor = "user2") }

        var i1 = 0
        var i2 = 0
        var turn = 0
        while (result.size < maxSongs && (i1 < u1Only.size || i2 < u2Only.size)) {
            if (turn % 2 == 0) {
                if (i1 < u1Only.size) {
                    val s = u1Only[i1++]
                    if (pickedIds.add(s.id)) result.add(s)
                } else if (i2 < u2Only.size) {
                    val s = u2Only[i2++]
                    if (pickedIds.add(s.id)) result.add(s)
                }
            } else {
                if (i2 < u2Only.size) {
                    val s = u2Only[i2++]
                    if (pickedIds.add(s.id)) result.add(s)
                } else if (i1 < u1Only.size) {
                    val s = u1Only[i1++]
                    if (pickedIds.add(s.id)) result.add(s)
                }
            }
            turn++
        }

        val unionCount = (user1Songs.map { it.id }.toSet() + user2Songs.map { it.id }.toSet()).size
        val matchScore = if (unionCount == 0) {
            75
        } else {
            val songRatio = shared.size.toDouble() / minOf(maxOf(user1Songs.size, 1), maxOf(user2Songs.size, 1)).coerceAtMost(30)
            (48 + (songRatio * 49.0)).toInt().coerceIn(42, 99)
        }
        return result.take(maxSongs) to matchScore
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
