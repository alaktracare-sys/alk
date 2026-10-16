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
    val email: String
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

data class SongDto(
    val id: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    @SerializedName("audio_url", alternate = ["audioUrl"]) val audioUrl: String? = null,
    @SerializedName("cover_url", alternate = ["coverUrl"]) val coverUrl: String? = null,
    @SerializedName("dominant_color", alternate = ["dominantColor"]) val dominantColor: String? = null,
    val duration: Double = 0.0
)

// ----------------- PLAYLIST MODELS -----------------

data class ServerPlaylistDto(
    val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    val name: String? = null,
    @SerializedName("is_system") val isSystem: Int = 0
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
    @SerializedName("played_at") val playedAt: Long
)

data class HistorySyncRequest(
    @SerializedName("user_id") val userId: Int,
    val events: List<HistoryEventDto>
)

data class ServerHistoryItemDto(
    @SerializedName("song_id") val songId: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    @SerializedName("audio_url") val audioUrl: String? = null,
    @SerializedName("cover_url") val coverUrl: String? = null,
    val duration: Double = 0.0,
    @SerializedName("listened_seconds") val listenedSeconds: Long = 0L,
    @SerializedName("played_at") val playedAt: Long = 0L
)

data class TodayListenTimeResponse(
    @SerializedName("user_id") val userId: Int = 0,
    val date: String? = null,
    @SerializedName("today_seconds") val todaySeconds: Long = 0L,
    val formatted: String? = null,
    val hours: Long = 0L,
    val minutes: Long = 0L
)

data class UserSongPlayDto(
    @SerializedName("song_id") val songId: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    @SerializedName("cover_url") val coverUrl: String? = null,
    @SerializedName("play_count") val playCount: Int = 0,
    val year: Int = 0,
    @SerializedName("last_played_at") val lastPlayedAt: String? = null
)

// ----------------- RETROFIT INTERFACE -----------------

interface MusicApiService {

    // === HEALTH & SYSTEM ===
    @GET("health")
    suspend fun healthCheck(): Response<Map<String, Any>>

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

    // === LISTENING HISTORY & STATS ===
    @POST("history/sync")
    suspend fun syncHistory(@Body req: HistorySyncRequest): Response<GenericSuccessResponse>

    @GET("history/{user_id}")
    suspend fun getUserHistory(
        @Path("user_id") userId: Int,
        @Query("limit") limit: Int = 100
    ): Response<List<ServerHistoryItemDto>>

    @GET("history/today/{user_id}")
    suspend fun getTodayListenTime(@Path("user_id") userId: Int): Response<TodayListenTimeResponse>

    @GET("history/plays/{user_id}")
    suspend fun getUserSongPlays(@Path("user_id") userId: Int): Response<List<UserSongPlayDto>>

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

