package com.example.data.repository

import android.content.Context
import com.example.BuildConfig
import com.example.data.DownloadManager
import com.example.data.local.AppDatabase
import com.example.data.preferences.AuthPreferences
import com.example.data.remote.*
import com.example.model.LocationPin
import com.example.model.Playlist
import com.example.model.Song
import com.example.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Cache
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

import com.example.data.local.ListeningHistoryEntity
import com.example.data.local.PlaylistDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.SongEntity
import com.example.data.local.UserDao
import com.example.data.local.UserEntity
import com.example.data.remote.cache.ApiCacheConfig
import com.example.data.remote.cache.ApiCacheManager
import com.example.data.remote.cache.OkHttpCacheInterceptor
import com.example.data.remote.cache.OfflineCacheInterceptor
import com.example.data.remote.cache.NetworkCacheInterceptor
import com.example.data.fuse.FuseCodeCodec
import com.example.data.fuse.FuseJoinResult
import com.example.data.fuse.FusePollStatus
import com.example.data.fuse.FuseSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MusicRepository(private val context: Context) {
    val authPreferences = AuthPreferences(context)
    val downloadManager = DownloadManager(context)
    val apiCacheManager = ApiCacheManager()
    private val appDatabase = AppDatabase.getInstance(context)
    private val songDao = appDatabase.songDao()
    private val playlistDao = appDatabase.playlistDao()
    private val userDao = appDatabase.userDao()
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var activeFuseSession: FuseSession? = null

    @Volatile
    private var activeFuseExpiryJob: Job? = null

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            com.example.util.NetworkMonitor.observeNetwork(context)
                .distinctUntilChanged()
                .collect { isOnline ->
                    if (isOnline) {
                        if (authPreferences.hasPendingOfflineActions()) {
                            syncOfflineActions()
                        }
                        // Sync history if 6 hr mark was reached or missed while offline
                        syncHistoryIfDue()
                    }
                }
        }

        // Periodic check to trigger 6-hour sync while online
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (true) {
                kotlinx.coroutines.delay(15L * 60L * 1000L) // 15-minute interval check
                try {
                    if (com.example.util.NetworkMonitor.isOnline(context)) {
                        syncHistoryIfDue()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val listeningHistory: Flow<List<ListeningHistoryEntity>> = appDatabase.getAllHistory()
    val downloadedSongsFlow: Flow<List<Song>> = songDao.getAllDownloadedSongs().map { list ->
        val likedIds = authPreferences.getLikedSongIds()
        list.map { entity ->
            entity.toSong().copy(isLiked = likedIds.contains(entity.songId))
        }
    }
    val cachedSongsFlow: Flow<List<Song>> = songDao.getAllCachedSongsFlow().map { list ->
        val likedIds = authPreferences.getLikedSongIds()
        list.map { it.toSong().copy(isLiked = likedIds.contains(it.id)) }
    }
    val cachedUserFlow: Flow<User?> = userDao.getUserFlow(getEffectiveUserId()).map { it?.toUser() }
    val cachedPlaylistsFlow: Flow<List<Playlist>> = playlistDao.getUserPlaylistsFlow(getEffectiveUserId()).map { list ->
        list.filter { !FuseCodeCodec.isPendingMarker(it.name) }.map { it.toPlaylist() }
    }

    suspend fun recordSongPlayed(song: Song, initialListenedSec: Long = 0L): Long {
        return appDatabase.insertListeningHistory(
            ListeningHistoryEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                audioUrl = song.audioUrl,
                coverUrl = song.coverUrl,
                duration = song.duration,
                listenedSeconds = initialListenedSec,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateListeningDuration(id: Long, listenedSeconds: Long) {
        appDatabase.updateListeningDuration(id, listenedSeconds)
    }

    suspend fun deleteHistoryItem(id: Long) {
        appDatabase.deleteHistoryItem(id)
    }

    suspend fun clearListeningHistory() {
        appDatabase.clearAllHistory()
    }

    @Volatile
    private var cachedBaseUrl: String = authPreferences.getServerBaseUrl()

    @Volatile
    private var apiInstance: MusicApiService? = null

    @Volatile
    var lastPinSyncOffline: Boolean = false
        private set

    @Volatile
    private var lastUnreachableAtMs: Long = 0L

    @Volatile
    private var lastUnreachableUrl: String = ""

    private fun shouldSkipUnreachableServer(forceNetwork: Boolean): Boolean {
        if (forceNetwork) return false
        val url = authPreferences.getServerBaseUrl()
        val elapsed = System.currentTimeMillis() - lastUnreachableAtMs
        return lastUnreachableAtMs > 0L && url == lastUnreachableUrl && elapsed in 0 until UNREACHABLE_COOLDOWN_MS
    }

    private fun markServerUnreachable() {
        lastUnreachableUrl = authPreferences.getServerBaseUrl()
        lastUnreachableAtMs = System.currentTimeMillis()
    }

    private fun markServerReachable() {
        lastUnreachableAtMs = 0L
        lastUnreachableUrl = ""
    }

    private val api: MusicApiService
        get() {
            val currentUrl = authPreferences.getServerBaseUrl()
            val existing = apiInstance
            if (existing != null && currentUrl == cachedBaseUrl) {
                return existing
            }
            return synchronized(this) {
                val current = apiInstance
                if (current != null && currentUrl == cachedBaseUrl) {
                    current
                } else {
                    cachedBaseUrl = currentUrl
                    val newApi = buildApiService(currentUrl)
                    apiInstance = newApi
                    newApi
                }
            }
        }

    private fun buildApiService(baseUrl: String): MusicApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        val httpCacheDir = File(context.cacheDir, ApiCacheConfig.HTTP_CACHE_DIR)
        val httpCache = Cache(httpCacheDir, ApiCacheConfig.HTTP_CACHE_MAX_BYTES)

        val client = OkHttpClient.Builder()
            .cache(httpCache)
            .addInterceptor(logging)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MusicApiService::class.java)
    }

    fun sanitizeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        val configuredBase = authPreferences.getServerBaseUrl().trimEnd('/')
        return if (rawUrl.startsWith("http://100.65.126.106:8000")) {
            rawUrl.replace("http://100.65.126.106:8000", configuredBase)
        } else if (rawUrl.startsWith("http://localhost:8000")) {
            rawUrl.replace("http://localhost:8000", configuredBase)
        } else if (rawUrl.startsWith("http://127.0.0.1:8000")) {
            rawUrl.replace("http://127.0.0.1:8000", configuredBase)
        } else if (rawUrl.startsWith("/")) {
            "$configuredBase$rawUrl"
        } else {
            rawUrl
        }
    }

    private fun getEffectiveUserId(): Int {
        val user = authPreferences.getUser()
        return user?.id ?: 1
    }

    // Default sample songs used when server is initially empty or offline (kept empty for real data only)
    private val defaultSongs = emptyList<Song>()

    // ==========================================
    // AUTHENTICATION
    // ==========================================

    fun updateServerUrl(newUrl: String) {
        authPreferences.setServerBaseUrl(newUrl)
        markServerReachable()
        lastPinSyncOffline = false
        synchronized(this) {
            cachedBaseUrl = authPreferences.getServerBaseUrl()
            apiInstance = buildApiService(cachedBaseUrl)
        }
    }

    suspend fun testUrl(testBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val testApi = buildApiService(testBaseUrl)
            val healthResp = try { testApi.healthCheck() } catch (_: Exception) { null }
            if (healthResp != null && (healthResp.isSuccessful || healthResp.code() in 200..399)) {
                return@withContext true
            }
            val songsResp = try { testApi.getSongs() } catch (_: Exception) { null }
            if (songsResp != null && (songsResp.isSuccessful || songsResp.code() in 200..399)) {
                return@withContext true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. First probe the dedicated /health endpoint
            val healthResp = try { api.healthCheck() } catch (_: Exception) { null }
            if (healthResp != null && (healthResp.isSuccessful || healthResp.code() in 200..399)) {
                return@withContext true
            }

            // 2. Next probe the songs catalog endpoint
            val songsResp = try { api.getSongs() } catch (_: Exception) { null }
            if (songsResp != null && (songsResp.isSuccessful || songsResp.code() in 200..399)) {
                return@withContext true
            }

            // 3. Fall back to playlist endpoint
            val plResp = try { api.getPlaylists(getEffectiveUserId()) } catch (_: Exception) { null }
            if (plResp != null && (plResp.isSuccessful || plResp.code() in 200..399)) {
                return@withContext true
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun login(identifier: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val response = api.login(LoginRequest(identifier.trim(), password.trim()))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val user = User(
                    id = body.userId,
                    username = body.username,
                    email = body.email
                )
                authPreferences.saveUser(user.id, user.username, user.email)
                userDao.insertUser(UserEntity.fromUser(user))

                // Retrieve and cache Liked Playlist ID
                try {
                    val likedResp = api.getLikedPlaylist(user.id)
                    if (likedResp.isSuccessful && likedResp.body()?.playlistId != null) {
                        authPreferences.setLikedPlaylistId(likedResp.body()!!.playlistId!!)
                    }
                } catch (_: Exception) {}

                // Sync liked song IDs and pending offline actions
                syncLikedSongs(user.id)
                syncOfflineActions()

                // If user is already registered, fetch history data from server
                try {
                    fetchAndMergeServerHistory(user.id)
                    syncHistoryIfDue(force = true)
                } catch (_: Exception) {}

                return@withContext Result.success(user)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                return@withContext Result.failure(Exception(errorMsg ?: "Invalid credentials"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: "Failed to connect to Tailscale server"))
        }
    }

    suspend fun signup(
        username: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.signup(
                SignupRequest(
                    username = username.trim(),
                    email = email.trim(),
                    password = password.trim(),
                    confirmPassword = confirmPassword.trim()
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val msg = response.body()!!.message ?: "OTP sent to your email"
                return@withContext Result.success(msg)
            } else {
                val err = parseErrorMessage(response.errorBody()?.string())
                return@withContext Result.failure(Exception(err ?: "Signup failed"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: "Server unreachable"))
        }
    }

    suspend fun verifyOtp(email: String, otp: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.verifyOtp(VerifyOtpRequest(email.trim(), otp.trim()))
            if (response.isSuccessful) {
                val msg = response.body()?.message ?: "Account verified successfully"
                return@withContext Result.success(msg)
            } else {
                val err = parseErrorMessage(response.errorBody()?.string())
                return@withContext Result.failure(Exception(err ?: "Invalid OTP"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: "Verification failed"))
        }
    }

    suspend fun resendOtp(email: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.resendOtp(ResendOtpRequest(email.trim()))
            if (response.isSuccessful && response.body() != null) {
                val msg = response.body()?.message ?: "New OTP sent to your email"
                return@withContext Result.success(msg)
            } else {
                val err = parseErrorMessage(response.errorBody()?.string())
                return@withContext Result.failure(Exception(err ?: "Failed to resend OTP"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: "Server unreachable"))
        }
    }

    private suspend fun syncLikedSongs(userId: Int) {
        try {
            val likedPlaylistId = getOrFetchLikedPlaylistId(userId)
            if (likedPlaylistId != null) {
                val resp = api.getPlaylistSongs(likedPlaylistId)
                if (resp.isSuccessful && resp.body() != null) {
                    val ids = resp.body()!!.map { it.id }.toSet()
                    ids.forEach { authPreferences.setSongLiked(it, true) }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun getOrFetchLikedPlaylistId(userId: Int): Int? {
        val cached = authPreferences.getLikedPlaylistId()
        if (cached > 0) return cached

        return try {
            val resp = api.getLikedPlaylist(userId)
            if (resp.isSuccessful && resp.body()?.playlistId != null) {
                val id = resp.body()!!.playlistId!!
                authPreferences.setLikedPlaylistId(id)
                id
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================
    // SONGS (Offline-First Catalog & Search)
    // ==========================================

    suspend fun getSongs(query: String? = null, forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        if (!query.isNullOrBlank()) {
            return@withContext searchSongs(query = query, forceRefresh = forceRefresh)
        }

        val likedIds = authPreferences.getLikedSongIds()

        // 1. In-memory bounded feed cache check (avoids repeating API calls within TTL)
        if (!forceRefresh) {
            val cachedFeed = apiCacheManager.getFeed("home_feed")
            if (cachedFeed != null) {
                val withLikes = cachedFeed.map { it.copy(isLiked = likedIds.contains(it.id)) }
                return@withContext attachLocalPaths(withLikes)
            }
        }

        // 2. Fetch fresh catalog from network
        val cachedEntities = songDao.getAllCachedSongs()
        val localSongs = cachedEntities.map { it.toSong().copy(isLiked = likedIds.contains(it.id)) }

        if (!shouldSkipUnreachableServer(forceRefresh) && com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val response = api.getSongs()
                markServerReachable()
                if (response.code() == 304) {
                    authPreferences.setLastSyncTime("songs")
                    if (localSongs.isNotEmpty()) {
                        return@withContext attachLocalPaths(localSongs)
                    }
                } else if (response.isSuccessful && response.body() != null) {
                    val remoteSongs = response.body()!!.map { dto ->
                        Song(
                            id = dto.id,
                            title = dto.title ?: "Untitled Track",
                            artist = dto.artist ?: "Unknown Artist",
                            audioUrl = sanitizeUrl(dto.audioUrl),
                            coverUrl = sanitizeUrl(dto.coverUrl),
                            duration = dto.duration,
                            isLiked = likedIds.contains(dto.id),
                            dominantColorHex = dto.dominantColor
                        )
                    }
                    apiCacheManager.putFeed("home_feed", remoteSongs)
                    songDao.insertCachedSongs(remoteSongs.map { SongEntity.fromSong(it) })
                    authPreferences.setLastSyncTime("songs")
                    return@withContext attachLocalPaths(remoteSongs)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.javaClass.name.contains("Cancellation")) throw e
                if (e is java.io.IOException) markServerUnreachable()
                // Network is unavailable or server error: continue using local cached songs
            }
        }

        // 3. Return local cached songs if available
        if (localSongs.isNotEmpty()) {
            return@withContext attachLocalPaths(localSongs)
        }

        // No bogus sample data fallback: return empty list
        emptyList()
    }

    suspend fun searchSongs(
        query: String,
        page: Int = 1,
        limit: Int = 30,
        offset: Int = 0,
        filter: String? = null,
        sort: String? = null,
        forceRefresh: Boolean = false
    ): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val q = query.trim()
        val actualOffset = if (offset > 0) offset else (page - 1) * limit
        val searchKey = ApiCacheConfig.buildSearchCacheKey(
            query = q,
            page = page,
            limit = limit,
            offset = actualOffset,
            filter = filter,
            sort = sort
        )

        // 1. Fast in-memory bounded search cache check
        if (!forceRefresh) {
            val cached = apiCacheManager.getSearch(searchKey)
            if (cached != null) {
                val withLikes = cached.map { it.copy(isLiked = likedIds.contains(it.id)) }
                return@withContext attachLocalPaths(withLikes)
            }
        }

        // 2. Network query
        try {
            val response = api.searchSongs(query = q, limit = limit, offset = actualOffset)
            if (response.isSuccessful && response.body() != null) {
                val songs = response.body()!!.map { dto ->
                    Song(
                        id = dto.id,
                        title = dto.title ?: "Untitled Track",
                        artist = dto.artist ?: "Unknown Artist",
                        audioUrl = sanitizeUrl(dto.audioUrl),
                        coverUrl = sanitizeUrl(dto.coverUrl),
                        duration = dto.duration,
                        isLiked = likedIds.contains(dto.id),
                        dominantColorHex = dto.dominantColor
                    )
                }
                apiCacheManager.putSearch(searchKey, songs)
                if (songs.isNotEmpty()) {
                    songDao.insertCachedSongs(songs.map { SongEntity.fromSong(it) })
                }
                return@withContext attachLocalPaths(songs)
            }
        } catch (_: Exception) {}

        // 3. Offline search from local SQLite database
        val cachedMatches = songDao.searchCachedSongs(q)
        if (cachedMatches.isNotEmpty()) {
            val songs = cachedMatches.map { it.toSong().copy(isLiked = likedIds.contains(it.id)) }
            return@withContext attachLocalPaths(songs)
        }

        val fallback = defaultSongs.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }.map { it.copy(isLiked = likedIds.contains(it.id)) }
        attachLocalPaths(fallback)
    }

    suspend fun getArtistSongs(artist: String, forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val key = ApiCacheConfig.buildArtistCacheKey(artist)
        if (!forceRefresh) {
            val cached = apiCacheManager.getArtist(key)
            if (cached != null) {
                return@withContext attachLocalPaths(cached.map { it.copy(isLiked = likedIds.contains(it.id)) })
            }
        }

        // Fetch matching artist tracks via search API
        try {
            val response = api.searchSongs(query = artist, limit = 50, offset = 0)
            if (response.isSuccessful && response.body() != null) {
                val songs = response.body()!!.map { dto ->
                    Song(
                        id = dto.id,
                        title = dto.title ?: "Untitled Track",
                        artist = dto.artist ?: "Unknown Artist",
                        audioUrl = sanitizeUrl(dto.audioUrl),
                        coverUrl = sanitizeUrl(dto.coverUrl),
                        duration = dto.duration,
                        isLiked = likedIds.contains(dto.id)
                    )
                }.filter { it.artist.contains(artist, ignoreCase = true) }

                if (songs.isNotEmpty()) {
                    apiCacheManager.putArtist(key, songs)
                    songDao.insertCachedSongs(songs.map { SongEntity.fromSong(it) })
                    return@withContext attachLocalPaths(songs)
                }
            }
        } catch (_: Exception) {}

        // Fallback: local cached songs matching artist
        val localMatches = songDao.searchCachedSongs(artist)
            .filter { it.artist.contains(artist, ignoreCase = true) }
            .map { it.toSong().copy(isLiked = likedIds.contains(it.id)) }
        if (localMatches.isNotEmpty()) {
            apiCacheManager.putArtist(key, localMatches)
            return@withContext attachLocalPaths(localMatches)
        }

        val fallback = defaultSongs.filter { it.artist.contains(artist, ignoreCase = true) }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }
        attachLocalPaths(fallback)
    }

    suspend fun getAlbumSongs(album: String, forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val key = ApiCacheConfig.buildAlbumCacheKey(album)
        if (!forceRefresh) {
            val cached = apiCacheManager.getAlbum(key)
            if (cached != null) {
                return@withContext attachLocalPaths(cached.map { it.copy(isLiked = likedIds.contains(it.id)) })
            }
        }

        try {
            val response = api.searchSongs(query = album, limit = 50, offset = 0)
            if (response.isSuccessful && response.body() != null) {
                val songs = response.body()!!.map { dto ->
                    Song(
                        id = dto.id,
                        title = dto.title ?: "Untitled Track",
                        artist = dto.artist ?: "Unknown Artist",
                        audioUrl = sanitizeUrl(dto.audioUrl),
                        coverUrl = sanitizeUrl(dto.coverUrl),
                        duration = dto.duration,
                        isLiked = likedIds.contains(dto.id)
                    )
                }
                if (songs.isNotEmpty()) {
                    apiCacheManager.putAlbum(key, songs)
                    songDao.insertCachedSongs(songs.map { SongEntity.fromSong(it) })
                    return@withContext attachLocalPaths(songs)
                }
            }
        } catch (_: Exception) {}

        val localMatches = songDao.searchCachedSongs(album)
            .map { it.toSong().copy(isLiked = likedIds.contains(it.id)) }
        if (localMatches.isNotEmpty()) {
            apiCacheManager.putAlbum(key, localMatches)
            return@withContext attachLocalPaths(localMatches)
        }

        val fallback = defaultSongs.filter { it.title.contains(album, ignoreCase = true) }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }
        attachLocalPaths(fallback)
    }

    suspend fun getRecommendations(seedSongId: Int? = null, forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val key = ApiCacheConfig.buildRecommendationsCacheKey(userId, seedSongId)
        val likedIds = authPreferences.getLikedSongIds()

        if (!forceRefresh) {
            val cached = apiCacheManager.getRecommendations(key)
            if (cached != null) {
                return@withContext attachLocalPaths(cached.map { it.copy(isLiked = likedIds.contains(it.id)) })
            }
        }

        // Generate recommendations from catalog
        val allCatalog = getCachedSongs()
        val recs = if (seedSongId != null) {
            val seed = allCatalog.find { it.id == seedSongId }
            if (seed != null) {
                allCatalog.filter { it.id != seedSongId && (it.artist.equals(seed.artist, ignoreCase = true) || it.title.length % 2 == seed.title.length % 2) }
            } else allCatalog.shuffled()
        } else {
            allCatalog.shuffled()
        }
        val result = if (recs.isNotEmpty()) recs else allCatalog
        apiCacheManager.putRecommendations(key, result)
        attachLocalPaths(result.map { it.copy(isLiked = likedIds.contains(it.id)) })
    }

    suspend fun getRecentlyPlayedSongs(forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val key = ApiCacheConfig.buildRecentlyPlayedCacheKey(userId)
        val likedIds = authPreferences.getLikedSongIds()

        if (!forceRefresh) {
            val cached = apiCacheManager.getRecentlyPlayed(key)
            if (cached != null) {
                return@withContext attachLocalPaths(cached.map { it.copy(isLiked = likedIds.contains(it.id)) })
            }
        }

        val history = appDatabase.getAllHistoryList()
        val songs = history.map {
            Song(
                id = it.songId,
                title = it.title,
                artist = it.artist,
                audioUrl = it.audioUrl,
                coverUrl = it.coverUrl,
                duration = it.duration,
                isLiked = likedIds.contains(it.songId)
            )
        }.distinctBy { it.id }

        if (songs.isNotEmpty()) {
            apiCacheManager.putRecentlyPlayed(key, songs)
        }
        attachLocalPaths(songs)
    }

    suspend fun getCachedSongs(): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val inMemoryFeed = apiCacheManager.getFeed("home_feed")
        if (inMemoryFeed != null && inMemoryFeed.isNotEmpty()) {
            return@withContext attachLocalPaths(inMemoryFeed.map { it.copy(isLiked = likedIds.contains(it.id)) })
        }
        val cachedEntities = songDao.getAllCachedSongs()
        val songs = cachedEntities.map { it.toSong().copy(isLiked = likedIds.contains(it.id)) }
        if (songs.isNotEmpty()) {
            attachLocalPaths(songs)
        } else {
            emptyList()
        }
    }

    suspend fun uploadSong(
        title: String,
        artist: String,
        audioBytes: ByteArray,
        audioFileName: String,
        coverBytes: ByteArray,
        coverFileName: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val titlePart = title.trim().toRequestBody("text/plain".toMediaTypeOrNull())
            val artistPart = artist.trim().toRequestBody("text/plain".toMediaTypeOrNull())

            val audioBody = audioBytes.toRequestBody("audio/mpeg".toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData("audio", audioFileName, audioBody)

            val coverBody = coverBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val coverPart = MultipartBody.Part.createFormData("cover", coverFileName, coverBody)

            val response = api.uploadSong(titlePart, artistPart, audioPart, coverPart)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                val err = response.body()?.error ?: "Upload failed"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Upload failed"))
        }
    }

    // ==========================================
    // PLAYLISTS (Offline-First Storage & Sync)
    // ==========================================

    suspend fun getPlaylists(forceRefresh: Boolean = false): List<Playlist> = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        if (!forceRefresh) {
            val inMemory = apiCacheManager.getUserPlaylists(userId)
            if (inMemory != null) {
                return@withContext inMemory.filter { !FuseCodeCodec.isPendingMarker(it.name) }
            }
        }

        val localEntities = playlistDao.getUserPlaylists(userId)
        val localCountById = localEntities.associate { it.id to it.songCount }
        val localPlaylists = localEntities
            .filter { !FuseCodeCodec.isPendingMarker(it.name) }
            .map { it.toPlaylist() }

        val pendingDeletedIds = authPreferences.getOfflineQueue()
            .filter { it.optString("type") == "delete_playlist" }
            .map { it.optInt("playlistId") }
            .toSet()
        val pendingLocalPlaylists = localPlaylists.filter { it.id < 0 && !pendingDeletedIds.contains(it.id) }

        try {
            val response = api.getPlaylists(userId)
            if (response.code() == 304) {
                authPreferences.setLastSyncTime("playlists")
                val activeLocal = localPlaylists.filter { !pendingDeletedIds.contains(it.id) }
                if (activeLocal.isNotEmpty()) {
                    return@withContext activeLocal
                }
            } else if (response.isSuccessful && response.body() != null) {
                val nowMs = System.currentTimeMillis()
                val rawBody = response.body()!!

                // Opportunistically clean up any expired pending Fuse markers on the server
                val expiredMarkerIds = rawBody.mapNotNull { dto ->
                    val marker = FuseCodeCodec.parsePendingMarker(dto.name)
                    if (marker != null && marker.isExpired(nowMs)) dto.id else null
                }
                if (expiredMarkerIds.isNotEmpty()) {
                    repoScope.launch {
                        expiredMarkerIds.forEach { expiredId ->
                            try { api.deletePlaylist(expiredId) } catch (_: Exception) {}
                            try { playlistDao.deletePlaylist(expiredId) } catch (_: Exception) {}
                        }
                    }
                }

                val userPlaylists = rawBody.filter {
                    it.isSystem == 0 &&
                        !pendingDeletedIds.contains(it.id) &&
                        !FuseCodeCodec.isPendingMarker(it.name)
                }
                val entities = userPlaylists.map { p ->
                    val cleanName = p.name ?: "Playlist"
                    PlaylistEntity(
                        id = p.id,
                        userId = userId,
                        name = cleanName,
                        description = if (FuseCodeCodec.isFusePlaylistName(cleanName)) "Fuse Playlist" else "Playlist",
                        isSystem = false,
                        songCount = localCountById[p.id] ?: 0,
                        lastSyncedAt = nowMs
                    )
                }
                playlistDao.insertPlaylists(entities)
                val mapped = entities.map { it.toPlaylist() } + pendingLocalPlaylists
                apiCacheManager.putUserPlaylists(userId, mapped)
                authPreferences.setLastSyncTime("playlists")
                return@withContext mapped
            }
        } catch (_: Exception) {}

        val active = localPlaylists.filter { !pendingDeletedIds.contains(it.id) }
        if (active.isNotEmpty()) {
            apiCacheManager.putUserPlaylists(userId, active)
        }
        active
    }

    suspend fun getCachedPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val inMemory = apiCacheManager.getUserPlaylists(userId)
        if (inMemory != null && inMemory.isNotEmpty()) {
            return@withContext inMemory.filter { !FuseCodeCodec.isPendingMarker(it.name) }
        }
        val localEntities = playlistDao.getUserPlaylists(userId)
        val mapped = localEntities
            .filter { !FuseCodeCodec.isPendingMarker(it.name) }
            .map { it.toPlaylist() }
        if (mapped.isNotEmpty()) {
            apiCacheManager.putUserPlaylists(userId, mapped)
        }
        mapped
    }

    suspend fun createPlaylist(name: String): Playlist = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val tempId = -((System.currentTimeMillis() % 900000 + 100000).toInt())
        val trimmedName = name.trim().ifBlank { "New Playlist" }
        apiCacheManager.invalidateUserPlaylists(userId)

        val localPl = Playlist(
            id = tempId,
            name = trimmedName,
            description = "Playlist",
            songCount = 0
        )
        playlistDao.insertPlaylist(PlaylistEntity.fromPlaylist(localPl, userId))

        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (isOnline) {
            try {
                val response = api.createPlaylist(userId = userId, name = trimmedName)
                if (response.isSuccessful && response.body() != null && response.body()?.id != null) {
                    val serverId = response.body()!!.id!!
                    val finalName = response.body()!!.name ?: trimmedName
                    playlistDao.remapPlaylistId(tempId, serverId)
                    playlistDao.updatePlaylistName(serverId, finalName)
                    val syncedPl = Playlist(
                        id = serverId,
                        name = finalName,
                        description = "Playlist",
                        songCount = 0
                    )
                    apiCacheManager.invalidateUserPlaylists(userId)
                    return@withContext syncedPl
                }
            } catch (_: Exception) {}
        }

        val action = JSONObject().apply {
            put("type", "create_playlist")
            put("userId", userId)
            put("name", trimmedName)
            put("tempId", tempId)
            put("timestamp", System.currentTimeMillis())
        }
        authPreferences.addOfflineAction(action)
        localPl
    }

    suspend fun renamePlaylist(playlistId: Int, newName: String): Playlist = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        apiCacheManager.invalidateUserPlaylists(userId)
        playlistDao.updatePlaylistName(playlistId, newName.trim())
        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (isOnline) {
            try {
                val response = api.renamePlaylist(
                    playlistId = playlistId,
                    userId = userId,
                    name = newName.trim()
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    return@withContext Playlist(
                        id = playlistId,
                        name = body.name ?: newName,
                        description = "Playlist"
                    )
                }
            } catch (_: Exception) {}
        }

        val action = JSONObject().apply {
            put("type", "rename_playlist")
            put("playlistId", playlistId)
            put("userId", userId)
            put("name", newName.trim())
            put("timestamp", System.currentTimeMillis())
        }
        authPreferences.addOfflineAction(action)
        Playlist(id = playlistId, name = newName)
    }

    suspend fun deletePlaylist(playlistId: Int) = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        apiCacheManager.invalidateUserPlaylists(userId)
        apiCacheManager.invalidatePlaylistSongs(playlistId)
        playlistDao.deletePlaylist(playlistId)

        if (playlistId < 0) {
            // Cancel un-synced actions associated with this temp playlist
            val remaining = authPreferences.getOfflineQueue().filter { action ->
                val pId = action.optInt("playlistId", 0)
                val tId = action.optInt("tempId", 0)
                !(pId == playlistId || tId == playlistId)
            }
            authPreferences.setOfflineQueue(remaining)
            return@withContext
        }

        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (isOnline) {
            try {
                val resp = api.deletePlaylist(playlistId)
                if (resp.isSuccessful) return@withContext
            } catch (_: Exception) {}
        }

        val action = JSONObject().apply {
            put("type", "delete_playlist")
            put("playlistId", playlistId)
            put("timestamp", System.currentTimeMillis())
        }
        authPreferences.addOfflineAction(action)
    }

    suspend fun getPlaylistSongs(playlistId: Int, forceRefresh: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val userId = getEffectiveUserId()

        if (playlistId == Playlist.ID_DOWNLOADED || playlistId == -2) {
            return@withContext getDownloadedSongs()
        }

        if (playlistId == Playlist.ID_OFFLINE_BACKUP || playlistId == -3) {
            return@withContext getOfflineBackupSongs()
        }

        if (playlistId == Playlist.ID_LIKED || playlistId == -1) {
            val localLiked = songDao.getLikedSongs().map { it.toSong() }
            val actualLikedId = getOrFetchLikedPlaylistId(userId)
            if (actualLikedId != null) {
                try {
                    val response = api.getPlaylistSongs(actualLikedId)
                    if (response.isSuccessful && response.body() != null) {
                        val songs = response.body()!!.map { dto ->
                            Song(
                                id = dto.id,
                                title = dto.title ?: "Untitled Track",
                                artist = dto.artist ?: "Unknown Artist",
                                audioUrl = sanitizeUrl(dto.audioUrl),
                                coverUrl = sanitizeUrl(dto.coverUrl),
                                duration = dto.duration,
                                isLiked = true,
                                dominantColorHex = dto.dominantColor
                            )
                        }
                        songDao.insertCachedSongs(songs.map { SongEntity.fromSong(it, isLiked = true) })
                        return@withContext attachLocalPaths(songs)
                    }
                } catch (_: Exception) {}
            }
            return@withContext attachLocalPaths(localLiked)
        }

        // Custom playlist:
        if (!forceRefresh) {
            val inMemory = apiCacheManager.getPlaylistSongs(playlistId)
            if (inMemory != null) {
                val withLikes = inMemory.map { it.copy(isLiked = likedIds.contains(it.id)) }
                return@withContext attachLocalPaths(withLikes)
            }
        }

        val localCached = playlistDao.getPlaylistSongs(playlistId).map {
            it.toSong().copy(isLiked = likedIds.contains(it.id))
        }

        try {
            val response = api.getPlaylistSongs(playlistId)
            if (response.isSuccessful && response.body() != null) {
                val remoteSongs = response.body()!!.map { dto ->
                    Song(
                        id = dto.id,
                        title = dto.title ?: "Untitled Track",
                        artist = dto.artist ?: "Unknown Artist",
                        audioUrl = sanitizeUrl(dto.audioUrl),
                        coverUrl = sanitizeUrl(dto.coverUrl),
                        duration = dto.duration,
                        isLiked = likedIds.contains(dto.id),
                        dominantColorHex = dto.dominantColor
                    )
                }
                apiCacheManager.putPlaylistSongs(playlistId, remoteSongs)
                playlistDao.setPlaylistSongs(playlistId, remoteSongs.map { SongEntity.fromSong(it) })
                return@withContext attachLocalPaths(remoteSongs)
            }
        } catch (_: Exception) {}

        if (localCached.isNotEmpty()) {
            apiCacheManager.putPlaylistSongs(playlistId, localCached)
        }
        attachLocalPaths(localCached)
    }

    suspend fun getCachedPlaylistSongs(playlistId: Int): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        if (playlistId == Playlist.ID_DOWNLOADED || playlistId == -2) {
            return@withContext getDownloadedSongs()
        }
        if (playlistId == Playlist.ID_OFFLINE_BACKUP || playlistId == -3) {
            return@withContext getOfflineBackupSongs()
        }
        if (playlistId == Playlist.ID_LIKED || playlistId == -1) {
            return@withContext getCachedLikedSongs()
        }
        val localCached = playlistDao.getPlaylistSongs(playlistId).map {
            it.toSong().copy(isLiked = likedIds.contains(it.id))
        }
        attachLocalPaths(localCached)
    }

    /**
     * Spotify-style Offline Backup / Cached Songs Playlist:
     * Assembles all tracks present in the streaming cache memory (SimpleCache)
     * and permanent downloads, allowing offline listening with zero internet required.
     */
    suspend fun getOfflineBackupSongs(): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val cachedMemoryIds = com.example.audio.AudioCache.getCachedSongIds(context)
        val downloadedList = songDao.getDownloadedSongsList()
        val downloadedIds = downloadedList.map { it.songId }.toSet()
        val allOfflineIds = cachedMemoryIds + downloadedIds

        val catalogEntities = songDao.getAllCachedSongs()
        val historyEntities = appDatabase.getAllHistoryList()

        val knownSongs = mutableMapOf<Int, Song>()

        // 1. Default catalog songs
        defaultSongs.forEach { knownSongs[it.id] = it }

        // 2. Room cached songs
        catalogEntities.forEach { knownSongs[it.id] = it.toSong() }

        // 3. Listening history songs
        historyEntities.forEach {
            knownSongs[it.songId] = Song(
                id = it.songId,
                title = it.title,
                artist = it.artist,
                audioUrl = it.audioUrl,
                coverUrl = it.coverUrl,
                duration = it.duration
            )
        }

        // 4. Downloaded songs
        downloadedList.forEach {
            knownSongs[it.songId] = it.toSong()
        }

        // Filter ONLY songs that are completely downloaded or 100% fully cached in memory
        val offlineReady = knownSongs.values.filter { song ->
            val hasLocalFile = (song.localPath != null && File(song.localPath).exists() && File(song.localPath).length() > 0)
            val isDownloaded = downloadedIds.contains(song.id)
            val isFullyCached = com.example.audio.AudioCache.isSongFullyCached(context, song)
            hasLocalFile || isDownloaded || isFullyCached
        }.distinctBy { it.id }.toMutableList()

        // If no cached or downloaded songs exist, do NOT inject un-cached network songs when offline
        if (offlineReady.isEmpty() && downloadedList.isNotEmpty()) {
            offlineReady.addAll(downloadedList.map { it.toSong() })
        }

        val enriched = offlineReady.map { song ->
            song.copy(
                isLiked = likedIds.contains(song.id),
                isCached = true
            )
        }
        attachLocalPaths(enriched)
    }

    private suspend fun ensureSongCachedInDb(songId: Int) {
        if (songDao.getCachedSong(songId) != null) return
        val downloaded = songDao.getDownloadedSong(songId)
        if (downloaded != null) {
            songDao.insertCachedSong(
                SongEntity(
                    id = downloaded.songId,
                    title = downloaded.title,
                    artist = downloaded.artist,
                    audioUrl = downloaded.audioUrl,
                    coverUrl = downloaded.coverUrl,
                    duration = downloaded.duration,
                    isLiked = authPreferences.getLikedSongIds().contains(songId),
                    lastSyncedAt = System.currentTimeMillis()
                )
            )
            return
        }
        val fallback = defaultSongs.find { it.id == songId }
        if (fallback != null) {
            songDao.insertCachedSong(SongEntity.fromSong(fallback, authPreferences.getLikedSongIds().contains(songId)))
        }
    }

    suspend fun addSongToPlaylist(playlistId: Int, songId: Int) = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        apiCacheManager.invalidatePlaylistSongs(playlistId)
        apiCacheManager.invalidateUserPlaylists(userId)
        ensureSongCachedInDb(songId)
        playlistDao.addSongToPlaylist(playlistId, songId, position = 0)

        if (playlistId < 0) {
            val action = JSONObject().apply {
                put("type", "add_song")
                put("playlistId", playlistId)
                put("songId", songId)
                put("timestamp", System.currentTimeMillis())
            }
            authPreferences.addOfflineAction(action)
            return@withContext
        }

        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (isOnline) {
            try {
                val resp = api.addSongToPlaylist(playlistId = playlistId, songId = songId)
                if (resp.isSuccessful) return@withContext
            } catch (_: Exception) {}
        }

        val action = JSONObject().apply {
            put("type", "add_song")
            put("playlistId", playlistId)
            put("songId", songId)
            put("timestamp", System.currentTimeMillis())
        }
        authPreferences.addOfflineAction(action)
    }

    suspend fun removeSongFromPlaylist(playlistId: Int, songId: Int) = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        apiCacheManager.invalidatePlaylistSongs(playlistId)
        apiCacheManager.invalidateUserPlaylists(userId)
        playlistDao.removeSongFromPlaylist(playlistId, songId)

        if (playlistId < 0) {
            val queue = authPreferences.getOfflineQueue()
            val addIndex = queue.indexOfLast {
                it.optString("type") == "add_song" &&
                it.optInt("playlistId") == playlistId &&
                it.optInt("songId") == songId
            }
            if (addIndex != -1) {
                val mutable = queue.toMutableList()
                mutable.removeAt(addIndex)
                authPreferences.setOfflineQueue(mutable)
                return@withContext
            }
        }

        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (isOnline) {
            try {
                val resp = api.removeSongFromPlaylist(playlistId = playlistId, songId = songId)
                if (resp.isSuccessful) return@withContext
            } catch (_: Exception) {}
        }

        val action = JSONObject().apply {
            put("type", "remove_song")
            put("playlistId", playlistId)
            put("songId", songId)
            put("timestamp", System.currentTimeMillis())
        }
        authPreferences.addOfflineAction(action)
    }

    // ==========================================
    // LIKES / FAVORITES
    // ==========================================

    suspend fun toggleLike(song: Song): Boolean = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val currentlyLiked = authPreferences.getLikedSongIds().contains(song.id)
        val newStatus = !currentlyLiked
        authPreferences.setSongLiked(song.id, newStatus)

        // Ensure song is stored in SQLite songs table and marked liked
        val cached = songDao.getCachedSong(song.id)
        if (cached == null) {
            songDao.insertCachedSong(SongEntity.fromSong(song, isLiked = newStatus))
        } else {
            songDao.updateSongLiked(song.id, newStatus)
        }
        apiCacheManager.updateSongLike(song.id, newStatus)
        apiCacheManager.invalidatePlaylistSongs(Playlist.ID_LIKED)

        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (isOnline) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val likedPlaylistId = getOrFetchLikedPlaylistId(userId)
                    if (likedPlaylistId != null) {
                        if (newStatus) {
                            api.addSongToPlaylist(playlistId = likedPlaylistId, songId = song.id)
                        } else {
                            api.removeSongFromPlaylist(playlistId = likedPlaylistId, songId = song.id)
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            // Collapse or replace previous pending toggle_like for this song
            val existingQueue = authPreferences.getOfflineQueue().toMutableList()
            existingQueue.removeAll {
                it.optString("type") == "toggle_like" && it.optInt("songId") == song.id
            }
            val action = JSONObject().apply {
                put("type", "toggle_like")
                put("songId", song.id)
                put("newStatus", newStatus)
                put("userId", userId)
                put("timestamp", System.currentTimeMillis())
            }
            existingQueue.add(action)
            authPreferences.setOfflineQueue(existingQueue)
        }

        newStatus
    }

    suspend fun getLikedSongs(): List<Song> = withContext(Dispatchers.IO) {
        getPlaylistSongs(Playlist.ID_LIKED)
    }

    suspend fun getCachedLikedSongs(): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val localLiked = songDao.getLikedSongs().map { it.toSong().copy(isLiked = true) }
        if (localLiked.isNotEmpty()) {
            attachLocalPaths(localLiked)
        } else {
            val allCached = songDao.getAllCachedSongs().filter { likedIds.contains(it.id) || it.isLiked }
            attachLocalPaths(allCached.map { it.toSong().copy(isLiked = true) })
        }
    }

    suspend fun getCachedUser(): User? = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val fromDb = userDao.getUser(userId)?.toUser()
        if (fromDb != null) return@withContext fromDb
        val fromPrefs = authPreferences.getUser()
        if (fromPrefs != null) {
            userDao.insertUser(UserEntity.fromUser(fromPrefs))
            return@withContext fromPrefs
        }
        null
    }

    suspend fun clearUserData() = withContext(Dispatchers.IO) {
        apiCacheManager.clearAll()
        userDao.clearUser()
        authPreferences.clear()
    }

    suspend fun getDownloadedSongs(): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        val entities = songDao.getDownloadedSongsList()
        entities.map { entity ->
            entity.toSong().copy(isLiked = likedIds.contains(entity.songId))
        }
    }

    // ==========================================
    // BACKGROUND OFFLINE ACTION SYNC
    // ==========================================

    suspend fun syncOfflineActions(): Int = withContext(Dispatchers.IO) {
        val queue = authPreferences.getOfflineQueue()
        if (queue.isEmpty()) return@withContext 0

        val tempToRealPlaylistIdMap = mutableMapOf<Int, Int>()
        val remaining = mutableListOf<JSONObject>()
        var successCount = 0

        for (action in queue) {
            val success = try {
                when (action.optString("type")) {
                    "create_playlist" -> {
                        val userId = action.getInt("userId")
                        val name = action.getString("name")
                        val tempId = action.optInt("tempId", 0)
                        val resp = api.createPlaylist(userId, name)
                        if (resp.isSuccessful && resp.body()?.id != null) {
                            val serverId = resp.body()!!.id!!
                            val finalName = resp.body()!!.name ?: name
                            if (tempId != 0) {
                                playlistDao.remapPlaylistId(tempId, serverId)
                                playlistDao.updatePlaylistName(serverId, finalName)
                                tempToRealPlaylistIdMap[tempId] = serverId
                            }
                            successCount++
                            true
                        } else false
                    }
                    "rename_playlist" -> {
                        var plId = action.getInt("playlistId")
                        if (tempToRealPlaylistIdMap.containsKey(plId)) {
                            plId = tempToRealPlaylistIdMap[plId]!!
                        }
                        val userId = action.getInt("userId")
                        val name = action.getString("name")
                        val ok = api.renamePlaylist(plId, userId, name).isSuccessful
                        if (ok) successCount++
                        ok
                    }
                    "delete_playlist" -> {
                        var plId = action.getInt("playlistId")
                        if (tempToRealPlaylistIdMap.containsKey(plId)) {
                            plId = tempToRealPlaylistIdMap[plId]!!
                        }
                        val ok = if (plId > 0) {
                            api.deletePlaylist(plId).isSuccessful
                        } else true
                        if (ok) successCount++
                        ok
                    }
                    "add_song" -> {
                        var plId = action.getInt("playlistId")
                        if (tempToRealPlaylistIdMap.containsKey(plId)) {
                            plId = tempToRealPlaylistIdMap[plId]!!
                        }
                        val songId = action.getInt("songId")
                        val ok = api.addSongToPlaylist(plId, songId).isSuccessful
                        if (ok) successCount++
                        ok
                    }
                    "remove_song" -> {
                        var plId = action.getInt("playlistId")
                        if (tempToRealPlaylistIdMap.containsKey(plId)) {
                            plId = tempToRealPlaylistIdMap[plId]!!
                        }
                        val songId = action.getInt("songId")
                        val ok = api.removeSongFromPlaylist(plId, songId).isSuccessful
                        if (ok) successCount++
                        ok
                    }
                    "toggle_like" -> {
                        val userId = action.getInt("userId")
                        val songId = action.getInt("songId")
                        val status = action.getBoolean("newStatus")
                        val likedPlId = getOrFetchLikedPlaylistId(userId)
                        if (likedPlId != null) {
                            val ok = if (status) api.addSongToPlaylist(likedPlId, songId).isSuccessful
                            else api.removeSongFromPlaylist(likedPlId, songId).isSuccessful
                            if (ok) successCount++
                            ok
                        } else false
                    }
                    "create_pin" -> {
                        val userId = action.optInt("userId", getEffectiveUserId())
                        val songId = action.getInt("songId")
                        val lat = action.getDouble("latitude")
                        val lng = action.getDouble("longitude")
                        val imagePath = action.optString("imagePath", "")
                        val tempPinId = action.optInt("tempPinId", 0)
                        val imageFile = File(imagePath)
                        if (!imageFile.exists()) {
                            true
                        } else {
                            val userIdBody = userId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                            val songIdBody = songId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                            val latBody = lat.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                            val lngBody = lng.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                            val reqFile = RequestBody.create("image/*".toMediaTypeOrNull(), imageFile)
                            val body = MultipartBody.Part.createFormData("image", imageFile.name, reqFile)
                            val resp = api.createPin(userIdBody, songIdBody, latBody, lngBody, body)
                            if (resp.isSuccessful && resp.body() != null) {
                                val dto = resp.body()!!
                                val updatedPins = authPreferences.getCachedPins().map { p ->
                                    if (p.id == tempPinId) {
                                        p.copy(
                                            id = dto.id,
                                            imageUrl = if (!dto.imageUrl.isNullOrBlank()) sanitizeUrl(dto.imageUrl) else p.imageUrl,
                                            createdAt = dto.createdAt ?: p.createdAt
                                        )
                                    } else p
                                }
                                authPreferences.saveCachedPins(updatedPins)
                                successCount++
                                true
                            } else false
                        }
                    }
                    else -> true
                }
            } catch (_: Exception) {
                false
            }
            if (!success) {
                val plId = action.optInt("playlistId", 0)
                if (plId != 0 && tempToRealPlaylistIdMap.containsKey(plId)) {
                    action.put("playlistId", tempToRealPlaylistIdMap[plId]!!)
                }
                remaining.add(action)
            }
        }
        authPreferences.setOfflineQueue(remaining)
        if (successCount > 0) {
            val userId = getEffectiveUserId()
            apiCacheManager.invalidateUserPlaylists(userId)
            apiCacheManager.invalidatePlaylistSongs(Playlist.ID_LIKED)
        }
        successCount
    }

    private suspend fun attachLocalPaths(songs: List<Song>): List<Song> {
        val fullyCachedIds = com.example.audio.AudioCache.getFullyCachedSongIds(context)
        return songs.map { song ->
            val local = downloadManager.getLocalPath(song.id)
            val isDownloaded = local != null && File(local).exists() && File(local).length() > 0
            val isCached = isDownloaded || fullyCachedIds.contains(song.id) || com.example.audio.AudioCache.isSongFullyCached(context, song)
            if (isDownloaded) {
                song.copy(localPath = local, isDownloaded = true, isCached = true)
            } else {
                song.copy(isDownloaded = false, isCached = isCached)
            }
        }
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = JSONObject(errorBody)
            if (json.has("detail")) json.getString("detail")
            else if (json.has("message")) json.getString("message")
            else if (json.has("error")) json.getString("error")
            else errorBody
        } catch (_: Exception) {
            errorBody
        }
    }

    // ==========================================
    // LISTENING HISTORY SYNC & SERVER INTEGRATION
    // ==========================================

    /**
     * Sends listening history events to the server.
     * Enforces the 6-hour interval rule, or synchronizes immediately when user comes online
     * if the 6-hour mark was missed while offline.
     */
    suspend fun syncHistoryIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val user = authPreferences.getUser() ?: return@withContext false
        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (!isOnline) return@withContext false

        val now = System.currentTimeMillis()
        val lastSync = authPreferences.getLastSyncTime(KEY_HISTORY_SYNC)
        val sixHoursMs = 6L * 60L * 60L * 1000L

        val isDue = force || (now - lastSync >= sixHoursMs) || (lastSync == 0L)
        if (!isDue) return@withContext false

        val historyItems = appDatabase.getAllHistoryList()
        if (historyItems.isEmpty()) {
            authPreferences.setLastSyncTime(KEY_HISTORY_SYNC, now)
            return@withContext true
        }

        val unSynced = if (lastSync > 0L) {
            val delta = historyItems.filter { it.playedAt > lastSync }
            if (delta.isEmpty()) historyItems.take(50) else delta
        } else {
            historyItems.take(100)
        }

        if (unSynced.isEmpty()) {
            authPreferences.setLastSyncTime(KEY_HISTORY_SYNC, now)
            return@withContext true
        }

        val payload = unSynced.map {
            HistoryEventDto(
                songId = it.songId,
                listenedSeconds = it.effectiveListenedSeconds,
                playedAt = it.playedAt
            )
        }

        try {
            val resp = api.syncHistory(HistorySyncRequest(user.id, payload))
            if (resp.isSuccessful && resp.body()?.success == true) {
                authPreferences.setLastSyncTime(KEY_HISTORY_SYNC, now)
                android.util.Log.i("MusicRepository", "History sync succeeded: ${resp.body()?.syncedCount} items")
                return@withContext true
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "History sync failed: ${e.message}")
        }
        return@withContext false
    }

    /**
     * Fetches listening history from the server for registered users upon login and merges with local database.
     */
    suspend fun fetchAndMergeServerHistory(userId: Int): Int = withContext(Dispatchers.IO) {
        try {
            val response = api.getUserHistory(userId)
            if (response.isSuccessful && response.body() != null) {
                val serverItems = response.body()!!
                val localHistory = appDatabase.getAllHistoryList()
                val existingPlayedAts = localHistory.map { it.playedAt }.toSet()
                var addedCount = 0
                for (item in serverItems) {
                    if (!existingPlayedAts.contains(item.playedAt)) {
                        appDatabase.insertListeningHistory(
                            ListeningHistoryEntity(
                                songId = item.songId,
                                title = item.title ?: "Unknown Song",
                                artist = item.artist ?: "Unknown Artist",
                                audioUrl = sanitizeUrl(item.audioUrl),
                                coverUrl = sanitizeUrl(item.coverUrl),
                                duration = item.duration,
                                listenedSeconds = item.listenedSeconds,
                                playedAt = item.playedAt
                            )
                        )
                        addedCount++
                    }
                }
                android.util.Log.i("MusicRepository", "Fetched and merged $addedCount server history items on login")
                return@withContext addedCount
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "Failed to fetch server history: ${e.message}")
        }
        return@withContext 0
    }

    suspend fun getTodayServerListenTime(): Result<TodayListenTimeResponse> = withContext(Dispatchers.IO) {
        val user = authPreferences.getUser() ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val resp = api.getTodayListenTime(user.id)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("Failed to get today listen time from server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserSongPlays(): Result<List<UserSongPlayDto>> = withContext(Dispatchers.IO) {
        val user = authPreferences.getUser() ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val resp = api.getUserSongPlays(user.id)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("Failed to get song plays from server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return Math.round(r * c * 10.0) / 10.0
    }

    suspend fun getCachedNearbyPins(lat: Double, lng: Double): List<LocationPin> = withContext(Dispatchers.IO) {
        val cached = authPreferences.getCachedPins()
        if (cached.isEmpty()) return@withContext emptyList()
        val likedIds = authPreferences.getLikedSongIds()
        val matched = cached.mapNotNull { pin ->
            val dist = calculateDistanceMeters(lat, lng, pin.latitude, pin.longitude)
            if (dist <= 50.0) {
                pin.copy(
                    distanceMeters = dist,
                    song = pin.song.copy(isLiked = likedIds.contains(pin.song.id))
                )
            } else null
        }.sortedBy { it.distanceMeters }
        if (matched.isEmpty()) return@withContext emptyList()
        val enrichedSongs = attachLocalPaths(matched.map { it.song })
        matched.mapIndexed { index, pin -> pin.copy(song = enrichedSongs[index]) }
    }

    suspend fun getNearbyPins(lat: Double, lng: Double, forceRefresh: Boolean = false): List<LocationPin> = withContext(Dispatchers.IO) {
        if (!com.example.util.NetworkMonitor.isOnline(context) || shouldSkipUnreachableServer(forceRefresh)) {
            lastPinSyncOffline = true
            return@withContext getCachedNearbyPins(lat, lng)
        }

        try {
            val resp = api.getNearbyPins(lat, lng)
            markServerReachable()
            if (resp.isSuccessful && resp.body() != null) {
                lastPinSyncOffline = false
                val likedIds = authPreferences.getLikedSongIds()
                val rawRemotePins = resp.body()!!.map { dto ->
                    val songDto = dto.song
                    val songId = songDto?.id ?: dto.songId
                    val rawAudio = songDto?.audioUrl
                    val resolvedAudioUrl = if (!rawAudio.isNullOrBlank()) {
                        sanitizeUrl(rawAudio)
                    } else {
                        "${authPreferences.getServerBaseUrl().trimEnd('/')}/songs/$songId/stream"
                    }
                    val baseSong = Song(
                        id = songId,
                        title = songDto?.title ?: "Pinned Track",
                        artist = songDto?.artist ?: "Unknown",
                        audioUrl = resolvedAudioUrl,
                        coverUrl = sanitizeUrl(songDto?.coverUrl ?: ""),
                        duration = songDto?.duration ?: 0.0,
                        isLiked = likedIds.contains(songId),
                        dominantColorHex = songDto?.dominantColor
                    )
                    val resolvedImage = if (!dto.imageUrl.isNullOrBlank()) sanitizeUrl(dto.imageUrl) else ""
                    LocationPin(
                        id = dto.id,
                        userId = dto.userId,
                        songId = dto.songId,
                        latitude = dto.latitude,
                        longitude = dto.longitude,
                        distanceMeters = Math.round(dto.distanceMeters * 10.0) / 10.0,
                        imageUrl = resolvedImage,
                        createdAt = dto.createdAt ?: "",
                        song = baseSong
                    )
                }
                val enrichedSongs = if (rawRemotePins.isNotEmpty()) {
                    attachLocalPaths(rawRemotePins.map { it.song })
                } else {
                    emptyList()
                }
                val remotePins = rawRemotePins.mapIndexed { index, pin ->
                    pin.copy(song = enrichedSongs[index])
                }

                // Preserve any unsynced local pins (id < 0) alongside fresh server pins
                val existingCached = authPreferences.getCachedPins()
                val pendingLocalPins = existingCached.filter { it.id < 0 }
                val otherDistantCached = existingCached.filter { cached ->
                    cached.id > 0 && remotePins.none { it.id == cached.id } &&
                        calculateDistanceMeters(lat, lng, cached.latitude, cached.longitude) > 50.0
                }
                authPreferences.saveCachedPins(pendingLocalPins + remotePins + otherDistantCached)

                val nearbyPending = pendingLocalPins.mapNotNull { pin ->
                    val dist = calculateDistanceMeters(lat, lng, pin.latitude, pin.longitude)
                    if (dist <= 50.0) pin.copy(distanceMeters = dist) else null
                }
                return@withContext (nearbyPending + remotePins).sortedBy { it.distanceMeters }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || e.javaClass.name.contains("Cancellation")) throw e
            if (e is java.io.IOException) markServerUnreachable()
            lastPinSyncOffline = true
            android.util.Log.w("MusicRepository", "Nearby pins unavailable (${authPreferences.getServerBaseUrl()}): ${e.message}")
        }
        getCachedNearbyPins(lat, lng)
    }

    suspend fun createPin(songId: Int, lat: Double, lng: Double, imageFile: File): LocationPin? = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val knownSong = getCachedSongs().find { it.id == songId }
            ?: Song(
                id = songId,
                title = "Pinned Track",
                artist = "Unknown",
                audioUrl = "${authPreferences.getServerBaseUrl().trimEnd('/')}/songs/$songId/stream",
                coverUrl = ""
            )

        if (com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val userIdBody = userId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                val songIdBody = songId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                val latBody = lat.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                val lngBody = lng.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                val reqFile = RequestBody.create("image/*".toMediaTypeOrNull(), imageFile)
                val body = MultipartBody.Part.createFormData("image", imageFile.name, reqFile)

                val resp = api.createPin(userIdBody, songIdBody, latBody, lngBody, body)
                markServerReachable()
                if (resp.isSuccessful && resp.body() != null) {
                    lastPinSyncOffline = false
                    val dto = resp.body()!!
                    val createdPin = LocationPin(
                        id = dto.id,
                        userId = dto.userId,
                        songId = dto.songId,
                        latitude = dto.latitude,
                        longitude = dto.longitude,
                        distanceMeters = 0.0,
                        imageUrl = if (!dto.imageUrl.isNullOrBlank()) sanitizeUrl(dto.imageUrl) else imageFile.toURI().toString(),
                        createdAt = dto.createdAt ?: "",
                        song = knownSong
                    )
                    val updated = listOf(createdPin) + authPreferences.getCachedPins().filter { it.id != createdPin.id }
                    authPreferences.saveCachedPins(updated)
                    return@withContext createdPin
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.javaClass.name.contains("Cancellation")) throw e
                if (e is java.io.IOException) markServerUnreachable()
                lastPinSyncOffline = true
                android.util.Log.w("MusicRepository", "Server unreachable while creating pin; saving locally: ${e.message}")
            }
        } else {
            lastPinSyncOffline = true
        }

        // Offline / unreachable server fallback: persist photo locally and queue sync
        return@withContext try {
            val pinsDir = File(context.filesDir, "local_pins").apply { mkdirs() }
            val persistentFile = File(pinsDir, "pin_${System.currentTimeMillis()}.jpg")
            imageFile.copyTo(persistentFile, overwrite = true)

            val tempPinId = -((System.currentTimeMillis() % 900000 + 100000).toInt())
            val localPin = LocationPin(
                id = tempPinId,
                userId = userId,
                songId = songId,
                latitude = lat,
                longitude = lng,
                distanceMeters = 0.0,
                imageUrl = persistentFile.toURI().toString(),
                createdAt = System.currentTimeMillis().toString(),
                song = knownSong
            )
            val updated = listOf(localPin) + authPreferences.getCachedPins()
            authPreferences.saveCachedPins(updated)

            val action = JSONObject().apply {
                put("type", "create_pin")
                put("userId", userId)
                put("songId", songId)
                put("latitude", lat)
                put("longitude", lng)
                put("imagePath", persistentFile.absolutePath)
                put("tempPinId", tempPinId)
                put("timestamp", System.currentTimeMillis())
            }
            authPreferences.addOfflineAction(action)
            localPin
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "Failed to save offline pin locally: ${e.message}")
            null
        }
    }

    // ==========================================
    // FUSE (5-Minute Temporary Collaborative Blend)
    // ==========================================

    fun getFuseDisplayName(): String {
        val user = authPreferences.getUser()
        val candidate = user?.username?.trim()?.takeIf { it.isNotEmpty() }
            ?: user?.name?.trim()?.takeIf { it.isNotEmpty() }
        return candidate ?: "User ${getEffectiveUserId()}"
    }

    fun getActiveFuseSession(): FuseSession? {
        val current = activeFuseSession ?: return null
        if (current.isExpired()) {
            repoScope.launch { expireOrDeleteFuseSession(current) }
            return null
        }
        return current
    }

    suspend fun createFuseSession(forceNew: Boolean = false): Result<FuseSession> = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val existing = activeFuseSession
        if (!forceNew && existing != null && !existing.isExpired(nowMs) && existing.hostUserId == getEffectiveUserId()) {
            return@withContext Result.success(existing)
        }

        if (existing != null) {
            expireOrDeleteFuseSession(existing)
        }

        val hostUserId = getEffectiveUserId()
        val hostName = getFuseDisplayName()
        val code = FuseCodeCodec.generateCode(hostUserId)
        val expiresAtMs = nowMs + FuseCodeCodec.FUSE_TTL_MS
        val markerName = FuseCodeCodec.buildPendingMarkerName(code, expiresAtMs, hostName)

        var createdId: Int? = null
        if (com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val resp = api.createPlaylist(userId = hostUserId, name = markerName)
                if (resp.isSuccessful && resp.body()?.id != null) {
                    createdId = resp.body()!!.id!!
                }
            } catch (_: Exception) {}
        }

        val finalTempId = createdId ?: -((nowMs % 800000 + 150000).toInt())
        if (finalTempId < 0) {
            // Local fallback marker if offline
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    id = finalTempId,
                    userId = hostUserId,
                    name = markerName,
                    description = "Pending Fuse",
                    isSystem = false,
                    songCount = 0,
                    lastSyncedAt = nowMs
                )
            )
        }

        val session = FuseSession(
            code = code,
            hostUserId = hostUserId,
            hostName = hostName,
            tempPlaylistId = finalTempId,
            createdAtMs = nowMs,
            expiresAtMs = expiresAtMs
        )
        activeFuseSession = session
        scheduleFuseAutoDeletion(session)
        Result.success(session)
    }

    private fun scheduleFuseAutoDeletion(session: FuseSession) {
        activeFuseExpiryJob?.cancel()
        activeFuseExpiryJob = repoScope.launch {
            val waitMs = (session.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L) + 250L
            delay(waitMs)
            cleanupPendingFuseIfUnjoined(session)
        }
    }

    private suspend fun cleanupPendingFuseIfUnjoined(session: FuseSession) = withContext(Dispatchers.IO) {
        try {
            if (session.tempPlaylistId > 0 && com.example.util.NetworkMonitor.isOnline(context)) {
                val resp = api.getPlaylists(session.hostUserId)
                if (resp.isSuccessful && resp.body() != null) {
                    val serverMatch = resp.body()!!.find { it.id == session.tempPlaylistId }
                    if (serverMatch != null && FuseCodeCodec.isPendingMarker(serverMatch.name)) {
                        api.deletePlaylist(session.tempPlaylistId)
                    } else if (serverMatch != null && !FuseCodeCodec.isPendingMarker(serverMatch.name)) {
                        // Someone already joined right before expiry — keep the completed Fuse playlist!
                        if (activeFuseSession?.code == session.code) {
                            activeFuseSession = null
                        }
                        return@withContext
                    }
                } else {
                    api.deletePlaylist(session.tempPlaylistId)
                }
            }
        } catch (_: Exception) {}

        val localPl = playlistDao.getPlaylistById(session.tempPlaylistId)
        if (localPl != null && FuseCodeCodec.isPendingMarker(localPl.name)) {
            playlistDao.deletePlaylist(session.tempPlaylistId)
        }

        if (activeFuseSession?.code == session.code) {
            activeFuseSession = null
        }
    }

    suspend fun expireOrDeleteFuseSession(session: FuseSession) = withContext(Dispatchers.IO) {
        if (activeFuseSession?.code == session.code) {
            activeFuseSession = null
            activeFuseExpiryJob?.cancel()
            activeFuseExpiryJob = null
        }
        cleanupPendingFuseIfUnjoined(session)
    }

    suspend fun pollFuseSession(session: FuseSession): FusePollStatus = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        if (session.isExpired(nowMs)) {
            expireOrDeleteFuseSession(session)
            return@withContext FusePollStatus.Expired
        }

        if (session.tempPlaylistId > 0 && com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val resp = api.getPlaylists(session.hostUserId)
                if (resp.isSuccessful && resp.body() != null) {
                    val target = resp.body()!!.find { it.id == session.tempPlaylistId }
                    if (target == null) {
                        if (activeFuseSession?.code == session.code) activeFuseSession = null
                        return@withContext FusePollStatus.Expired
                    }
                    val currentName = target.name ?: ""
                    if (!FuseCodeCodec.isPendingMarker(currentName) && currentName.isNotBlank()) {
                        // User 2 joined and finalized the Fuse playlist!
                        activeFuseSession = null
                        activeFuseExpiryJob?.cancel()
                        activeFuseExpiryJob = null

                        val songs = getPlaylistSongs(session.tempPlaylistId, forceRefresh = true)
                        val completedEntity = PlaylistEntity(
                            id = session.tempPlaylistId,
                            userId = session.hostUserId,
                            name = currentName,
                            description = "Fuse Playlist",
                            isSystem = false,
                            songCount = songs.size,
                            lastSyncedAt = nowMs
                        )
                        playlistDao.insertPlaylist(completedEntity)
                        playlistDao.setPlaylistSongs(
                            session.tempPlaylistId,
                            songs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
                        )
                        apiCacheManager.invalidateUserPlaylists(session.hostUserId)
                        apiCacheManager.putPlaylistSongs(session.tempPlaylistId, songs)

                        return@withContext FusePollStatus.Joined(
                            playlist = completedEntity.toPlaylist(),
                            songs = songs
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Also check local DB in case single-device / offline join happened
        val localEntity = playlistDao.getPlaylistById(session.tempPlaylistId)
        if (localEntity != null && !FuseCodeCodec.isPendingMarker(localEntity.name)) {
            activeFuseSession = null
            activeFuseExpiryJob?.cancel()
            activeFuseExpiryJob = null
            val songs = getCachedPlaylistSongs(session.tempPlaylistId)
            return@withContext FusePollStatus.Joined(
                playlist = localEntity.toPlaylist().copy(songCount = songs.size),
                songs = songs
            )
        }

        FusePollStatus.Waiting
    }

    suspend fun joinFuse(rawCodeOrLink: String): Result<FuseJoinResult> = withContext(Dispatchers.IO) {
        val code = FuseCodeCodec.extractCode(rawCodeOrLink)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid Fuse code or Fuse link."))

        val hostUserId = FuseCodeCodec.decodeHostUserId(code)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Fuse code."))

        val nowMs = System.currentTimeMillis()
        val joinerUserId = getEffectiveUserId()
        val joinerName = getFuseDisplayName()

        var pendingPlaylistId: Int? = null
        var hostName: String? = null
        var expiresAtMs: Long = 0L
        var hostServerPlaylists: List<ServerPlaylistDto> = emptyList()

        if (com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val resp = api.getPlaylists(hostUserId)
                if (resp.isSuccessful && resp.body() != null) {
                    hostServerPlaylists = resp.body()!!
                    for (dto in hostServerPlaylists) {
                        val marker = FuseCodeCodec.parsePendingMarker(dto.name)
                        if (marker != null && marker.code == code) {
                            pendingPlaylistId = dto.id
                            hostName = marker.hostName
                            expiresAtMs = marker.expiresAtMs
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback to local pending session if testing on same device or offline
        if (pendingPlaylistId == null) {
            val localActive = activeFuseSession
            if (localActive != null && localActive.code == code) {
                pendingPlaylistId = localActive.tempPlaylistId
                hostName = localActive.hostName
                expiresAtMs = localActive.expiresAtMs
            } else {
                val localCandidates = playlistDao.getUserPlaylists(hostUserId)
                for (entity in localCandidates) {
                    val marker = FuseCodeCodec.parsePendingMarker(entity.name)
                    if (marker != null && marker.code == code) {
                        pendingPlaylistId = entity.id
                        hostName = marker.hostName
                        expiresAtMs = marker.expiresAtMs
                        break
                    }
                }
            }
        }

        val targetHostPlaylistId = pendingPlaylistId
            ?: return@withContext Result.failure(IllegalStateException("Fuse not found, already joined, or expired."))

        if (nowMs >= expiresAtMs) {
            // Enforce strict 5-minute TTL deletion
            if (targetHostPlaylistId > 0) {
                try { api.deletePlaylist(targetHostPlaylistId) } catch (_: Exception) {}
            }
            try { playlistDao.deletePlaylist(targetHostPlaylistId) } catch (_: Exception) {}
            if (activeFuseSession?.code == code) {
                activeFuseSession = null
                activeFuseExpiryJob?.cancel()
            }
            return@withContext Result.failure(IllegalStateException("This Fuse expired after 5 minutes and was deleted."))
        }

        val resolvedHostName = (hostName ?: "User $hostUserId").trim().ifEmpty { "User $hostUserId" }
        val fusePlaylistName = FuseCodeCodec.buildFusePlaylistName(resolvedHostName, joinerName)

        // Gather all songs across Liked + normal custom playlists for User 1 and User 2
        val user1Songs = if (hostUserId == joinerUserId) {
            collectAllSongsForCurrentUser()
        } else {
            collectAllSongsForRemoteUser(hostUserId, hostServerPlaylists)
        }
        val user2Songs = collectAllSongsForCurrentUser()

        val commonSongs = FuseCodeCodec.computeCommonFuseSongs(
            user1Songs = user1Songs,
            user2Songs = user2Songs,
            maxSongs = FuseCodeCodec.MAX_FUSE_SONGS
        )

        // 1. Populate User 1's Fuse playlist first, then rename it atomically so User 1's poll sees all songs ready
        if (targetHostPlaylistId > 0 && com.example.util.NetworkMonitor.isOnline(context)) {
            for (song in commonSongs) {
                try {
                    api.addSongToPlaylist(playlistId = targetHostPlaylistId, songId = song.id)
                } catch (_: Exception) {}
            }
            try {
                api.renamePlaylist(
                    playlistId = targetHostPlaylistId,
                    userId = hostUserId,
                    name = fusePlaylistName
                )
            } catch (_: Exception) {}
        }

        // 2. Make the playlist visible to User 2 as well (or reuse targetHostPlaylistId if same user)
        val joinerPlaylist: Playlist = if (joinerUserId == hostUserId) {
            val entity = PlaylistEntity(
                id = targetHostPlaylistId,
                userId = joinerUserId,
                name = fusePlaylistName,
                description = "Fuse Playlist",
                isSystem = false,
                songCount = commonSongs.size,
                lastSyncedAt = System.currentTimeMillis()
            )
            playlistDao.insertPlaylist(entity)
            playlistDao.setPlaylistSongs(
                targetHostPlaylistId,
                commonSongs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
            )
            if (activeFuseSession?.code == code) {
                activeFuseSession = null
                activeFuseExpiryJob?.cancel()
            }
            entity.toPlaylist()
        } else {
            val createdForJoiner = createPlaylist(fusePlaylistName)
            if (createdForJoiner.id > 0 && com.example.util.NetworkMonitor.isOnline(context)) {
                for (song in commonSongs) {
                    try {
                        api.addSongToPlaylist(playlistId = createdForJoiner.id, songId = song.id)
                    } catch (_: Exception) {}
                }
            } else {
                for (song in commonSongs) {
                    val action = JSONObject().apply {
                        put("type", "add_song")
                        put("playlistId", createdForJoiner.id)
                        put("songId", song.id)
                        put("timestamp", System.currentTimeMillis())
                    }
                    authPreferences.addOfflineAction(action)
                }
            }
            val updatedJoinerEntity = PlaylistEntity(
                id = createdForJoiner.id,
                userId = joinerUserId,
                name = fusePlaylistName,
                description = "Fuse Playlist",
                isSystem = false,
                songCount = commonSongs.size,
                lastSyncedAt = System.currentTimeMillis()
            )
            playlistDao.insertPlaylist(updatedJoinerEntity)
            playlistDao.setPlaylistSongs(
                createdForJoiner.id,
                commonSongs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
            )
            updatedJoinerEntity.toPlaylist()
        }

        apiCacheManager.invalidateUserPlaylists(joinerUserId)
        apiCacheManager.putPlaylistSongs(joinerPlaylist.id, commonSongs)

        Result.success(
            FuseJoinResult(
                playlist = joinerPlaylist,
                songs = commonSongs,
                hostName = resolvedHostName,
                joinerName = joinerName
            )
        )
    }

    private suspend fun collectAllSongsForRemoteUser(
        remoteUserId: Int,
        preloadedPlaylists: List<ServerPlaylistDto>
    ): List<Song> {
        val songsById = LinkedHashMap<Int, Song>()
        val currentLikedIds = authPreferences.getLikedSongIds()

        // 1. Remote user's Liked Songs playlist
        try {
            val likedResp = api.getLikedPlaylist(remoteUserId)
            val remoteLikedId = likedResp.body()?.playlistId
            if (likedResp.isSuccessful && remoteLikedId != null) {
                val likedSongsResp = api.getPlaylistSongs(remoteLikedId)
                if (likedSongsResp.isSuccessful && likedSongsResp.body() != null) {
                    for (dto in likedSongsResp.body()!!) {
                        songsById.putIfAbsent(dto.id, mapDtoToSong(dto, currentLikedIds))
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Remote user's normal custom playlists
        val normalPlaylists = (if (preloadedPlaylists.isNotEmpty()) {
            preloadedPlaylists
        } else {
            try {
                api.getPlaylists(remoteUserId).body().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }).filter { it.isSystem == 0 && !FuseCodeCodec.isPendingMarker(it.name) }

        for (pl in normalPlaylists) {
            try {
                val plSongsResp = api.getPlaylistSongs(pl.id)
                if (plSongsResp.isSuccessful && plSongsResp.body() != null) {
                    for (dto in plSongsResp.body()!!) {
                        songsById.putIfAbsent(dto.id, mapDtoToSong(dto, currentLikedIds))
                    }
                }
            } catch (_: Exception) {}
        }

        // Local DB fallback if cached on device
        if (songsById.isEmpty()) {
            val localPls = playlistDao.getUserPlaylists(remoteUserId)
                .filter { !FuseCodeCodec.isPendingMarker(it.name) }
            for (pl in localPls) {
                for (entity in playlistDao.getPlaylistSongs(pl.id)) {
                    songsById.putIfAbsent(entity.id, entity.toSong().copy(isLiked = currentLikedIds.contains(entity.id)))
                }
            }
        }

        return songsById.values.toList()
    }

    private suspend fun collectAllSongsForCurrentUser(): List<Song> {
        val songsById = LinkedHashMap<Int, Song>()
        val likedIds = authPreferences.getLikedSongIds()

        // 1. Liked Songs (both server & local)
        try {
            for (song in getLikedSongs()) {
                songsById.putIfAbsent(song.id, song)
            }
        } catch (_: Exception) {}
        try {
            for (song in getCachedLikedSongs()) {
                songsById.putIfAbsent(song.id, song)
            }
        } catch (_: Exception) {}

        if (likedIds.isNotEmpty()) {
            val catalogById = songDao.getAllCachedSongs().associateBy { it.id }
            for (likedId in likedIds) {
                if (likedId !in songsById) {
                    catalogById[likedId]?.let { entity ->
                        songsById[likedId] = entity.toSong().copy(isLiked = true)
                    }
                }
            }
        }

        // 2. All normal custom playlists (both server & local)
        val userPlaylists = try {
            getPlaylists(forceRefresh = true)
        } catch (_: Exception) {
            getCachedPlaylists()
        }

        for (pl in userPlaylists) {
            if (FuseCodeCodec.isPendingMarker(pl.name)) continue
            try {
                for (song in getPlaylistSongs(pl.id, forceRefresh = true)) {
                    songsById.putIfAbsent(song.id, song)
                }
            } catch (_: Exception) {}
            try {
                for (song in getCachedPlaylistSongs(pl.id)) {
                    songsById.putIfAbsent(song.id, song)
                }
            } catch (_: Exception) {}
        }

        return attachLocalPaths(songsById.values.toList())
    }

    private fun mapDtoToSong(dto: SongDto, currentLikedIds: Set<Int>): Song {
        return Song(
            id = dto.id,
            title = dto.title ?: "Untitled Track",
            artist = dto.artist ?: "Unknown Artist",
            audioUrl = sanitizeUrl(dto.audioUrl),
            coverUrl = sanitizeUrl(dto.coverUrl),
            duration = dto.duration,
            isLiked = currentLikedIds.contains(dto.id),
            dominantColorHex = dto.dominantColor
        )
    }

    companion object {
        const val KEY_HISTORY_SYNC = "history_sync"
        private const val UNREACHABLE_COOLDOWN_MS = 10_000L
    }
}

