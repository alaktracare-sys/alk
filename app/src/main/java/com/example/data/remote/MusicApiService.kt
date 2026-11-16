package com.example.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

// ----------------- AUTH MODELS -----------------

data class LoginRequest(
    val identifier: String,
    val password: String
)

data class LoginResponse(
    val message: String? = null,
    @SerializedName("user_id") val userId: Int,
    val username: String,
    val email: String,
    @SerializedName("claimed_artist_id") val claimedArtistId: Int? = null,
    @SerializedName("claimed_artist_name") val claimedArtistName: String? = null
)

data class SignupRequest(
    val username: String,
    val email: String,
    val password: String,
    @SerializedName("confirm_password") val confirmPassword: String
)

data class VerifyOtpRequest(
    val email: String,
    val otp: String
)

data class ResendOtpRequest(
    val email: String
)

data class MessageResponse(
    val message: String? = null,
    val detail: String? = null
)

// ----------------- SONG MODELS -----------------

data class SongArtistCreditDto(
    val id: Int = 0,
    val name: String? = null,
    val role: String? = "primary",
    val position: Int = 0,
    @SerializedName("avatar_url", alternate = ["avatarUrl"]) val avatarUrl: String? = null,
    @SerializedName("is_verified", alternate = ["isVerified"]) val isVerified: Boolean = false
)

data class SongDto(
    val id: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    @SerializedName("audio_url", alternate = ["audioUrl"]) val audioUrl: String? = null,
    @SerializedName("cover_url", alternate = ["coverUrl"]) val coverUrl: String? = null,
    @SerializedName("dominant_color", alternate = ["dominantColor"]) val dominantColor: String? = null,
    val duration: Double = 0.0,
    @SerializedName("fuse_contributor", alternate = ["fuseContributor"]) val fuseContributor: String? = null,
    @SerializedName("primary_artist_id", alternate = ["primaryArtistId"]) val primaryArtistId: Int? = null,
    val artists: List<SongArtistCreditDto>? = null,
    @SerializedName("play_count", alternate = ["playCount"]) val playCount: Int = 0
)

// ----------------- ARTIST & CLAIM MODELS -----------------

data class ArtistDto(
    val id: Int = 0,
    val name: String? = null,
    val bio: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("banner_url") val bannerUrl: String? = null,
    @SerializedName("is_verified") val isVerified: Boolean = false,
    @SerializedName("claimed_by_user_id") val claimedByUserId: Int? = null,
    @SerializedName("claimed_by_username") val claimedByUsername: String? = null,
    @SerializedName("song_count") val songCount: Int = 0,
    @SerializedName("total_plays") val totalPlays: Int = 0,
    @SerializedName("monthly_listeners") val monthlyListeners: Int = 0
)

data class ArtistDetailResponse(
    val artist: ArtistDto? = null,
    @SerializedName("popular_songs") val popularSongs: List<SongDto>? = null,
    @SerializedName("primary_songs") val primarySongs: List<SongDto>? = null,
    @SerializedName("featured_songs") val featuredSongs: List<SongDto>? = null,
    @SerializedName("all_songs") val allSongs: List<SongDto>? = null
)

