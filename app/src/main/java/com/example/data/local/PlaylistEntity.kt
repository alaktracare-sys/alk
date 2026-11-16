package com.example.data.local

import com.example.data.fuse.FuseCodeCodec
import com.example.model.Playlist
import java.util.Locale

data class PlaylistEntity(
    val id: Int,
    val userId: Int,
    val name: String,
    val description: String = "",
    val isSystem: Boolean = false,
    val songCount: Int = 0,
    val lastSyncedAt: Long = System.currentTimeMillis()
) {
    fun toPlaylist(): Playlist {
        if (description.startsWith(FUSE_META_PREFIX)) {
            val parts = description.removePrefix(FUSE_META_PREFIX).split("|")
            val parsedFuseId = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 }
            val parsedMatchScore = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val fallbackNames = FuseCodeCodec.parseFuseNamesFromTitle(name)
            val u1 = decodeHex(parts.getOrNull(2).orEmpty()).ifBlank { fallbackNames.first }
            val u2 = decodeHex(parts.getOrNull(3).orEmpty()).ifBlank { fallbackNames.second }
            return Playlist(
                id = id,
                name = name,
                description = "Fuse Blend",
                songCount = songCount,
                isLikedPlaylist = false,
                isDownloadedPlaylist = false,
                isFuse = true,
                fuseId = parsedFuseId,
                fuseMatchScore = parsedMatchScore,
                fuseUser1Name = u1,
                fuseUser2Name = u2
            )
        }

        val isImplicitFuse = !isSystem && (description == "Fuse Playlist" || FuseCodeCodec.isFusePlaylistName(name))
        if (isImplicitFuse) {
            val (u1, u2) = FuseCodeCodec.parseFuseNamesFromTitle(name)
            return Playlist(
                id = id,
                name = name,
                description = "Fuse Blend",
                songCount = songCount,
                isFuse = true,
                fuseUser1Name = u1,
                fuseUser2Name = u2
            )
        }

        return Playlist(
            id = id,
            name = name,
            description = description,
            songCount = songCount,
            isLikedPlaylist = isSystem && (id == Playlist.ID_LIKED || name.contains("Liked", ignoreCase = true)),
            isDownloadedPlaylist = isSystem && (id == Playlist.ID_DOWNLOADED || name.contains("Download", ignoreCase = true))
        )
    }

    companion object {
        const val FUSE_META_PREFIX = "__FUSE_META__|"

        fun encodeFuseDescription(
            fuseId: Int?,
            matchScore: Int,
            user1Name: String,
            user2Name: String
        ): String {
            val fId = fuseId ?: 0
            val score = matchScore.coerceIn(0, 100)
            val h1 = encodeHex(user1Name.trim())
            val h2 = encodeHex(user2Name.trim())
            return "$FUSE_META_PREFIX$fId|$score|$h1|$h2"
        }

        fun fromPlaylist(
            playlist: Playlist,
            userId: Int,
            isSystem: Boolean = false,
            lastSyncedAt: Long = System.currentTimeMillis()
        ): PlaylistEntity {
            val isFuse = playlist.isFuse || (!isSystem && FuseCodeCodec.isFusePlaylistName(playlist.name))
            val fallbackNames = if (isFuse) FuseCodeCodec.parseFuseNamesFromTitle(playlist.name) else ("" to "")
            val resolvedDesc = if (isFuse) {
                encodeFuseDescription(
                    fuseId = playlist.fuseId,
                    matchScore = playlist.fuseMatchScore,
                    user1Name = playlist.fuseUser1Name.ifBlank { fallbackNames.first },
                    user2Name = playlist.fuseUser2Name.ifBlank { fallbackNames.second }
                )
            } else {
                playlist.description
            }
            return PlaylistEntity(
                id = playlist.id,
                userId = userId,
                name = playlist.name,
                description = resolvedDesc,
                isSystem = isSystem || playlist.isLikedPlaylist || playlist.isDownloadedPlaylist,
                songCount = playlist.songCount,
                lastSyncedAt = lastSyncedAt
            )
        }

        private fun encodeHex(input: String): String {
            if (input.isEmpty()) return ""
            val bytes = input.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            return sb.toString()
        }

        private fun decodeHex(hex: String): String {
            if (hex.isEmpty() || hex.length % 2 != 0) return ""
            return try {
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
}
