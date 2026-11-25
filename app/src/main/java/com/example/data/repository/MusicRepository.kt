package com.example.data.repository
import com.example.audio.AudioController

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.BuildConfig
import com.example.data.DownloadManager
import com.example.data.local.AppDatabase
import com.example.data.preferences.AuthPreferences
import com.example.data.remote.*
import com.example.model.ArtistClaimInfo
import com.example.model.ArtistDetailBundle
import com.example.model.ArtistProfile
import com.example.model.DailyPlaylist
import com.example.model.LocationPin
import com.example.model.Playlist
import com.example.model.Song
import com.example.model.SongArtistCredit
import com.example.model.User
import com.example.model.UserArtistStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Cache
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
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
import com.example.data.fuse.FusePlaylistBundle
import com.example.data.fuse.FusePollStatus
import com.example.data.fuse.FuseSession
import com.example.soundcapsule.SoundCapsuleRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MusicRepository(private val context: Context) {
    val authPreferences = AuthPreferences(context)
    val downloadManager = DownloadManager(context)
    val apiCacheManager = ApiCacheManager()
    private val appDatabase = AppDatabase.getInstance(context)
    private val songDao = appDatabase.songDao()
    private val playlistDao = appDatabase.playlistDao()
    private val userDao = appDatabase.userDao()
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val historySyncMutex = Mutex()
    private val fuseContributorsCache = ConcurrentHashMap<Int, Map<Int, String>>()

    val soundCapsuleRepository: SoundCapsuleRepository by lazy {
        SoundCapsuleRepository(
            context = context.applicationContext,
            authPreferences = authPreferences,
            apiCacheManager = apiCacheManager,
            apiProvider = { api },
            urlSanitizer = { sanitizeUrl(it) }
        )
    }

    @Volatile
    private var activeFuseSession: FuseSession? = null

    @Volatile
    private var activeFuseExpiryJob: Job? = null

    @Volatile
    private var cachedMyClubsList: List<com.example.model.ClubPlaylistSummary>? = null

    fun getCachedMyClubs(): List<com.example.model.ClubPlaylistSummary> {
        return cachedMyClubsList.orEmpty()
    }

    init {
        if (backgroundSyncStarted.compareAndSet(false, true)) {
            soundCapsuleRepository.warmUpAsync()
            repoScope.launch {
                com.example.util.NetworkMonitor.observeNetwork(context.applicationContext)
                    .distinctUntilChanged()
                    .collect { isOnline ->
                        if (isOnline) {
                            if (authPreferences.hasPendingOfflineActions()) {
                                syncOfflineActions()
                            }
                            // Sync history only if 6-hour window was reached or missed while offline
                            syncHistoryIfDue()
                        }
                    }
            }

            // Periodic 30-minute check to trigger 6-hour scheduled sync while online
            repoScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(30L * 60L * 1000L)
                    try {
                        if (com.example.util.NetworkMonitor.isOnline(context.applicationContext)) {
                            syncHistoryIfDue()
                        }
                    } catch (_: Exception) {}
                }
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
            .addInterceptor(com.example.data.remote.AppSecurityInterceptor())
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
        return if (rawUrl.startsWith("https://api.alaktracare.in")) {
            rawUrl.replace("https://api.alaktracare.in", configuredBase)
        } else if (rawUrl.startsWith("https://api.alaktracare.in")) {
            rawUrl.replace("https://api.alaktracare.in", configuredBase)
        } else if (rawUrl.startsWith("https://api.alaktracare.in")) {
            rawUrl.replace("https://api.alaktracare.in", configuredBase)
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

                // Sync Sound Capsule & today's history once on login via unified Sound Capsule endpoint
                try {
                    soundCapsuleRepository.syncWithServer(user.id, force = true)
                    authPreferences.setLastSyncTime(KEY_HISTORY_SYNC, System.currentTimeMillis())
                } catch (_: Exception) {}

                return@withContext Result.success(user)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                return@withContext Result.failure(Exception(errorMsg ?: "Invalid credentials"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: "Failed to connect to music server"))
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
        val localPlaylistById = localEntities.associate { it.id to it.toPlaylist() }
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

                // Opportunistically clean up any expired pending Fuse markers on legacy servers
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
                        (it.isFuse != 0 || !pendingDeletedIds.contains(it.id)) &&
                        !FuseCodeCodec.isPendingMarker(it.name)
                }
                val entities = userPlaylists.map { p ->
                    val cleanName = p.name ?: "Playlist"
                    val prevLocal = localPlaylistById[p.id]
                    val isFuse = (p.isFuse != 0) || (p.editable == false) || (prevLocal?.isFuse == true) || FuseCodeCodec.isFusePlaylistName(cleanName)
                    val fallbackNames = if (isFuse) FuseCodeCodec.parseFuseNamesFromTitle(cleanName) else ("" to "")
                    val resolvedCount = when {
                        p.songCount > 0 -> p.songCount
                        prevLocal != null -> prevLocal.songCount
                        else -> 0
                    }
                    val mappedPlaylist = Playlist(
                        id = p.id,
                        name = cleanName,
                        description = if (isFuse) "Fuse Blend" else "Playlist",
                        songCount = resolvedCount,
                        isFuse = isFuse,
                        fuseId = p.fuseId ?: prevLocal?.fuseId,
                        fuseMatchScore = if (p.matchScore > 0) p.matchScore else (prevLocal?.fuseMatchScore ?: 0),
                        fuseUser1Name = p.user1Name?.takeIf { it.isNotBlank() }
                            ?: prevLocal?.fuseUser1Name?.takeIf { it.isNotBlank() }
                            ?: fallbackNames.first,
                        fuseUser2Name = p.user2Name?.takeIf { it.isNotBlank() }
                            ?: prevLocal?.fuseUser2Name?.takeIf { it.isNotBlank() }
                            ?: fallbackNames.second
                    )
                    PlaylistEntity.fromPlaylist(
                        playlist = mappedPlaylist,
                        userId = userId,
                        isSystem = false,
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

    // ==========================================
    // DAILY MIXES (5 x 30 SONGS, PER USER, SAVED FOR THE DAY)
    // ==========================================

    private fun todayDateString(): String = java.time.LocalDate.now().toString()

    suspend fun getCachedDailyPlaylists(): List<DailyPlaylist> = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val today = todayDateString()
        val likedIds = authPreferences.getLikedSongIds()
        val snapshot = authPreferences.getDailyPlaylistsSnapshot(userId, expectedDate = today)
            ?: return@withContext emptyList()
        snapshot.playlists.map { dp ->
            val hydrated = attachLocalPaths(dp.songs.map { it.copy(isLiked = likedIds.contains(it.id)) })
            dp.copy(
                songs = hydrated,
                songCount = hydrated.size,
                previewCovers = hydrated.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4)
            )
        }
    }

    suspend fun getDailyPlaylists(forceRefresh: Boolean = false): List<DailyPlaylist> = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val today = todayDateString()
        val likedIds = authPreferences.getLikedSongIds()

        // Always load today's snapshot (automatically deletes any older day's playlists)
        val existingToday = authPreferences.getDailyPlaylistsSnapshot(userId, expectedDate = today)
        if (!forceRefresh && existingToday != null && existingToday.playlists.size == 5 && existingToday.playlists.all { it.songs.isNotEmpty() }) {
            return@withContext existingToday.playlists.map { dp ->
                val hydrated = attachLocalPaths(dp.songs.map { it.copy(isLiked = likedIds.contains(it.id)) })
                dp.copy(
                    songs = hydrated,
                    songCount = hydrated.size,
                    previewCovers = hydrated.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4)
                )
            }
        }

        // Try authoritative backend endpoint (saves today's 5x30 playlists per user & deletes previous days)
        if (!shouldSkipUnreachableServer(forceRefresh) && com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val resp = api.getDailyPlaylists(userId = userId, date = today)
                markServerReachable()
                if (resp.code() == 304 && existingToday != null && existingToday.playlists.isNotEmpty()) {
                    return@withContext existingToday.playlists.map { dp ->
                        val hydrated = attachLocalPaths(dp.songs.map { it.copy(isLiked = likedIds.contains(it.id)) })
                        dp.copy(
                            songs = hydrated,
                            songCount = hydrated.size,
                            previewCovers = hydrated.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4)
                        )
                    }
                } else if (resp.isSuccessful && !resp.body().isNullOrEmpty()) {
                    val dtos = resp.body()!!
                    val mapped = dtos.mapIndexed { index, dto ->
                        val slot = dto.slotIndex.coerceIn(1, 5).let { if (it in 1..5) it else (index + 1) }
                        val mappedSongs = dto.songs.orEmpty().map { sDto -> mapDtoToSong(sDto, likedIds) }
                        val hydratedSongs = attachLocalPaths(mappedSongs)
                        val covers = dto.previewCovers
                            ?.map { sanitizeUrl(it) }
                            ?.filter { it.isNotBlank() }
                            ?.takeIf { it.isNotEmpty() }
                            ?: hydratedSongs.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4)

                        DailyPlaylist(
                            id = Playlist.dailyPlaylistIdForSlot(slot),
                            serverId = dto.id,
                            slotIndex = slot,
                            date = dto.date.ifBlank { today },
                            name = dto.name?.takeIf { it.isNotBlank() } ?: "Daily Mix $slot",
                            subtitle = dto.subtitle?.takeIf { it.isNotBlank() } ?: "30 tracks • Refreshes daily",
                            accentColorHex = dto.accentColor?.takeIf { it.isNotBlank() } ?: "#00E599",
                            previewCovers = covers,
                            songCount = if (dto.songCount > 0) dto.songCount else hydratedSongs.size,
                            songs = hydratedSongs
                        )
                    }
                    if (mapped.any { it.songs.isNotEmpty() }) {
                        // Cache songs locally too so offline playback & detail lookups work seamlessly
                        val allSongs = mapped.flatMap { it.songs }.distinctBy { it.id }
                        if (allSongs.isNotEmpty()) {
                            songDao.insertCachedSongs(allSongs.map { SongEntity.fromSong(it, likedIds.contains(it.id)) })
                        }
                        // Overwrite/save today's snapshot (automatically removes old days' saved playlists)
                        authPreferences.saveDailyPlaylistsSnapshot(userId, today, "server", mapped)
                        return@withContext mapped
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.javaClass.name.contains("Cancellation")) throw e
                if (e is java.io.IOException) markServerUnreachable()
            }
        }

        // If we already saved today's 5 playlists locally, keep them fixed for the entire day (never re-randomize mid-day)
        if (existingToday != null && existingToday.playlists.size == 5 && existingToday.playlists.all { it.songs.isNotEmpty() }) {
            return@withContext existingToday.playlists.map { dp ->
                val hydrated = attachLocalPaths(dp.songs.map { it.copy(isLiked = likedIds.contains(it.id)) })
                dp.copy(
                    songs = hydrated,
                    songCount = hydrated.size,
                    previewCovers = hydrated.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4)
                )
            }
        }

        // Local fallback generation if offline or backend endpoint is not yet updated:
        // Generates 5 x 30 random playlists unique to this user for today, saves them for the day, and deletes previous days.
        val catalog = getCachedSongs().ifEmpty { getSongs(forceRefresh = false) }
        if (catalog.isEmpty()) return@withContext emptyList()

        val themes = listOf(
            "Neon Drift" to "#00E599",
            "Midnight Echo" to "#00D2FF",
            "Velvet Horizon" to "#8B5CF6",
            "Solar Pulse" to "#F59E0B",
            "Emerald Tide" to "#10B981",
            "Aurora Haze" to "#EC4899",
            "Prism Wave" to "#06B6D4",
            "Crimson Static" to "#FF5277"
        )
        val rng = java.security.SecureRandom()
        val pickedThemes = themes.shuffled(java.util.Random(rng.nextLong())).take(5)
        val targetSize = minOf(30, catalog.size)
        val usageCount = mutableMapOf<Int, Int>().apply { catalog.forEach { put(it.id, 0) } }

        val generated = pickedThemes.mapIndexed { idx, (name, accent) ->
            val slot = idx + 1
            val chosen = if (catalog.size <= 30) {
                catalog.shuffled(java.util.Random(rng.nextLong()))
            } else {
                catalog.sortedBy { (usageCount[it.id] ?: 0) * 0.65 + rng.nextDouble() }
                    .take(targetSize)
                    .shuffled(java.util.Random(rng.nextLong()))
            }
            chosen.forEach { usageCount[it.id] = (usageCount[it.id] ?: 0) + 1 }

            val artists = chosen.map { it.primaryArtistName }
                .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
                .distinct()
                .take(2)
            val subtitle = when (artists.size) {
                2 -> "Feat. ${artists[0]}, ${artists[1]} & more"
                1 -> "Feat. ${artists[0]} & more"
                else -> "Fresh ${chosen.size}-track daily mix"
            }
            val hydrated = attachLocalPaths(chosen.map { it.copy(isLiked = likedIds.contains(it.id)) })
            DailyPlaylist(
                id = Playlist.dailyPlaylistIdForSlot(slot),
                serverId = 0,
                slotIndex = slot,
                date = today,
                name = name,
                subtitle = subtitle,
                accentColorHex = accent,
                previewCovers = hydrated.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct().take(4),
                songCount = hydrated.size,
                songs = hydrated
            )
        }

        authPreferences.saveDailyPlaylistsSnapshot(userId, today, "local", generated)
        generated
    }

    suspend fun getDailyPlaylistMeta(playlistId: Int): DailyPlaylist? = withContext(Dispatchers.IO) {
        if (!Playlist.isDailyPlaylistId(playlistId)) return@withContext null
        val cached = getCachedDailyPlaylists().find { it.id == playlistId }
        if (cached != null) return@withContext cached
        getDailyPlaylists(forceRefresh = false).find { it.id == playlistId }
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

    suspend fun isFusePlaylist(playlistId: Int): Boolean = withContext(Dispatchers.IO) {
        if (playlistId in listOf(Playlist.ID_LIKED, Playlist.ID_DOWNLOADED, Playlist.ID_OFFLINE_BACKUP)) {
            return@withContext false
        }
        if (fuseContributorsCache.containsKey(playlistId)) return@withContext true
        val userId = getEffectiveUserId()
        apiCacheManager.getUserPlaylists(userId)?.find { it.id == playlistId }?.let { cached ->
            if (cached.isFuse || FuseCodeCodec.isFusePlaylistName(cached.name)) return@withContext true
        }
        val local = playlistDao.getPlaylistById(playlistId)?.toPlaylist()
        local?.isFuse == true || (local != null && FuseCodeCodec.isFusePlaylistName(local.name))
    }

    suspend fun renamePlaylist(playlistId: Int, newName: String): Playlist = withContext(Dispatchers.IO) {
        if (isFusePlaylist(playlistId)) {
            val existing = playlistDao.getPlaylistById(playlistId)?.toPlaylist()
            return@withContext existing ?: Playlist(id = playlistId, name = newName, isFuse = true)
        }
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
        if (isFusePlaylist(playlistId)) {
            // Fuse playlists are strictly read-only and cannot be deleted by any user
            return@withContext
        }
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

        if (Playlist.isDailyPlaylistId(playlistId)) {
            val dailyList = getDailyPlaylists(forceRefresh = forceRefresh)
            val match = dailyList.find { it.id == playlistId }
            if (match != null && match.songs.isNotEmpty()) {
                return@withContext attachLocalPaths(match.songs.map { it.copy(isLiked = likedIds.contains(it.id)) })
            }
            return@withContext emptyList()
        }

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
                            mapDtoToSong(dto, setOf(dto.id)).copy(isLiked = true)
                        }
                        songDao.insertCachedSongs(songs.map { SongEntity.fromSong(it, isLiked = true) })
                        return@withContext attachLocalPaths(songs)
                    }
                } catch (_: Exception) {}
            }
            return@withContext attachLocalPaths(localLiked)
        }

        val knownFuse = isFusePlaylist(playlistId)
        if (knownFuse) {
            if (!forceRefresh) {
                val inMemory = apiCacheManager.getPlaylistSongs(playlistId)
                if (inMemory != null && inMemory.isNotEmpty()) {
                    val contribMap = fuseContributorsCache[playlistId].orEmpty()
                    return@withContext inMemory.map { s ->
                        s.copy(
                            isLiked = likedIds.contains(s.id),
                            localPath = null,
                            isDownloaded = false,
                            fuseContributor = s.fuseContributor ?: contribMap[s.id] ?: "both"
                        )
                    }
                }
            }
            val bundle = getFusePlaylistBundle(playlistId, forceRefresh = true)
            if (bundle != null && bundle.songs.isNotEmpty()) {
                return@withContext bundle.songs
            }
        }

        // Custom playlist:
        if (!forceRefresh) {
            val inMemory = apiCacheManager.getPlaylistSongs(playlistId)
            if (inMemory != null) {
                val withLikes = inMemory.map { it.copy(isLiked = likedIds.contains(it.id)) }
                return@withContext if (knownFuse) {
                    withLikes.map { it.copy(localPath = null, isDownloaded = false) }
                } else {
                    attachLocalPaths(withLikes)
                }
            }
        }

        val localCached = playlistDao.getPlaylistSongs(playlistId).map {
            it.toSong().copy(isLiked = likedIds.contains(it.id))
        }

        try {
            val response = api.getPlaylistSongs(playlistId)
            if (response.isSuccessful && response.body() != null) {
                val dtos = response.body()!!
                val detectedFuse = knownFuse || dtos.any { !it.fuseContributor.isNullOrBlank() }
                val remoteSongs = dtos.map { dto ->
                    mapDtoToSong(dto, likedIds)
                }
                if (detectedFuse) {
                    val contribMap = remoteSongs.mapNotNull { s ->
                        s.fuseContributor?.let { s.id to it }
                    }.toMap()
                    if (contribMap.isNotEmpty()) {
                        fuseContributorsCache[playlistId] = contribMap
                    }
                    val streamOnlySongs = remoteSongs.map { it.copy(localPath = null, isDownloaded = false) }
                    apiCacheManager.putPlaylistSongs(playlistId, streamOnlySongs)
                    playlistDao.setPlaylistSongs(playlistId, streamOnlySongs.map { SongEntity.fromSong(it) })
                    return@withContext streamOnlySongs
                }
                apiCacheManager.putPlaylistSongs(playlistId, remoteSongs)
                playlistDao.setPlaylistSongs(playlistId, remoteSongs.map { SongEntity.fromSong(it) })
                return@withContext attachLocalPaths(remoteSongs)
            }
        } catch (_: Exception) {}

        if (knownFuse) {
            val contribMap = fuseContributorsCache[playlistId].orEmpty()
            val fuseLocal = localCached.map { s ->
                s.copy(
                    localPath = null,
                    isDownloaded = false,
                    fuseContributor = s.fuseContributor ?: contribMap[s.id] ?: "both"
                )
            }
            if (fuseLocal.isNotEmpty()) {
                apiCacheManager.putPlaylistSongs(playlistId, fuseLocal)
            }
            return@withContext fuseLocal
        }

        if (localCached.isNotEmpty()) {
            apiCacheManager.putPlaylistSongs(playlistId, localCached)
        }
        attachLocalPaths(localCached)
    }

    suspend fun getCachedPlaylistSongs(playlistId: Int): List<Song> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        if (Playlist.isDailyPlaylistId(playlistId)) {
            val dailyMatch = getCachedDailyPlaylists().find { it.id == playlistId }
            if (dailyMatch != null) {
                return@withContext attachLocalPaths(dailyMatch.songs.map { it.copy(isLiked = likedIds.contains(it.id)) })
            }
            return@withContext emptyList()
        }
        if (playlistId == Playlist.ID_DOWNLOADED || playlistId == -2) {
            return@withContext getDownloadedSongs()
        }
        if (playlistId == Playlist.ID_OFFLINE_BACKUP || playlistId == -3) {
            return@withContext getOfflineBackupSongs()
        }
        if (playlistId == Playlist.ID_LIKED || playlistId == -1) {
            return@withContext getCachedLikedSongs()
        }
        val knownFuse = isFusePlaylist(playlistId)
        val contribMap = fuseContributorsCache[playlistId].orEmpty()
        val localCached = playlistDao.getPlaylistSongs(playlistId).map {
            it.toSong().copy(isLiked = likedIds.contains(it.id))
        }
        if (knownFuse) {
            return@withContext localCached.map { s ->
                s.copy(
                    localPath = null,
                    isDownloaded = false,
                    fuseContributor = s.fuseContributor ?: contribMap[s.id] ?: "both"
                )
            }
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

        // 3. Listening history songs (preserve existing dominantColorHex / artist credits if already known!)
        historyEntities.forEach { hist ->
            val existing = knownSongs[hist.songId]
            knownSongs[hist.songId] = Song(
                id = hist.songId,
                title = hist.title,
                artist = hist.artist,
                audioUrl = existing?.audioUrl?.takeIf { it.isNotBlank() } ?: hist.audioUrl,
                coverUrl = existing?.coverUrl?.takeIf { it.isNotBlank() } ?: hist.coverUrl,
                duration = if (hist.duration > 0.0) hist.duration else (existing?.duration ?: 0.0),
                dominantColorHex = existing?.dominantColorHex,
                primaryArtistId = existing?.primaryArtistId,
                artists = existing?.artists ?: emptyList()
            )
        }

        // 4. Downloaded songs (merge while keeping non-blank dominantColorHex if catalog already had one)
        downloadedList.forEach { dlEntity ->
            val existing = knownSongs[dlEntity.songId]
            val dlSong = dlEntity.toSong()
            knownSongs[dlEntity.songId] = if (dlSong.dominantColorHex.isNullOrBlank() && !existing?.dominantColorHex.isNullOrBlank()) {
                dlSong.copy(dominantColorHex = existing?.dominantColorHex)
            } else {
                dlSong
            }
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
        if (isFusePlaylist(playlistId)) {
            // Fuse playlists are curated by the server and cannot be modified by any user
            return@withContext
        }
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
        if (isFusePlaylist(playlistId)) {
            // Fuse playlists are curated by the server and cannot be modified by any user
            return@withContext
        }
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
        runCatching { syncHistoryIfDue(force = true) }
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
        val downloadedById = try {
            songDao.getDownloadedSongsList().associateBy { it.songId }
        } catch (_: Exception) {
            emptyMap()
        }
        return songs.map { song ->
            val dlEntity = downloadedById[song.id]
            val local = dlEntity?.localPath ?: downloadManager.getLocalPath(song.id)
            val isDownloaded = local != null && File(local).exists() && File(local).length() > 0
            val isCached = isDownloaded || fullyCachedIds.contains(song.id) || com.example.audio.AudioCache.isSongFullyCached(context, song)
            val mergedColorHex = song.dominantColorHex?.takeIf { it.isNotBlank() }
                ?: dlEntity?.dominantColorHex?.takeIf { it.isNotBlank() }
            if (isDownloaded) {
                song.copy(
                    localPath = local,
                    isDownloaded = true,
                    isCached = true,
                    dominantColorHex = mergedColorHex
                )
            } else {
                song.copy(
                    isDownloaded = false,
                    isCached = isCached,
                    dominantColorHex = mergedColorHex
                )
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
     * Sends cached unsynced listening history events to the server in a single unified batch.
     * Enforces the 6-hour interval rule (sending 0 requests when no new local plays exist).
     */
    suspend fun syncHistoryIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val user = authPreferences.getUser() ?: return@withContext false
        val isOnline = com.example.util.NetworkMonitor.isOnline(context)
        if (!isOnline) return@withContext false

        historySyncMutex.withLock {
            val now = System.currentTimeMillis()
            val lastSync = authPreferences.getLastSyncTime(KEY_HISTORY_SYNC)
            val isDue = force ||
                (now - lastSync >= com.example.data.remote.cache.ApiCacheConfig.HISTORY_SYNC_INTERVAL_MS) ||
                (lastSync == 0L)
            if (!isDue) return@withLock false

            val synced = try {
                soundCapsuleRepository.pushUnsyncedEventsToServer(user.id, force = true)
            } catch (e: Exception) {
                android.util.Log.w("MusicRepository", "History sync failed: ${e.message}")
                false
            }
            if (synced) {
                authPreferences.setLastSyncTime(KEY_HISTORY_SYNC, System.currentTimeMillis())
            }
            synced
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
    // FUSE BLEND (Server-Curated, 5-Min Code, Non-Downloadable, Non-Editable)
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

    /**
     * Fetches the full server-curated Fuse Blend playlist & its 30 tracks (with contributor badges
     * and Taste Match % score). Fuse songs are always stream-only (`localPath = null, isDownloaded = false`).
     */
    suspend fun getFusePlaylistBundle(
        playlistId: Int,
        forceRefresh: Boolean = false
    ): FusePlaylistBundle? = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId()
        val likedIds = authPreferences.getLikedSongIds()

        if (!forceRefresh) {
            val cachedPl = apiCacheManager.getUserPlaylists(userId)?.find { it.id == playlistId }
                ?: playlistDao.getPlaylistById(playlistId)?.toPlaylist()
            val cachedSongs = apiCacheManager.getPlaylistSongs(playlistId)
            if (cachedPl != null && cachedPl.isFuse && !cachedSongs.isNullOrEmpty()) {
                val contribMap = fuseContributorsCache[playlistId].orEmpty()
                val streamSongs = cachedSongs.map { s ->
                    s.copy(
                        isLiked = likedIds.contains(s.id),
                        localPath = null,
                        isDownloaded = false,
                        fuseContributor = s.fuseContributor ?: contribMap[s.id] ?: "both"
                    )
                }
                return@withContext FusePlaylistBundle(
                    playlist = cachedPl.copy(songCount = streamSongs.size, isFuse = true),
                    songs = streamSongs
                )
            }
        }

        if (playlistId > 0 && com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val resp = api.getFusePlaylist(playlistId)
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    markServerReachable()
                    val cleanName = body.name?.takeIf { it.isNotBlank() } ?: "Fuse Blend"
                    val fallbackNames = FuseCodeCodec.parseFuseNamesFromTitle(cleanName)
                    val songs = body.songs.orEmpty().map { dto ->
                        mapDtoToFuseSong(dto, likedIds)
                    }
                    val contribMap = songs.mapNotNull { s ->
                        s.fuseContributor?.let { s.id to it }
                    }.toMap()
                    fuseContributorsCache[playlistId] = contribMap

                    val playlist = Playlist(
                        id = if (body.playlistId > 0) body.playlistId else playlistId,
                        name = cleanName,
                        description = "Fuse Blend",
                        songCount = songs.size,
                        isFuse = true,
                        fuseId = body.fuseId,
                        fuseMatchScore = body.matchScore,
                        fuseUser1Name = body.user1Name?.takeIf { it.isNotBlank() } ?: fallbackNames.first,
                        fuseUser2Name = body.user2Name?.takeIf { it.isNotBlank() } ?: fallbackNames.second
                    )

                    playlistDao.insertPlaylist(
                        PlaylistEntity.fromPlaylist(playlist, userId = userId, isSystem = false)
                    )
                    playlistDao.setPlaylistSongs(
                        playlist.id,
                        songs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
                    )
                    apiCacheManager.putPlaylistSongs(playlist.id, songs)
                    apiCacheManager.invalidateUserPlaylists(userId)

                    return@withContext FusePlaylistBundle(playlist = playlist, songs = songs)
                }
            } catch (_: Exception) {}
        }

        val localPl = playlistDao.getPlaylistById(playlistId)?.toPlaylist() ?: return@withContext null
        val contribMap = fuseContributorsCache[playlistId].orEmpty()
        val localSongs = playlistDao.getPlaylistSongs(playlistId).map { entity ->
            entity.toSong().copy(
                isLiked = likedIds.contains(entity.id),
                localPath = null,
                isDownloaded = false,
                fuseContributor = contribMap[entity.id] ?: "both"
            )
        }
        FusePlaylistBundle(
            playlist = localPl.copy(isFuse = true, songCount = localSongs.size),
            songs = localSongs
        )
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

        // 1. Primary path: Create 5-minute Fuse invite on the FastAPI server
        if (com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val serverResp = api.createFuseInvite(userId = hostUserId, username = hostName)
                if (serverResp.isSuccessful && serverResp.body() != null && serverResp.body()!!.code.isNotBlank()) {
                    markServerReachable()
                    val body = serverResp.body()!!
                    val serverDurationMs = (body.expiresAtMs - body.createdAtMs)
                        .takeIf { it in 1_000L..(10L * 60L * 1000L) }
                        ?: FuseCodeCodec.FUSE_TTL_MS
                    val session = FuseSession(
                        code = body.code.trim().uppercase(java.util.Locale.US),
                        hostUserId = hostUserId,
                        hostName = body.hostName.ifBlank { hostName },
                        tempPlaylistId = 0,
                        createdAtMs = nowMs,
                        expiresAtMs = nowMs + serverDurationMs,
                        serverManaged = true
                    )
                    activeFuseSession = session
                    scheduleFuseAutoDeletion(session)
                    return@withContext Result.success(session)
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback path (only if offline or server has not yet restarted with /fuse/* routes)
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
            expiresAtMs = expiresAtMs,
            serverManaged = false
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
        if (session.serverManaged) {
            if (com.example.util.NetworkMonitor.isOnline(context)) {
                try {
                    api.cancelFuseInvite(code = session.code, userId = session.hostUserId)
                } catch (_: Exception) {}
            }
            if (activeFuseSession?.code == session.code) {
                activeFuseSession = null
            }
            return@withContext
        }

        try {
            if (session.tempPlaylistId > 0 && com.example.util.NetworkMonitor.isOnline(context)) {
                val resp = api.getPlaylists(session.hostUserId)
                if (resp.isSuccessful && resp.body() != null) {
                    val serverMatch = resp.body()!!.find { it.id == session.tempPlaylistId }
                    if (serverMatch != null && FuseCodeCodec.isPendingMarker(serverMatch.name)) {
                        api.deletePlaylist(session.tempPlaylistId)
                    } else if (serverMatch != null && !FuseCodeCodec.isPendingMarker(serverMatch.name)) {
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

        if (session.tempPlaylistId != 0) {
            val localPl = playlistDao.getPlaylistById(session.tempPlaylistId)
            if (localPl != null && FuseCodeCodec.isPendingMarker(localPl.name)) {
                playlistDao.deletePlaylist(session.tempPlaylistId)
            }
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

        // 1. Primary path: Poll server /fuse/status/{code}
        if (session.serverManaged && com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val statusResp = api.getFuseStatus(session.code)
                if (statusResp.isSuccessful && statusResp.body() != null) {
                    val body = statusResp.body()!!
                    when (body.status.lowercase(java.util.Locale.US)) {
                        "expired" -> {
                            activeFuseSession = null
                            activeFuseExpiryJob?.cancel()
                            activeFuseExpiryJob = null
                            return@withContext FusePollStatus.Expired
                        }
                        "joined" -> {
                            activeFuseSession = null
                            activeFuseExpiryJob?.cancel()
                            activeFuseExpiryJob = null

                            val likedIds = authPreferences.getLikedSongIds()
                            val plId = body.playlistId ?: 0
                            val cleanName = body.name?.takeIf { it.isNotBlank() } ?: "Fuse Blend"
                            val fallbackNames = FuseCodeCodec.parseFuseNamesFromTitle(cleanName)
                            val songs = body.songs.orEmpty().map { dto ->
                                mapDtoToFuseSong(dto, likedIds)
                            }
                            if (plId != 0) {
                                fuseContributorsCache[plId] = songs.mapNotNull { s ->
                                    s.fuseContributor?.let { s.id to it }
                                }.toMap()
                            }
                            val completedPlaylist = Playlist(
                                id = plId,
                                name = cleanName,
                                description = "Fuse Blend",
                                songCount = songs.size,
                                isFuse = true,
                                fuseId = body.fuseId,
                                fuseMatchScore = body.matchScore,
                                fuseUser1Name = body.user1Name?.takeIf { it.isNotBlank() } ?: fallbackNames.first,
                                fuseUser2Name = body.user2Name?.takeIf { it.isNotBlank() } ?: fallbackNames.second
                            )
                            if (plId != 0) {
                                playlistDao.insertPlaylist(
                                    PlaylistEntity.fromPlaylist(completedPlaylist, session.hostUserId, isSystem = false, lastSyncedAt = nowMs)
                                )
                                playlistDao.setPlaylistSongs(
                                    plId,
                                    songs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
                                )
                                apiCacheManager.invalidateUserPlaylists(session.hostUserId)
                                apiCacheManager.putPlaylistSongs(plId, songs)
                            }
                            return@withContext FusePollStatus.Joined(
                                playlist = completedPlaylist,
                                songs = songs,
                                matchScore = completedPlaylist.fuseMatchScore
                            )
                        }
                        else -> return@withContext FusePollStatus.Waiting
                    }
                }
            } catch (_: Exception) {}
            return@withContext FusePollStatus.Waiting
        }

        // 2. Fallback path for legacy / offline marker sessions
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
                        activeFuseSession = null
                        activeFuseExpiryJob?.cancel()
                        activeFuseExpiryJob = null

                        val (u1, u2) = FuseCodeCodec.parseFuseNamesFromTitle(currentName)
                        val songs = getPlaylistSongs(session.tempPlaylistId, forceRefresh = true)
                            .map { it.copy(localPath = null, isDownloaded = false, fuseContributor = it.fuseContributor ?: "both") }
                        val completedPlaylist = Playlist(
                            id = session.tempPlaylistId,
                            name = currentName,
                            description = "Fuse Blend",
                            songCount = songs.size,
                            isFuse = true,
                            fuseMatchScore = if (target.matchScore > 0) target.matchScore else 84,
                            fuseUser1Name = target.user1Name ?: u1,
                            fuseUser2Name = target.user2Name ?: u2
                        )
                        playlistDao.insertPlaylist(
                            PlaylistEntity.fromPlaylist(completedPlaylist, session.hostUserId, isSystem = false, lastSyncedAt = nowMs)
                        )
                        playlistDao.setPlaylistSongs(
                            session.tempPlaylistId,
                            songs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
                        )
                        apiCacheManager.invalidateUserPlaylists(session.hostUserId)
                        apiCacheManager.putPlaylistSongs(session.tempPlaylistId, songs)

                        return@withContext FusePollStatus.Joined(
                            playlist = completedPlaylist,
                            songs = songs,
                            matchScore = completedPlaylist.fuseMatchScore
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        if (session.tempPlaylistId != 0) {
            val localEntity = playlistDao.getPlaylistById(session.tempPlaylistId)
            if (localEntity != null && !FuseCodeCodec.isPendingMarker(localEntity.name)) {
                activeFuseSession = null
                activeFuseExpiryJob?.cancel()
                activeFuseExpiryJob = null
                val songs = getCachedPlaylistSongs(session.tempPlaylistId)
                val completedPl = localEntity.toPlaylist().copy(isFuse = true, songCount = songs.size)
                return@withContext FusePollStatus.Joined(
                    playlist = completedPl,
                    songs = songs,
                    matchScore = completedPl.fuseMatchScore
                )
            }
        }

        FusePollStatus.Waiting
    }

    suspend fun joinFuse(rawCodeOrLink: String): Result<FuseJoinResult> = withContext(Dispatchers.IO) {
        val code = FuseCodeCodec.extractCode(rawCodeOrLink)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid Fuse code or Fuse link."))

        val nowMs = System.currentTimeMillis()
        val joinerUserId = getEffectiveUserId()
        val joinerName = getFuseDisplayName()
        val likedIds = authPreferences.getLikedSongIds()

        // 1. Primary path: Server curates the 30-song Blend playlist and sends it back to play!
        if (com.example.util.NetworkMonitor.isOnline(context)) {
            try {
                val joinResp = api.joinFuseInvite(
                    code = code,
                    userId = joinerUserId,
                    username = joinerName
                )
                if (joinResp.isSuccessful && joinResp.body() != null) {
                    markServerReachable()
                    val body = joinResp.body()!!
                    val plId = body.playlistId
                    val cleanName = body.name?.takeIf { it.isNotBlank() }
                        ?: FuseCodeCodec.buildFusePlaylistName(body.user1Name ?: "User 1", body.user2Name ?: joinerName)
                    val fallbackNames = FuseCodeCodec.parseFuseNamesFromTitle(cleanName)
                    val resolvedU1 = body.user1Name?.takeIf { it.isNotBlank() } ?: fallbackNames.first
                    val resolvedU2 = body.user2Name?.takeIf { it.isNotBlank() } ?: fallbackNames.second

                    val serverSongs = body.songs.orEmpty().map { dto ->
                        mapDtoToFuseSong(dto, likedIds)
                    }
                    if (plId != 0) {
                        fuseContributorsCache[plId] = serverSongs.mapNotNull { s ->
                            s.fuseContributor?.let { s.id to it }
                        }.toMap()
                    }

                    val fusePlaylist = Playlist(
                        id = plId,
                        name = cleanName,
                        description = "Fuse Blend",
                        songCount = serverSongs.size,
                        isFuse = true,
                        fuseId = body.fuseId,
                        fuseMatchScore = body.matchScore,
                        fuseUser1Name = resolvedU1,
                        fuseUser2Name = resolvedU2
                    )

                    if (plId != 0) {
                        playlistDao.insertPlaylist(
                            PlaylistEntity.fromPlaylist(fusePlaylist, userId = joinerUserId, isSystem = false, lastSyncedAt = nowMs)
                        )
                        playlistDao.setPlaylistSongs(
                            plId,
                            serverSongs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
                        )
                        apiCacheManager.invalidateUserPlaylists(joinerUserId)
                        apiCacheManager.putPlaylistSongs(plId, serverSongs)
                    }

                    if (activeFuseSession?.code == code) {
                        activeFuseSession = null
                        activeFuseExpiryJob?.cancel()
                    }

                    return@withContext Result.success(
                        FuseJoinResult(
                            playlist = fusePlaylist,
                            songs = serverSongs,
                            hostName = resolvedU1,
                            joinerName = resolvedU2,
                            matchScore = fusePlaylist.fuseMatchScore
                        )
                    )
                } else if (joinResp.code() != 404) {
                    val serverErr = parseErrorMessage(joinResp.errorBody()?.string())
                        ?: "Unable to join Fuse (${joinResp.code()})."
                    return@withContext Result.failure(IllegalStateException(serverErr))
                }
            } catch (_: Exception) {
                // Network error or server unreachable — continue to local fallback if applicable
            }
        }

        // 2. Offline / legacy fallback when server /fuse/join is unreachable (404 or offline)
        val hostUserId = FuseCodeCodec.decodeHostUserId(code)
            ?: activeFuseSession?.takeIf { it.code == code }?.hostUserId
            ?: return@withContext Result.failure(
                IllegalStateException("Could not reach server to join Fuse code $code. Check your connection and try again.")
            )

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
            if (targetHostPlaylistId > 0) {
                try { api.deletePlaylist(targetHostPlaylistId) } catch (_: Exception) {}
            }
            if (targetHostPlaylistId != 0) {
                try { playlistDao.deletePlaylist(targetHostPlaylistId) } catch (_: Exception) {}
            }
            if (activeFuseSession?.code == code) {
                activeFuseSession = null
                activeFuseExpiryJob?.cancel()
            }
            return@withContext Result.failure(IllegalStateException("This Fuse expired after 5 minutes and was deleted."))
        }

        val resolvedHostName = (hostName ?: "User $hostUserId").trim().ifEmpty { "User $hostUserId" }
        val fusePlaylistName = FuseCodeCodec.buildFusePlaylistName(resolvedHostName, joinerName)

        val user1Songs = if (hostUserId == joinerUserId) {
            collectAllSongsForCurrentUser()
        } else {
            collectAllSongsForRemoteUser(hostUserId, hostServerPlaylists)
        }
        val user2Songs = collectAllSongsForCurrentUser()

        val (blendSongs, matchScore) = FuseCodeCodec.computeBlendFallback(
            user1Songs = user1Songs,
            user2Songs = user2Songs,
            maxSongs = FuseCodeCodec.MAX_FUSE_SONGS
        )

        val joinerPlaylistId = if (targetHostPlaylistId != 0) {
            targetHostPlaylistId
        } else {
            -((nowMs % 800000 + 200000).toInt())
        }

        val joinerPlaylist = Playlist(
            id = joinerPlaylistId,
            name = fusePlaylistName,
            description = "Fuse Blend",
            songCount = blendSongs.size,
            isFuse = true,
            fuseMatchScore = matchScore,
            fuseUser1Name = resolvedHostName,
            fuseUser2Name = joinerName
        )

        fuseContributorsCache[joinerPlaylist.id] = blendSongs.mapNotNull { s ->
            s.fuseContributor?.let { s.id to it }
        }.toMap()

        playlistDao.insertPlaylist(
            PlaylistEntity.fromPlaylist(joinerPlaylist, userId = joinerUserId, isSystem = false, lastSyncedAt = nowMs)
        )
        playlistDao.setPlaylistSongs(
            joinerPlaylist.id,
            blendSongs.map { SongEntity.fromSong(it, isLiked = it.isLiked) }
        )
        if (activeFuseSession?.code == code) {
            activeFuseSession = null
            activeFuseExpiryJob?.cancel()
        }

        apiCacheManager.invalidateUserPlaylists(joinerUserId)
        apiCacheManager.putPlaylistSongs(joinerPlaylist.id, blendSongs)

        Result.success(
            FuseJoinResult(
                playlist = joinerPlaylist,
                songs = blendSongs,
                hostName = resolvedHostName,
                joinerName = joinerName,
                matchScore = matchScore
            )
        )
    }

    private suspend fun collectAllSongsForRemoteUser(
        remoteUserId: Int,
        preloadedPlaylists: List<ServerPlaylistDto>
    ): List<Song> {
        val songsById = LinkedHashMap<Int, Song>()
        val currentLikedIds = authPreferences.getLikedSongIds()

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

        val normalPlaylists = (if (preloadedPlaylists.isNotEmpty()) {
            preloadedPlaylists
        } else {
            try {
                api.getPlaylists(remoteUserId).body().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }).filter { it.isSystem == 0 && it.isFuse == 0 && !FuseCodeCodec.isPendingMarker(it.name) }

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

        val userPlaylists = try {
            getPlaylists(forceRefresh = true)
        } catch (_: Exception) {
            getCachedPlaylists()
        }

        for (pl in userPlaylists) {
            if (pl.isFuse || FuseCodeCodec.isPendingMarker(pl.name) || FuseCodeCodec.isFusePlaylistName(pl.name)) continue
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

    // ==========================================
    // ARTISTS, CLAIMS & IN-APP ARTIST STUDIO
    // ==========================================

    private fun mapDtoToArtist(dto: ArtistDto): ArtistProfile {
        return ArtistProfile(
            id = dto.id,
            name = dto.name?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
            bio = dto.bio ?: "",
            avatarUrl = sanitizeUrl(dto.avatarUrl),
            bannerUrl = sanitizeUrl(dto.bannerUrl),
            isVerified = dto.isVerified,
            claimedByUserId = dto.claimedByUserId,
            claimedByUsername = dto.claimedByUsername,
            songCount = dto.songCount,
            totalPlays = dto.totalPlays,
            monthlyListeners = dto.monthlyListeners
        )
    }

    private fun mapDtoCredits(dto: SongDto): List<SongArtistCredit> {
        return dto.artists.orEmpty().mapNotNull { c ->
            val name = c.name?.trim().orEmpty()
            if (c.id <= 0 || name.isEmpty()) null
            else SongArtistCredit(
                id = c.id,
                name = name,
                role = c.role?.ifBlank { "primary" } ?: "primary",
                position = c.position,
                avatarUrl = sanitizeUrl(c.avatarUrl),
                isVerified = c.isVerified
            )
        }
    }

    private fun parseApiErrorDetail(rawBody: String?, fallback: String): String {
        if (rawBody.isNullOrBlank()) return fallback
        return try {
            val obj = JSONObject(rawBody)
            obj.optString("detail")
                .ifBlank { obj.optString("error") }
                .ifBlank { obj.optString("message") }
                .ifBlank { fallback }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun readUriBytesAndMeta(uri: Uri, fallbackName: String, fallbackMime: String): Triple<ByteArray, String, String>? {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null

        var displayName = fallbackName
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val n = cursor.getString(idx)?.trim()
                        if (!n.isNullOrEmpty()) displayName = n
                    }
                }
            }
        }
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: fallbackMime
        return Triple(bytes, displayName, mime)
    }

    suspend fun getArtists(
        query: String = "",
        unclaimedOnly: Boolean = false
    ): List<ArtistProfile> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getArtists(query = query.trim(), unclaimedOnly = unclaimedOnly, limit = 200)
            if (resp.isSuccessful && resp.body() != null) {
                markServerReachable()
                return@withContext resp.body()!!.map { mapDtoToArtist(it) }
            }
        } catch (_: Exception) {
            markServerUnreachable()
        }

        // Offline fallback synthesized from cached songs' structured credits
        val cachedSongs = getCachedSongs()
        val byId = LinkedHashMap<Int, MutableList<Song>>()
        val creditMeta = LinkedHashMap<Int, SongArtistCredit>()
        for (song in cachedSongs) {
            for (c in song.artists) {
                creditMeta.putIfAbsent(c.id, c)
                byId.getOrPut(c.id) { mutableListOf() }.add(song)
            }
        }
        val cleanQ = query.trim().lowercase()
        creditMeta.values
            .filter { cleanQ.isEmpty() || it.name.lowercase().contains(cleanQ) }
            .map { c ->
                val songsForArtist = byId[c.id].orEmpty()
                ArtistProfile(
                    id = c.id,
                    name = c.name,
                    avatarUrl = c.avatarUrl.ifBlank { songsForArtist.firstOrNull()?.coverUrl.orEmpty() },
                    bannerUrl = songsForArtist.firstOrNull()?.coverUrl.orEmpty(),
                    isVerified = c.isVerified,
                    songCount = songsForArtist.size
                )
            }
    }

    suspend fun resolveArtistId(
        artistIdHint: Int? = null,
        artistNameHint: String? = null
    ): Int? = withContext(Dispatchers.IO) {
        if (artistIdHint != null && artistIdHint > 0) return@withContext artistIdHint
        val cleanName = artistNameHint?.trim().orEmpty()
        if (cleanName.isEmpty()) return@withContext null

        // Check numeric ID string if caller passed e.g. Sound Capsule artistId
        cleanName.toIntOrNull()?.takeIf { it > 0 }?.let { return@withContext it }

        // Check local cached songs first for instant match
        val cachedMatch = getCachedSongs()
            .asSequence()
            .flatMap { it.artists.asSequence() }
            .firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        if (cachedMatch != null && cachedMatch.id > 0) {
            return@withContext cachedMatch.id
        }

        try {
            val resp = api.getArtistByName(cleanName)
            if (resp.isSuccessful && resp.body() != null && resp.body()!!.id > 0) {
                markServerReachable()
                return@withContext resp.body()!!.id
            }
        } catch (_: Exception) {}

        // Try prefix match via search
        val searched = getArtists(query = cleanName.substringBefore(",").trim())
        searched.firstOrNull { it.name.equals(cleanName.substringBefore(",").trim(), ignoreCase = true) }?.id
            ?: searched.firstOrNull()?.id
    }

    suspend fun getArtistDetail(
        artistId: Int,
        fallbackName: String = ""
    ): Result<ArtistDetailBundle> = withContext(Dispatchers.IO) {
        val likedIds = authPreferences.getLikedSongIds()
        try {
            val resp = api.getArtistDetail(artistId)
            if (resp.isSuccessful && resp.body()?.artist != null) {
                markServerReachable()
                val body = resp.body()!!
                val artist = mapDtoToArtist(body.artist!!)
                val popular = attachLocalPaths(body.popularSongs.orEmpty().map { mapDtoToSong(it, likedIds) })
                val primary = attachLocalPaths(body.primarySongs.orEmpty().map { mapDtoToSong(it, likedIds) })
                val featured = attachLocalPaths(body.featuredSongs.orEmpty().map { mapDtoToSong(it, likedIds) })
                val all = attachLocalPaths(body.allSongs.orEmpty().map { mapDtoToSong(it, likedIds) })
                return@withContext Result.success(
                    ArtistDetailBundle(
                        artist = artist,
                        popularSongs = popular,
                        primarySongs = primary,
                        featuredSongs = featured,
                        allSongs = all
                    )
                )
            }
        } catch (_: Exception) {
            markServerUnreachable()
        }

        // Offline fallback from cached songs
        val cached = getCachedSongs()
        val primary = cached.filter { s ->
            s.primaryArtistId == artistId ||
                s.artists.any { it.id == artistId && it.role.equals("primary", ignoreCase = true) } ||
                (artistId <= 0 && fallbackName.isNotBlank() && s.primaryArtistName.equals(fallbackName, ignoreCase = true))
        }
        val featured = cached.filter { s ->
            s.artists.any { it.id == artistId && !it.role.equals("primary", ignoreCase = true) }
        }
        val all = (primary + featured).distinctBy { it.id }
        if (all.isNotEmpty() || fallbackName.isNotBlank()) {
            val credit = all.asSequence().flatMap { it.artists.asSequence() }.firstOrNull { it.id == artistId }
            val profile = ArtistProfile(
                id = artistId,
                name = credit?.name ?: fallbackName.ifBlank { "Artist" },
                avatarUrl = credit?.avatarUrl?.ifBlank { all.firstOrNull()?.coverUrl.orEmpty() }
                    ?: all.firstOrNull()?.coverUrl.orEmpty(),
                bannerUrl = all.firstOrNull()?.coverUrl.orEmpty(),
                isVerified = credit?.isVerified ?: false,
                songCount = all.size
            )
            return@withContext Result.success(
                ArtistDetailBundle(
                    artist = profile,
                    popularSongs = all.take(5),
                    primarySongs = primary,
                    featuredSongs = featured,
                    allSongs = all
                )
            )
        }

        Result.failure(Exception("Unable to load artist profile right now."))
    }

    suspend fun getUserArtistStatus(
        userId: Int = getEffectiveUserId()
    ): Result<UserArtistStatus> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getUserArtistStatus(userId)
            if (resp.isSuccessful && resp.body() != null) {
                markServerReachable()
                val b = resp.body()!!
                val claimed = b.claimedArtist?.let { mapDtoToArtist(it) }
                val pending = b.pendingClaim?.let { c ->
                    ArtistClaimInfo(
                        id = c.id,
                        userId = c.userId,
                        artistId = c.artistId,
                        requestedName = c.requestedName ?: "",
                        note = c.note ?: "",
                        status = c.status ?: "pending",
                        createdAt = c.createdAt ?: ""
                    )
                }
                Result.success(
                    UserArtistStatus(
                        userId = b.userId,
                        isArtist = b.isArtist && claimed != null,
                        claimedArtist = claimed,
                        pendingClaim = pending
                    )
                )
            } else {
                Result.failure(Exception(parseApiErrorDetail(resp.errorBody()?.string(), "Failed to fetch artist status")))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Unable to connect to server"))
        }
    }

    suspend fun submitArtistClaim(
        artistId: Int? = null,
        requestedName: String,
        note: String = ""
    ): Result<ArtistClaimInfo> = withContext(Dispatchers.IO) {
        val currentUser = authPreferences.getUser()
            ?: return@withContext Result.failure(Exception("Please sign in to claim an artist profile."))

        try {
            val resp = api.submitArtistClaim(
                SubmitArtistClaimRequest(
                    userId = currentUser.id,
                    artistId = artistId,
                    requestedName = requestedName.trim(),
                    note = note.trim()
                )
            )
            if (resp.isSuccessful && resp.body()?.claim != null) {
                val c = resp.body()!!.claim!!
                Result.success(
                    ArtistClaimInfo(
                        id = c.id,
                        userId = c.userId,
                        artistId = c.artistId,
                        requestedName = c.requestedName ?: requestedName.trim(),
                        note = c.note ?: note.trim(),
                        status = c.status ?: "pending",
                        createdAt = c.createdAt ?: ""
                    )
                )
            } else {
                val msg = resp.body()?.detail
                    ?: parseApiErrorDetail(resp.errorBody()?.string(), "Could not submit artist claim.")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Network error submitting claim."))
        }
    }

    suspend fun updateArtistProfile(
        artistId: Int,
        name: String?,
        bio: String?,
        avatarUri: Uri? = null,
        bannerUri: Uri? = null
    ): Result<ArtistProfile> = withContext(Dispatchers.IO) {
        val currentUser = authPreferences.getUser()
            ?: return@withContext Result.failure(Exception("Please sign in to edit your artist profile."))

        try {
            val uidPart = currentUser.id.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val namePart = name?.trim()?.takeIf { it.isNotEmpty() }?.toRequestBody("text/plain".toMediaTypeOrNull())
            val bioPart = bio?.trim()?.toRequestBody("text/plain".toMediaTypeOrNull())

            val avatarPart = avatarUri?.let { uri ->
                val (bytes, fname, mime) = readUriBytesAndMeta(uri, "avatar.jpg", "image/jpeg")
                    ?: return@withContext Result.failure(Exception("Unable to read selected avatar image."))
                MultipartBody.Part.createFormData("avatar", fname, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            }

            val bannerPart = bannerUri?.let { uri ->
                val (bytes, fname, mime) = readUriBytesAndMeta(uri, "banner.jpg", "image/jpeg")
                    ?: return@withContext Result.failure(Exception("Unable to read selected banner image."))
                MultipartBody.Part.createFormData("banner", fname, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            }

            val resp = api.updateArtistProfile(
                artistId = artistId,
                requesterUserId = uidPart,
                name = namePart,
                bio = bioPart,
                avatar = avatarPart,
                banner = bannerPart
            )
            if (resp.isSuccessful && resp.body()?.artist != null) {
                apiCacheManager.clearAll()
                Result.success(mapDtoToArtist(resp.body()!!.artist!!))
            } else {
                val msg = resp.body()?.detail
                    ?: parseApiErrorDetail(resp.errorBody()?.string(), "Failed to update artist profile.")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Network error updating artist profile."))
        }
    }

    suspend fun uploadStudioSong(
        title: String,
        colorHex: String,
        primaryArtistId: Int,
        primaryArtistName: String,
        featuredArtistIds: List<Int>,
        featuredArtistNames: List<String>,
        audioUri: Uri,
        coverUri: Uri
    ): Result<Song> = withContext(Dispatchers.IO) {
        val currentUser = authPreferences.getUser()
            ?: return@withContext Result.failure(Exception("Please sign in to release tracks."))

        try {
            val (audioBytes, audioName, audioMime) = readUriBytesAndMeta(audioUri, "track.mp3", "audio/mpeg")
                ?: return@withContext Result.failure(Exception("Unable to read selected audio file."))
            val (coverBytes, coverName, coverMime) = readUriBytesAndMeta(coverUri, "cover.jpg", "image/jpeg")
                ?: return@withContext Result.failure(Exception("Unable to read selected cover artwork."))

            val plain = "text/plain".toMediaTypeOrNull()
            val titleBody = title.trim().toRequestBody(plain)
            val artistBody = primaryArtistName.trim().toRequestBody(plain)
            val colorBody = colorHex.trim().ifBlank { "#00E599" }.toRequestBody(plain)
            val primIdBody = primaryArtistId.toString().toRequestBody(plain)
            val uploaderBody = currentUser.id.toString().toRequestBody(plain)

            val featIdsBody = if (featuredArtistIds.isNotEmpty()) {
                JSONArray(featuredArtistIds).toString().toRequestBody(plain)
            } else null

            val featNamesBody = if (featuredArtistNames.isNotEmpty()) {
                JSONArray(featuredArtistNames).toString().toRequestBody(plain)
            } else null

            val audioPart = MultipartBody.Part.createFormData(
                "audio",
                audioName,
                audioBytes.toRequestBody(audioMime.toMediaTypeOrNull())
            )
            val coverPart = MultipartBody.Part.createFormData(
                "cover",
                coverName,
                coverBytes.toRequestBody(coverMime.toMediaTypeOrNull())
            )

            val resp = api.uploadStudioSong(
                title = titleBody,
                artist = artistBody,
                color = colorBody,
                primaryArtistId = primIdBody,
                uploaderUserId = uploaderBody,
                featuredArtistIds = featIdsBody,
                featuredArtistNames = featNamesBody,
                audio = audioPart,
                cover = coverPart
            )
            if (resp.isSuccessful && resp.body()?.success == true) {
                apiCacheManager.clearAll()
                val dto = resp.body()?.song
                val likedIds = authPreferences.getLikedSongIds()
                val createdSong = if (dto != null) {
                    mapDtoToSong(dto, likedIds)
                } else {
                    getSongs(forceRefresh = true).firstOrNull { it.id == resp.body()!!.id }
                        ?: Song(
                            id = resp.body()!!.id,
                            title = title.trim(),
                            artist = resp.body()!!.artist ?: primaryArtistName,
                            audioUrl = "${authPreferences.getServerBaseUrl().trimEnd('/')}/songs/${resp.body()!!.id}/stream",
                            coverUrl = "",
                            primaryArtistId = primaryArtistId
                        )
                }
                songDao.insertCachedSong(SongEntity.fromSong(createdSong))
                Result.success(createdSong)
            } else {
                val msg = resp.body()?.detail
                    ?: parseApiErrorDetail(resp.errorBody()?.string(), "Failed to upload song.")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Upload failed."))
        }
    }

    suspend fun allotStudioSong(
        songId: Int,
        title: String? = null,
        colorHex: String? = null,
        primaryArtistId: Int,
        featuredArtistIds: List<Int>,
        featuredArtistNames: List<String> = emptyList()
    ): Result<Song> = withContext(Dispatchers.IO) {
        val currentUser = authPreferences.getUser()
            ?: return@withContext Result.failure(Exception("Please sign in to manage track credits."))

        try {
            val resp = api.allotSongArtists(
                songId = songId,
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                color = colorHex?.trim()?.takeIf { it.isNotEmpty() },
                primaryArtistId = primaryArtistId,
                featuredArtistIdsJson = JSONArray(featuredArtistIds).toString(),
                featuredArtistNamesJson = JSONArray(featuredArtistNames).toString(),
                requesterUserId = currentUser.id
            )
            if (resp.isSuccessful && resp.body()?.song != null) {
                apiCacheManager.clearAll()
                val updated = mapDtoToSong(resp.body()!!.song!!, authPreferences.getLikedSongIds())
                songDao.insertCachedSong(SongEntity.fromSong(updated))
                Result.success(updated)
            } else {
                val msg = resp.body()?.detail
                    ?: parseApiErrorDetail(resp.errorBody()?.string(), "Failed to update track credits.")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Network error updating credits."))
        }
    }

    private fun mapDtoToSong(dto: SongDto, currentLikedIds: Set<Int>): Song {
        val credits = mapDtoCredits(dto)
        val primId = dto.primaryArtistId ?: credits.firstOrNull { it.role.equals("primary", ignoreCase = true) }?.id ?: credits.firstOrNull()?.id
        return Song(
            id = dto.id,
            title = dto.title ?: "Untitled Track",
            artist = dto.artist ?: "Unknown Artist",
            audioUrl = sanitizeUrl(dto.audioUrl),
            coverUrl = sanitizeUrl(dto.coverUrl),
            duration = dto.duration,
            isLiked = currentLikedIds.contains(dto.id),
            dominantColorHex = dto.dominantColor,
            fuseContributor = dto.fuseContributor,
            primaryArtistId = primId,
            artists = credits,
            playCount = dto.playCount
        )
    }

    private fun mapDtoToFuseSong(dto: SongDto, currentLikedIds: Set<Int>): Song {
        val rawAudio = dto.audioUrl
        val resolvedAudioUrl = if (!rawAudio.isNullOrBlank()) {
            sanitizeUrl(rawAudio)
        } else {
            "${authPreferences.getServerBaseUrl().trimEnd('/')}/songs/${dto.id}/stream"
        }
        val credits = mapDtoCredits(dto)
        val primId = dto.primaryArtistId ?: credits.firstOrNull { it.role.equals("primary", ignoreCase = true) }?.id ?: credits.firstOrNull()?.id
        return Song(
            id = dto.id,
            title = dto.title ?: "Untitled Track",
            artist = dto.artist ?: "Unknown Artist",
            audioUrl = resolvedAudioUrl,
            coverUrl = sanitizeUrl(dto.coverUrl),
            duration = dto.duration,
            isLiked = currentLikedIds.contains(dto.id),
            localPath = null,
            isDownloaded = false,
            isCached = false,
            dominantColorHex = dto.dominantColor,
            fuseContributor = dto.fuseContributor ?: "both",
            primaryArtistId = primId,
            artists = credits,
            playCount = dto.playCount
        )
    }

    // ==========================================
    // COLLABORATIVE CLUB PLAYLISTS
    // ==========================================

    suspend fun createClub(name: String, description: String = ""): Result<com.example.data.remote.ClubCreateResponse> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Host", email = "")
            val resp = api.createClub(userId = user.id, username = user.username, name = name, description = description)
            if (resp.isSuccessful && resp.body()?.success == true) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("Failed to create collaborative playlist"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinClub(code: String): Result<com.example.data.remote.ClubJoinResponse> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Collaborator", email = "")
            val resp = api.joinClub(userId = user.id, username = user.username, code = code.trim().uppercase())
            if (resp.isSuccessful && resp.body()?.success == true) {
                Result.success(resp.body()!!)
            } else {
                val err = parseErrorMessage(resp.errorBody()?.string()) ?: "Club playlist not found. Check the code."
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyClubs(): Result<List<com.example.model.ClubPlaylistSummary>> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Collaborator", email = "")
            val resp = api.getMyClubs(userId = user.id)
            if (resp.isSuccessful && resp.body() != null) {
                val list = resp.body()!!.clubs.map { dto ->
                    com.example.model.ClubPlaylistSummary(
                        clubId = dto.clubId,
                        code = dto.code,
                        name = dto.name,
                        ownerUserId = dto.ownerUserId,
                        ownerUsername = dto.ownerUsername,
                        isOwner = dto.isOwner,
                        memberCount = dto.memberCount,
                        songCount = dto.songCount,
                        createdAt = dto.createdAt,
                        previewCovers = dto.previewCovers.map { sanitizeUrl(it) }
                    )
                }
                cachedMyClubsList = list
                Result.success(list)
            } else {
                Result.failure(Exception("Could not load collaborative playlists"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getClubDetails(clubId: Int): Result<com.example.model.ClubPlaylistDetails> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Collaborator", email = "")
            val resp = api.getClubDetails(clubId = clubId, userId = user.id)
            if (resp.isSuccessful && resp.body() != null) {
                val b = resp.body()!!
                val likedIds = authPreferences.getLikedSongIds()
                val mappedSongs = b.songs.map { sDto ->
                    val song = Song(
                        id = sDto.id,
                        title = sDto.title ?: "Untitled Track",
                        artist = sDto.artist ?: "Unknown Artist",
                        audioUrl = sanitizeUrl(sDto.audioUrl),
                        coverUrl = sanitizeUrl(sDto.coverUrl),
                        dominantColorHex = sDto.dominantColor,
                        duration = sDto.duration,
                        isLiked = likedIds.contains(sDto.id)
                    )
                    com.example.model.ClubCollaborativeSong(
                        song = song,
                        addedByUserId = sDto.addedByUserId,
                        addedByUsername = sDto.addedByUsername ?: "Member"
                    )
                }
                val mappedMembers = b.members.map { mDto ->
                    com.example.model.ClubMember(
                        userId = mDto.userId,
                        username = mDto.username,
                        isOwner = mDto.isOwner
                    )
                }
                val details = com.example.model.ClubPlaylistDetails(
                    clubId = b.clubId,
                    code = b.code,
                    name = b.name,
                    ownerUserId = b.ownerUserId,
                    ownerUsername = b.ownerUsername,
                    isOwner = b.isOwner,
                    members = mappedMembers,
                    songs = mappedSongs
                )
                Result.success(details)
            } else {
                Result.failure(Exception("Failed to fetch collaborative playlist details"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addSongToClub(clubId: Int, songId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Collaborator", email = "")
            val resp = api.addSongToClub(clubId = clubId, userId = user.id, username = user.username, songId = songId)
            if (resp.isSuccessful && resp.body()?.success == true) {
                Result.success(true)
            } else {
                Result.failure(Exception("Could not add song to playlist"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeSongFromClub(clubId: Int, songId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Collaborator", email = "")
            val resp = api.removeSongFromClub(clubId = clubId, userId = user.id, songId = songId)
            if (resp.isSuccessful && resp.body()?.success == true) {
                Result.success(true)
            } else {
                val err = parseErrorMessage(resp.errorBody()?.string()) ?: "Could not remove song"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveClub(clubId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Collaborator", email = "")
            val resp = api.leaveClub(clubId = clubId, userId = user.id)
            if (resp.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to leave playlist"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteClub(clubId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val user = authPreferences.getUser() ?: User(id = 1, username = "Host", email = "")
            val resp = api.deleteClub(clubId = clubId, userId = user.id)
            if (resp.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to delete collaborative playlist"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerDeviceToken(userId: Int?, token: String, deviceInfo: String?) = withContext(Dispatchers.IO) {
        try {
            val req = com.example.data.remote.RegisterTokenRequestDto(
                userId = userId ?: authPreferences.getUser()?.id,
                fcmToken = token,
                deviceInfo = deviceInfo
            )
            api.registerDeviceToken(req)
        } catch (_: Exception) {
        }
    }

    suspend fun getNotificationHistory(userId: Int? = null): List<com.example.data.remote.NotificationDto> = withContext(Dispatchers.IO) {
        try {
            val targetUserId = userId ?: authPreferences.getUser()?.id
            api.getNotificationHistory(targetUserId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val KEY_HISTORY_SYNC = "history_sync"
        private const val UNREACHABLE_COOLDOWN_MS = 10_000L
        private val backgroundSyncStarted = AtomicBoolean(false)

        @Volatile
        private var INSTANCE: MusicRepository? = null

        fun getInstance(context: Context): MusicRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