data class ArtistClaimDto(
    val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("artist_id") val artistId: Int? = null,
    @SerializedName("requested_name") val requestedName: String? = null,
    val note: String? = null,
    val status: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

data class SubmitArtistClaimRequest(
    @SerializedName("user_id") val userId: Int,
    @SerializedName("artist_id") val artistId: Int? = null,
    @SerializedName("requested_name") val requestedName: String,
    val note: String = ""
)

data class SubmitArtistClaimResponse(
    val success: Boolean = false,
    val claim: ArtistClaimDto? = null,
    val detail: String? = null
)

data class UserArtistStatusResponse(
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("is_artist") val isArtist: Boolean = false,
    @SerializedName("claimed_artist") val claimedArtist: ArtistDto? = null,
    @SerializedName("pending_claim") val pendingClaim: ArtistClaimDto? = null
)

data class ArtistMutationResponse(
    val success: Boolean = false,
    val artist: ArtistDto? = null,
    val detail: String? = null
)

data class UploadStudioSongResponse(
    val success: Boolean = false,
    val id: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    @SerializedName("dominant_color") val dominantColor: String? = null,
    val song: SongDto? = null,
    val detail: String? = null
)

// ----------------- PLAYLIST MODELS -----------------

data class ServerPlaylistDto(
    val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    val name: String? = null,
    @SerializedName("is_system") val isSystem: Int = 0,
    @SerializedName("is_fuse") val isFuse: Int = 0,
    @SerializedName("fuse_id") val fuseId: Int? = null,
    @SerializedName("song_count") val songCount: Int = 0,
    @SerializedName("match_score") val matchScore: Int = 0,
    @SerializedName("user1_name") val user1Name: String? = null,
    @SerializedName("user2_name") val user2Name: String? = null,
    val editable: Boolean? = null,
    val downloadable: Boolean? = null
)

// ----------------- FUSE BLEND MODELS -----------------

data class FuseCreateResponse(
    val code: String = "",
    @SerializedName("host_user_id") val hostUserId: Int = 0,
    @SerializedName("host_name") val hostName: String = "",
    @SerializedName("created_at_ms") val createdAtMs: Long = 0L,
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
    @SerializedName("ttl_seconds") val ttlSeconds: Int = 300
)

data class FuseStatusResponse(
    val status: String = "",
    val code: String? = null,
    @SerializedName("host_user_id") val hostUserId: Int = 0,
    @SerializedName("host_name") val hostName: String? = null,
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
    @SerializedName("remaining_ms") val remainingMs: Long = 0L,
    @SerializedName("playlist_id") val playlistId: Int? = null,
    @SerializedName("fuse_id") val fuseId: Int? = null,
    val name: String? = null,
    @SerializedName("user1_id") val user1Id: Int? = null,
    @SerializedName("user1_name") val user1Name: String? = null,
    @SerializedName("user2_id") val user2Id: Int? = null,
    @SerializedName("user2_name") val user2Name: String? = null,
    @SerializedName("match_score") val matchScore: Int = 0,
    @SerializedName("is_fuse") val isFuse: Boolean = false,
    val editable: Boolean = false,
    val downloadable: Boolean = false,
    @SerializedName("song_count") val songCount: Int = 0,
    val songs: List<SongDto>? = null
)

data class FuseBundleResponse(
    val success: Boolean = false,
    @SerializedName("playlist_id") val playlistId: Int = 0,
    @SerializedName("host_playlist_id") val hostPlaylistId: Int? = null,
    @SerializedName("fuse_id") val fuseId: Int? = null,
    val name: String? = null,
    @SerializedName("user1_id") val user1Id: Int = 0,
    @SerializedName("user1_name") val user1Name: String? = null,
    @SerializedName("user2_id") val user2Id: Int = 0,
    @SerializedName("user2_name") val user2Name: String? = null,
    @SerializedName("match_score") val matchScore: Int = 0,
    @SerializedName("last_refreshed_date") val lastRefreshedDate: String? = null,
    @SerializedName("is_fuse") val isFuse: Boolean = true,
    val editable: Boolean = false,
    val downloadable: Boolean = false,
    @SerializedName("song_count") val songCount: Int = 0,
    val songs: List<SongDto>? = null,
    val detail: String? = null,
    val error: String? = null
)

data class LikedPlaylistResponse(
    @SerializedName("playlist_id") val playlistId: Int? = null,
    val error: String? = null
)

data class CreatePlaylistResponse(
    val success: Boolean = false,
    val id: Int? = null,
    val name: String? = null,
    val error: String? = null
)

data class GenericSuccessResponse(
    val success: Boolean = false,
    val name: String? = null,
    val error: String? = null,
    val message: String? = null,
    @SerializedName("synced_count") val syncedCount: Int = 0
)

// ----------------- HISTORY MODELS -----------------

data class HistoryEventDto(
    @SerializedName("song_id") val songId: Int,
    @SerializedName("listened_seconds") val listenedSeconds: Long,
    @SerializedName("ms_played") val msPlayed: Long? = null,
    @SerializedName("played_at") val playedAt: Long,
    @SerializedName("local_date") val localDate: String? = null
)

data class HistorySyncRequest(
    @SerializedName("user_id") val userId: Int,
    val events: List<HistoryEventDto>
)

data class DailyListenTimeDto(
    val date: String = "",
    val seconds: Long = 0L,
    val minutes: Int = 0
)

data class MonthlyListenTimeDto(
    val year: Int = 0,
    val month: Int = 0,
    @SerializedName("total_seconds") val totalSeconds: Long = 0L,
    val formatted: String? = null,
    val hours: Long = 0L,
    val minutes: Int = 0
)

data class SoundCapsuleEventDto(
    @SerializedName("song_id") val songId: Int = 0,
    @SerializedName("track_id") val trackId: String? = null,
    @SerializedName("track_title") val trackTitle: String? = null,
    @SerializedName("artist_id") val artistId: String? = null,
    @SerializedName("artist_name") val artistName: String? = null,
    @SerializedName("artwork_url") val artworkUrl: String? = null,
    @SerializedName("artist_artwork_url") val artistArtworkUrl: String? = null,
    val duration: Double = 0.0,
    @SerializedName("started_at_millis") val startedAtMillis: Long = 0L,
    @SerializedName("ms_played") val msPlayed: Long = 0L
)

data class SoundCapsuleResponse(
    @SerializedName("user_id") val userId: Int = 0,
    val events: List<SoundCapsuleEventDto>? = null,
    @SerializedName("daily_listen_time") val dailyListenTime: List<DailyListenTimeDto>? = null,
    @SerializedName("monthly_listen_time") val monthlyListenTime: List<MonthlyListenTimeDto>? = null,
    @SerializedName("available_months") val availableMonths: List<String>? = null
)

// ----------------- RETROFIT INTERFACE -----------------

interface MusicApiService {

    // === HEALTH & SYSTEM ===
    @GET("health")
    suspend fun healthCheck(): Response<Map<String, Any>>

    @POST("notifications/register-token")
    suspend fun registerDeviceToken(@Body body: RegisterTokenRequestDto): Map<String, Any>

    @GET("notifications/history")
    suspend fun getNotificationHistory(
        @Query("user_id") userId: Int? = null
    ): List<NotificationDto>

    // === AUTH ===
    @POST("auth/signup")
    suspend fun signup(@Body req: SignupRequest): Response<MessageResponse>

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body req: VerifyOtpRequest): Response<MessageResponse>

    @POST("auth/resend-otp")
    suspend fun resendOtp(@Body req: ResendOtpRequest): Response<MessageResponse>

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): Response<LoginResponse>

    // === SONGS ===
    @GET("songs")
    suspend fun getSongs(): Response<List<SongDto>>

    @GET("songs/search")
    suspend fun searchSongs(
        @Query("q") query: String,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0
    ): Response<List<SongDto>>

    @Multipart
    @POST("songs/upload")
    suspend fun uploadSong(
        @Part("title") title: RequestBody,
        @Part("artist") artist: RequestBody,
        @Part audio: MultipartBody.Part,
        @Part cover: MultipartBody.Part
    ): Response<GenericSuccessResponse>

    @Multipart
    @POST("songs/upload")
    suspend fun uploadStudioSong(
        @Part("title") title: RequestBody,
        @Part("artist") artist: RequestBody,
        @Part("color") color: RequestBody,
        @Part("primary_artist_id") primaryArtistId: RequestBody,
        @Part("uploader_user_id") uploaderUserId: RequestBody,
        @Part("featured_artist_ids") featuredArtistIds: RequestBody?,
        @Part("featured_artist_names") featuredArtistNames: RequestBody?,
        @Part audio: MultipartBody.Part,
        @Part cover: MultipartBody.Part
    ): Response<UploadStudioSongResponse>

    @FormUrlEncoded
    @POST("songs/{song_id}/allot")
    suspend fun allotSongArtists(
        @Path("song_id") songId: Int,
        @Field("title") title: String? = null,
        @Field("color") color: String? = null,
        @Field("primary_artist_id") primaryArtistId: Int? = null,
        @Field("featured_artist_ids") featuredArtistIdsJson: String? = null,
        @Field("featured_artist_names") featuredArtistNamesJson: String? = null,
        @Field("requester_user_id") requesterUserId: Int? = null
    ): Response<UploadStudioSongResponse>

    // === ARTISTS & STUDIO CLAIMS ===
    @GET("artists")
    suspend fun getArtists(
        @Query("q") query: String = "",
        @Query("unclaimed_only") unclaimedOnly: Boolean = false,
        @Query("limit") limit: Int = 100
    ): Response<List<ArtistDto>>

    @GET("artists/by-name")
    suspend fun getArtistByName(
        @Query("name") name: String
    ): Response<ArtistDto>

    @GET("artists/{artist_id}")
    suspend fun getArtistDetail(
        @Path("artist_id") artistId: Int
    ): Response<ArtistDetailResponse>

    @GET("artists/user-status/{user_id}")
    suspend fun getUserArtistStatus(
        @Path("user_id") userId: Int
    ): Response<UserArtistStatusResponse>

    @POST("artists/claims")
    suspend fun submitArtistClaim(
        @Body req: SubmitArtistClaimRequest
    ): Response<SubmitArtistClaimResponse>

    @Multipart
    @POST("artists/{artist_id}/update")
    suspend fun updateArtistProfile(
        @Path("artist_id") artistId: Int,
        @Part("requester_user_id") requesterUserId: RequestBody,
        @Part("name") name: RequestBody?,
        @Part("bio") bio: RequestBody?,
        @Part avatar: MultipartBody.Part?,
        @Part banner: MultipartBody.Part?
    ): Response<ArtistMutationResponse>

    // === PLAYLISTS ===
    @GET("playlists/{user_id}")
    suspend fun getPlaylists(@Path("user_id") userId: Int): Response<List<ServerPlaylistDto>>

    @GET("playlists/liked/{user_id}")
    suspend fun getLikedPlaylist(@Path("user_id") userId: Int): Response<LikedPlaylistResponse>

    @GET("playlists/{playlist_id}/songs")
    suspend fun getPlaylistSongs(@Path("playlist_id") playlistId: Int): Response<List<SongDto>>

    @FormUrlEncoded
    @POST("playlists/create")
    suspend fun createPlaylist(
        @Field("user_id") userId: Int,
        @Field("name") name: String
    ): Response<CreatePlaylistResponse>

    @FormUrlEncoded
    @POST("playlists/rename")
    suspend fun renamePlaylist(
        @Field("playlist_id") playlistId: Int,
        @Field("user_id") userId: Int,
        @Field("name") name: String
    ): Response<GenericSuccessResponse>

    @FormUrlEncoded
    @POST("playlists/delete")
    suspend fun deletePlaylist(
        @Field("playlist_id") playlistId: Int
    ): Response<GenericSuccessResponse>

    @FormUrlEncoded
    @POST("playlists/add-song")
    suspend fun addSongToPlaylist(
        @Field("playlist_id") playlistId: Int,
        @Field("song_id") songId: Int
    ): Response<GenericSuccessResponse>

    @FormUrlEncoded
    @POST("playlists/remove-song")
    suspend fun removeSongFromPlaylist(
        @Field("playlist_id") playlistId: Int,
        @Field("song_id") songId: Int
    ): Response<GenericSuccessResponse>

    // === FUSE BLEND (Server-Curated, Non-Editable, Stream-Only) ===
    @FormUrlEncoded
    @POST("fuse/create")
    suspend fun createFuseInvite(
        @Field("user_id") userId: Int,
        @Field("username") username: String
    ): Response<FuseCreateResponse>

    @FormUrlEncoded
    @POST("fuse/cancel")
    suspend fun cancelFuseInvite(
        @Field("code") code: String,
        @Field("user_id") userId: Int
    ): Response<GenericSuccessResponse>

    @GET("fuse/status/{code}")
    suspend fun getFuseStatus(
        @Path("code") code: String
    ): Response<FuseStatusResponse>

    @FormUrlEncoded
    @POST("fuse/join")
    suspend fun joinFuseInvite(
        @Field("code") code: String,
        @Field("user_id") userId: Int,
        @Field("username") username: String
    ): Response<FuseBundleResponse>

    @GET("fuse/playlist/{playlist_id}")
    suspend fun getFusePlaylist(
        @Path("playlist_id") playlistId: Int
    ): Response<FuseBundleResponse>

    // === SOUND CAPSULE & LISTENING HISTORY ===
    @POST("history/sync")
    suspend fun syncHistory(@Body req: HistorySyncRequest): Response<GenericSuccessResponse>

    @GET("history/sound-capsule/{user_id}")
    suspend fun getSoundCapsule(@Path("user_id") userId: Int): Response<SoundCapsuleResponse>

    // === LOCATION PINS ===
    @Multipart
    @POST("pins")
    suspend fun createPin(
        @Part("user_id") userId: RequestBody,
        @Part("song_id") songId: RequestBody,
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<PinDto>

    @GET("pins")
    suspend fun getNearbyPins(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double
    ): Response<List<PinDto>>

    // === COLLABORATIVE CLUB PLAYLISTS ===
    @FormUrlEncoded
    @POST("club/create")
    suspend fun createClub(
        @Field("user_id") userId: Int,
        @Field("username") username: String,
        @Field("name") name: String,
        @Field("description") description: String = ""
    ): Response<ClubCreateResponse>

    @FormUrlEncoded
    @POST("club/join")
    suspend fun joinClub(
        @Field("user_id") userId: Int,
        @Field("username") username: String,
        @Field("code") code: String
    ): Response<ClubJoinResponse>

    @GET("club/my-clubs")
    suspend fun getMyClubs(
        @Query("user_id") userId: Int
    ): Response<ClubListResponse>

    @GET("club/{club_id}")
    suspend fun getClubDetails(
        @Path("club_id") clubId: Int,
        @Query("user_id") userId: Int? = null
    ): Response<ClubDetailsResponse>

    @FormUrlEncoded
    @POST("club/add-song")
    suspend fun addSongToClub(
        @Field("club_id") clubId: Int,
        @Field("user_id") userId: Int,
        @Field("username") username: String,
        @Field("song_id") songId: Int
    ): Response<GenericSuccessResponse>

    @FormUrlEncoded
    @POST("club/remove-song")
    suspend fun removeSongFromClub(
        @Field("club_id") clubId: Int,
        @Field("user_id") userId: Int,
        @Field("song_id") songId: Int
    ): Response<GenericSuccessResponse>

    @FormUrlEncoded
    @POST("club/leave")
    suspend fun leaveClub(
        @Field("club_id") clubId: Int,
        @Field("user_id") userId: Int
    ): Response<ClubLeaveResponse>

    @FormUrlEncoded
    @POST("club/delete")
    suspend fun deleteClub(
        @Field("club_id") clubId: Int,
        @Field("user_id") userId: Int
    ): Response<GenericSuccessResponse>
}

