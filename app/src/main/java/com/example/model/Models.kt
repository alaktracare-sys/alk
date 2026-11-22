package com.example.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SongArtistCredit(
    val id: Int,
    val name: String,
    val role: String = "primary", // "primary" | "featured"
    val position: Int = 0,
    val avatarUrl: String = "",
    val isVerified: Boolean = false
)

object ArtistCreditJsonCodec {
    private val gson = Gson()
    private val listType = object : TypeToken<List<SongArtistCredit>>() {}.type

    fun encode(credits: List<SongArtistCredit>): String? {
        if (credits.isEmpty()) return null
        return try {
            gson.toJson(credits, listType)
        } catch (_: Exception) {
            null
        }
    }

    fun decode(json: String?): List<SongArtistCredit> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val parsed: List<SongArtistCredit>? = gson.fromJson(json, listType)
            parsed.orEmpty().filter { it.id > 0 && it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

data class Song(
    val id: Int,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val duration: Double = 0.0,
    val isLiked: Boolean = false,
    val localPath: String? = null,
    val isDownloaded: Boolean = false,
    val isCached: Boolean = false,
    val dominantColorHex: String? = null,
    val fuseContributor: String? = null,
    val primaryArtistId: Int? = null,
    val artists: List<SongArtistCredit> = emptyList(),
    val playCount: Int = 0
) {
    fun resolvedPath(): String = localPath ?: audioUrl

    val primaryArtistCredit: SongArtistCredit?
        get() = artists.firstOrNull { it.role.equals("primary", ignoreCase = true) } ?: artists.firstOrNull()

    val primaryArtistName: String
        get() = primaryArtistCredit?.name?.takeIf { it.isNotBlank() }
            ?: artist.substringBefore(",").trim().ifBlank { artist.ifBlank { "Unknown Artist" } }

    val hasMultipleArtists: Boolean
        get() = artists.size > 1
}

data class ArtistProfile(
    val id: Int,
    val name: String,
    val bio: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val isVerified: Boolean = false,
    val claimedByUserId: Int? = null,
    val claimedByUsername: String? = null,
    val songCount: Int = 0,
    val totalPlays: Int = 0,
    val monthlyListeners: Int = 0
)

data class ArtistDetailBundle(
    val artist: ArtistProfile,
    val popularSongs: List<Song> = emptyList(),
    val primarySongs: List<Song> = emptyList(),
    val featuredSongs: List<Song> = emptyList(),
    val allSongs: List<Song> = emptyList()
)

data class ArtistClaimInfo(
    val id: Int,
    val userId: Int,
    val artistId: Int? = null,
    val requestedName: String,
    val note: String = "",
    val status: String = "pending",
    val createdAt: String = ""
)

data class UserArtistStatus(
    val userId: Int,
    val isArtist: Boolean = false,
    val claimedArtist: ArtistProfile? = null,
    val pendingClaim: ArtistClaimInfo? = null
)

data class Playlist(
    val id: Int,
    val name: String,
    val description: String = "",
    val songCount: Int = 0,
    val isLikedPlaylist: Boolean = false,
    val isDownloadedPlaylist: Boolean = false,
    val isOfflineBackupPlaylist: Boolean = false,
    val isFuse: Boolean = false,
    val fuseId: Int? = null,
    val fuseMatchScore: Int = 0,
    val fuseUser1Name: String = "",
    val fuseUser2Name: String = ""
) {
    val isEditable: Boolean
        get() = !isFuse && !isLikedPlaylist && !isDownloadedPlaylist && !isOfflineBackupPlaylist

    val isDownloadable: Boolean
        get() = !isFuse

    companion object {
        const val ID_LIKED = -1
        const val ID_DOWNLOADED = -2
        const val ID_OFFLINE_BACKUP = -3
        const val DAILY_ID_OFFSET = -100
        const val DAILY_ID_MAX = -101
        const val DAILY_ID_MIN = -105

        fun dailyPlaylistIdForSlot(slotIndex: Int): Int = DAILY_ID_OFFSET - slotIndex.coerceIn(1, 5)
        fun isDailyPlaylistId(id: Int): Boolean = id in DAILY_ID_MIN..DAILY_ID_MAX
    }
}

data class DailyPlaylist(
    val id: Int,
    val serverId: Int = 0,
    val slotIndex: Int,
    val date: String,
    val name: String,
    val subtitle: String = "",
    val accentColorHex: String = "#00E599",
    val previewCovers: List<String> = emptyList(),
    val songCount: Int = 0,
    val songs: List<Song> = emptyList()
)

data class User(
    val id: Int,
    val username: String,
    val email: String,
    val name: String = "",
    val avatarUrl: String? = null
)

data class OfflineAction(
    val id: String,
    val type: String, // create_playlist, delete_playlist, rename_playlist, add_song, remove_song
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class LocationPin(
    val id: Int,
    val userId: Int,
    val songId: Int,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val imageUrl: String,
    val createdAt: String,
    val song: Song
)

data class ClubMember(
    val userId: Int,
    val username: String,
    val isOwner: Boolean = false
)

data class ClubCollaborativeSong(
    val song: Song,
    val addedByUserId: Int,
    val addedByUsername: String,
    val addedAt: String? = null
)

data class ClubPlaylistSummary(
    val clubId: Int,
    val code: String,
    val name: String,
    val ownerUserId: Int,
    val ownerUsername: String,
    val isOwner: Boolean,
    val memberCount: Int,
    val songCount: Int,
    val createdAt: String? = null,
    val previewCovers: List<String> = emptyList()
)

data class ClubPlaylistDetails(
    val clubId: Int,
    val code: String,
    val name: String,
    val ownerUserId: Int,
    val ownerUsername: String,
    val isOwner: Boolean,
    val members: List<ClubMember> = emptyList(),
    val songs: List<ClubCollaborativeSong> = emptyList()
)

// Backward compatibility alias
typealias ClubState = ClubPlaylistDetails