// ----------------- LOCATION PINS MODELS -----------------

data class PinDto(
    val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("song_id") val songId: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerializedName("distance_meters") val distanceMeters: Double = 0.0,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    val song: SongDto? = null
)

// ----------------- CLUB MODELS -----------------

data class ClubCreateResponse(
    val success: Boolean = false,
    @SerializedName("club_id") val clubId: Int = 0,
    val code: String = "",
    val name: String = "",
    @SerializedName("owner_user_id") val ownerUserId: Int = 0,
    @SerializedName("owner_username") val ownerUsername: String = "",
    @SerializedName("join_link") val joinLink: String? = null
)

data class ClubJoinResponse(
    val success: Boolean = false,
    @SerializedName("club_id") val clubId: Int = 0,
    val code: String = "",
    val name: String = "",
    @SerializedName("owner_user_id") val ownerUserId: Int = 0,
    @SerializedName("owner_username") val ownerUsername: String = ""
)

data class ClubMemberDto(
    @SerializedName("user_id") val userId: Int = 0,
    val username: String = "",
    @SerializedName("is_owner") val isOwner: Boolean = false
)

data class ClubSongDto(
    val id: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    @SerializedName("audio_url") val audioUrl: String? = null,
    @SerializedName("cover_url") val coverUrl: String? = null,
    @SerializedName("dominant_color") val dominantColor: String? = null,
    val duration: Double = 0.0,
    @SerializedName("added_by_user_id") val addedByUserId: Int = 0,
    @SerializedName("added_by_username") val addedByUsername: String? = null,
    val position: Int = 0,
    val artists: List<SongArtistCreditDto>? = null
)

data class ClubSummaryDto(
    @SerializedName("club_id") val clubId: Int = 0,
    val code: String = "",
    val name: String = "",
    @SerializedName("owner_user_id") val ownerUserId: Int = 0,
    @SerializedName("owner_username") val ownerUsername: String = "",
    @SerializedName("is_owner") val isOwner: Boolean = false,
    @SerializedName("member_count") val memberCount: Int = 1,
    @SerializedName("song_count") val songCount: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("preview_covers") val previewCovers: List<String> = emptyList()
)

data class ClubListResponse(
    val success: Boolean = false,
    val clubs: List<ClubSummaryDto> = emptyList()
)

data class ClubDetailsResponse(
    val success: Boolean = false,
    @SerializedName("club_id") val clubId: Int = 0,
    val code: String = "",
    val name: String = "",
    @SerializedName("owner_user_id") val ownerUserId: Int = 0,
    @SerializedName("owner_username") val ownerUsername: String = "",
    @SerializedName("is_owner") val isOwner: Boolean = false,
    val members: List<ClubMemberDto> = emptyList(),
    val songs: List<ClubSongDto> = emptyList()
)

data class ClubLeaveResponse(
    val success: Boolean = false,
    val deleted: Boolean = false,
    val message: String? = null
)

data class NotificationDto(
    val id: Int = 0,
    val title: String = "",
    val message: String = "",
    @SerializedName("recipient_type") val recipientType: String = "",
    @SerializedName("target_user_ids") val targetUserIds: String? = null,
    @SerializedName("target_usernames") val targetUsernames: String? = null,
    @SerializedName("sent_count") val sentCount: Int = 0,
    @SerializedName("created_at") val createdAt: String = ""
)

data class RegisterTokenRequestDto(
    @SerializedName("user_id") val userId: Int? = null,
    @SerializedName("fcm_token") val fcmToken: String,
    @SerializedName("device_info") val deviceInfo: String? = ""
)


