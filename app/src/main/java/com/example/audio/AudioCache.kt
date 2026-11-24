package com.example.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.example.data.remote.AppSecurityInterceptor
import com.example.model.Song
import com.example.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Dynamic LRU cache evictor that supports on-the-fly resizing of maximum cache space.
 * When the user lowers their cache cap, older spans are evicted immediately to adhere
 * to the new limit.
 */
@OptIn(UnstableApi::class)
class DynamicLruCacheEvictor(initialMaxBytes: Long) : CacheEvictor {
    @Volatile
    var maxBytes: Long = initialMaxBytes
        private set

    private val leastRecentlyUsed = TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp == b.lastTouchTimestamp) {
            a.compareTo(b)
        } else if (a.lastTouchTimestamp < b.lastTouchTimestamp) {
            -1
        } else {
            1
        }
    }

    private var currentSize: Long = 0

    @Synchronized
    fun setMaxBytes(newMaxBytes: Long, cache: Cache? = null) {
        maxBytes = newMaxBytes
        if (cache != null) {
            evictCache(cache, 0L)
        }
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    @Synchronized
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != -1L) {
            evictCache(cache, length)
        }
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0L)
    }

    var onKeyEvicted: ((String) -> Unit)? = null

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
        onKeyEvicted?.invoke(span.key)
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        leastRecentlyUsed.remove(oldSpan)
        currentSize -= oldSpan.length
        onSpanAdded(cache, newSpan)
    }

    @Synchronized
    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            val oldest = leastRecentlyUsed.first()
            try {
                cache.removeSpan(oldest)
            } catch (e: Exception) {
                Log.e("DynamicLruEvictor", "Failed to remove span during eviction", e)
                leastRecentlyUsed.remove(oldest)
                currentSize -= oldest.length
            }
            if (leastRecentlyUsed.isNotEmpty() && leastRecentlyUsed.first() === oldest) {
                leastRecentlyUsed.remove(oldest)
                currentSize -= oldest.length
            }
        }
    }
}

/**
 * Centralized singleton audio streaming cache manager.
 * Uses Media3 SimpleCache with dynamic LRU eviction and user-configurable memory cap.
 *
 * IMPORTANT ARCHITECTURAL SEPARATION:
 * - Temporary streaming audio is cached here in context.cacheDir/audio_cache.
 * - Permanent downloads (filesDir/downloads via DownloadManager) are NOT stored here
 *   and are completely independent and protected from LRU eviction.
 * - Image cache (context.cacheDir/image_cache via Coil) is separate.
 *
 * Cache initialization is completely passive and NEVER starts MusicPlaybackService,
 * foreground service, or ExoPlayer playback.
 */
@OptIn(UnstableApi::class)
object AudioCache {
    private const val TAG = "AudioCache"
    const val DEFAULT_CACHE_CAP_MB: Int = 512
    const val MIN_CACHE_CAP_MB: Int = 64
    const val MAX_CACHE_CAP_MB: Int = 8192
    const val MAX_CACHE_SIZE_BYTES: Long = 512L * 1024L * 1024L // 512 MB default constant
    const val PREFS_NAME = "already_prefs"
    const val KEY_CACHE_CAP_MB = "audio_cache_cap_mb"
    private const val KEY_PRUNED_PARTIAL_SPANS_V161 = "pruned_partial_precache_spans_v161"
    const val AUDIO_CACHE_DIR = "audio_cache"

    val PRESET_CAPS_MB = listOf(128, 256, 512, 1024, 2048, 4096)

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Volatile
    private var evictorInstance: DynamicLruCacheEvictor? = null

    @Volatile
    private var databaseProviderInstance: StandaloneDatabaseProvider? = null

    @Volatile
    private var streamingHttpClientInstance: OkHttpClient? = null

    private val fullyCachedSongIdsMemory = ConcurrentHashMap.newKeySet<Int>()
    private val downloadedSongIdsMemory = ConcurrentHashMap.newKeySet<Int>()
    private val cachedContentLengthBySongId = ConcurrentHashMap<Int, Long>()
    private const val CACHE_FRAGMENT_SIZE_BYTES = 20L * 1024L * 1024L

    fun getCachedContentLengthFast(songId: Int): Long {
        if (songId <= 0) return -1L
        return cachedContentLengthBySongId[songId] ?: -1L
    }

    fun updateCachedContentLength(songId: Int, lengthBytes: Long) {
        if (songId > 0 && lengthBytes > 0L) {
            cachedContentLengthBySongId[songId] = lengthBytes
        }
    }

    fun cancelActivePreloadForSong(songId: Int) {
        // Early audio pre-caching is disabled so active playback streams uninterrupted.
    }

    fun cancelAllActivePreloads() {
        // Early audio pre-caching is disabled so active playback streams uninterrupted.
    }

    private val _playableOfflineSongIds = MutableStateFlow<Set<Int>>(emptySet())
    val playableOfflineSongIds: StateFlow<Set<Int>> = _playableOfflineSongIds.asStateFlow()

    @Volatile
    private var cacheIndexWarmed: Boolean = false
    private val _cacheIndexWarmedState = MutableStateFlow(false)
    val cacheIndexWarmedState: StateFlow<Boolean> = _cacheIndexWarmedState.asStateFlow()

    @Volatile
    private var indexWarmingScheduled: Boolean = false

    @Volatile
    private var earlyOfflineTruncatedSongId: Int? = null

    fun isCacheIndexWarmed(): Boolean = cacheIndexWarmed

    fun markEarlyOfflineTruncation(songId: Int?) {
        earlyOfflineTruncatedSongId = songId
    }

    fun consumeEarlyOfflineTruncation(songId: Int?): Boolean {
        val recorded = earlyOfflineTruncatedSongId ?: return false
        return if (songId != null && recorded == songId) {
            earlyOfflineTruncatedSongId = null
            true
        } else {
            false
        }
    }

    fun clearEarlyOfflineTruncation() {
        earlyOfflineTruncatedSongId = null
    }

    private fun publishPlayableOfflineIds() {
        val merged = HashSet<Int>(downloadedSongIdsMemory.size + fullyCachedSongIdsMemory.size)
        merged.addAll(downloadedSongIdsMemory)
        merged.addAll(fullyCachedSongIdsMemory)
        if (_playableOfflineSongIds.value != merged) {
            _playableOfflineSongIds.value = merged
        }
    }

    fun markDownloaded(songId: Int) {
        if (songId > 0) {
            downloadedSongIdsMemory.add(songId)
            publishPlayableOfflineIds()
        }
    }

    fun unmarkDownloaded(songId: Int) {
        downloadedSongIdsMemory.remove(songId)
        publishPlayableOfflineIds()
    }

    fun markDownloadedAll(songIds: Collection<Int>) {
        if (songIds.isNotEmpty()) {
            downloadedSongIdsMemory.addAll(songIds)
            publishPlayableOfflineIds()
        }
    }

    fun refreshSongCachedStatusAsync(context: Context, songId: Int, durationSec: Double = 0.0) {
        if (songId <= 0) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = "song:$songId"
                val cache = getSimpleCache(appContext)
                val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (len > 0L) {
                    cachedContentLengthBySongId[songId] = len
                }
                if (isKeyFullyCached(appContext, key, durationSec)) {
                    if (fullyCachedSongIdsMemory.add(songId)) {
                        publishPlayableOfflineIds()
                    }
                } else {
                    if (fullyCachedSongIdsMemory.remove(songId)) {
                        publishPlayableOfflineIds()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun selectIncompleteCacheKeysToPrune(
        keys: Collection<String>,
        isKeyFullyCachedCheck: (String) -> Boolean
    ): List<String> {
        return keys.filter { key ->
            extractSongIdFromKey(key) != null && !isKeyFullyCachedCheck(key)
        }
    }

    private fun pruneIncompletePreCachedSpansOnce(appContext: Context, cache: SimpleCache) {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_PRUNED_PARTIAL_SPANS_V161, false)) return
            val keysSnapshot = cache.keys.toList()
            val keysToPrune = selectIncompleteCacheKeysToPrune(keysSnapshot) { key ->
                isKeyFullyCached(appContext, key)
            }
            for (key in keysToPrune) {
                runCatching { cache.removeResource(key) }
                extractSongIdFromKey(key)?.let { cachedContentLengthBySongId.remove(it) }
            }
            prefs.edit().putBoolean(KEY_PRUNED_PARTIAL_SPANS_V161, true).apply()
            if (keysToPrune.isNotEmpty()) {
                Log.d(TAG, "Pruned ${keysToPrune.size} incomplete/partial pre-cached span keys")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skipped partial span cleanup: ${e.message}")
        }
    }

    fun warmUpCacheIndexAsync(context: Context) {
        if (cacheIndexWarmed || indexWarmingScheduled) return
        indexWarmingScheduled = true
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadsDir = File(appContext.filesDir, "downloads")
                downloadsDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".mp3") && file.length() > 0L) {
                        name.removeSuffix(".mp3").toIntOrNull()?.let { downloadedSongIdsMemory.add(it) }
                    }
                }
                val cache = getSimpleCache(appContext)
                pruneIncompletePreCachedSpansOnce(appContext, cache)
                getFullyCachedSongIds(appContext, forceRescan = true)
            } catch (_: Exception) {
            } finally {
                cacheIndexWarmed = true
                _cacheIndexWarmedState.value = true
                publishPlayableOfflineIds()
            }
        }
    }

    /**
     * Non-blocking O(1) offline playability check safe to call on Main thread or in loops.
     */
    fun isSongPlayableOfflineFast(context: Context, song: Song): Boolean {
        if (downloadedSongIdsMemory.contains(song.id) || fullyCachedSongIdsMemory.contains(song.id)) return true
        if (!cacheIndexWarmed) {
            warmUpCacheIndexAsync(context)
            return song.isDownloaded || song.isCached || !song.localPath.isNullOrBlank()
        }
        return false
    }

    /**
     * Checks whether a track has buffered/cached bytes stored in SimpleCache (including partial cache)
     * or is available locally. Used to keep playing cached tails seamlessly after connectivity drops.
     */
    fun hasCachedAudioBytes(context: Context, songId: Int, minBytes: Long = 32_768L): Boolean {
        if (songId <= 0) return false
        if (downloadedSongIdsMemory.contains(songId) || fullyCachedSongIdsMemory.contains(songId)) return true
        return try {
            val cache = getSimpleCache(context)
            cache.getCachedBytes("song:$songId", 0L, -1L) >= minBytes
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves the current user-configured cache memory cap in megabytes.
     * Defaults to 512 MB.
     */
    fun getCacheCapMb(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CACHE_CAP_MB, DEFAULT_CACHE_CAP_MB)
    }

    /**
     * Retrieves the current user-configured cache memory cap in bytes.
     */
    fun getCacheCapBytes(context: Context): Long {
        return getCacheCapMb(context).toLong() * 1024L * 1024L
    }

    /**
     * Updates the user-configured cache memory cap.
     * Persists the setting and immediately adjusts the live LRU evictor,
     * triggering eviction of older spans if the new cap is smaller than current usage.
     */
    fun setCacheCapMb(context: Context, capMb: Int): Int {
        val validCap = capMb.coerceIn(MIN_CACHE_CAP_MB, MAX_CACHE_CAP_MB)
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CACHE_CAP_MB, validCap).apply()

        val newCapBytes = validCap.toLong() * 1024L * 1024L
        synchronized(this) {
            evictorInstance?.setMaxBytes(newCapBytes, simpleCacheInstance)
        }
        Log.d(TAG, "Audio cache cap updated to $validCap MB ($newCapBytes bytes)")
        return validCap
    }

    /**
     * Formats bytes into a human-readable string (KB, MB, or GB).
     */
    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.1f GB", mb / 1024.0)
        } else if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            val kb = bytes / 1024.0
            String.format(Locale.US, "%.0f KB", kb)
        }
    }

    /**
     * Formats megabytes into a human-readable string (MB or GB).
     */
    fun formatCapMb(capMb: Int): String {
        return if (capMb >= 1024) {
            if (capMb % 1024 == 0) "${capMb / 1024} GB" else String.format(Locale.US, "%.1f GB", capMb / 1024.0)
        } else {
            "$capMb MB"
        }
    }

    /**
     * Estimates the number of full audio songs stored in the given cap (assuming ~5 MB / song average).
     */
    fun estimateSongsForCap(capMb: Int): Int {
        return (capMb / 5).coerceAtLeast(1)
    }

    /**
     * Stable cache key factory:
     * - Uses customCacheKey if already provided on DataSpec (e.g. "song:<id>").
     * - Fallback: extracts song id from backend URL pattern (/songs/{id}/stream).
     * - Strips dynamic query parameters/tokens so that token rotations do not cause
     *   duplicate cache entries or cache misses for the same underlying song.
     */
    /**
     * Computes a stable cache key:
     * - Uses explicit key if provided (e.g. "song:<id>").
     * - Extracts song id from URL pattern (/songs/{id}/stream).
     * - Strips dynamic query parameters/tokens (?token=...) to avoid cache fragmentation.
     */
    fun buildCacheKey(uriString: String, explicitKey: String? = null): String {
        if (!explicitKey.isNullOrEmpty()) {
            return explicitKey
        }
        val match = Regex("""/songs/(\d+)(?:/stream)?""").find(uriString)
        return if (match != null) {
            "song:${match.groupValues[1]}"
        } else {
            uriString.substringBefore('?')
        }
    }

    val cacheKeyFactory: CacheKeyFactory = CacheKeyFactory { dataSpec ->
        buildCacheKey(dataSpec.uri.toString(), dataSpec.key)
    }

    /**
     * Returns the singleton SimpleCache instance using application context.
     * Thread-safe and safe to call from any thread or during startup.
     */
    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        val appContext = context.applicationContext
        return simpleCacheInstance ?: synchronized(this) {
            simpleCacheInstance ?: run {
                val cacheDir = File(appContext.cacheDir, AUDIO_CACHE_DIR).apply {
                    if (!exists()) {
                        mkdirs()
                    }
                }
                val dbProvider = databaseProviderInstance ?: StandaloneDatabaseProvider(appContext).also {
                    databaseProviderInstance = it
                }
                val initialCapBytes = getCacheCapBytes(appContext)
                val evictor = DynamicLruCacheEvictor(initialCapBytes).apply {
                    onKeyEvicted = { key ->
                        extractSongIdFromKey(key)?.let { evictedId ->
                            if (fullyCachedSongIdsMemory.remove(evictedId)) {
                                publishPlayableOfflineIds()
                            }
                        }
                    }
                }.also {
                    evictorInstance = it
                }
                SimpleCache(cacheDir, evictor, dbProvider).also {
                    simpleCacheInstance = it
                    Log.d(TAG, "Initialized shared SimpleCache at ${cacheDir.absolutePath} (cap: ${getCacheCapMb(appContext)} MB)")
                }
            }
        }
    }

    private fun getStreamingOkHttpClient(): OkHttpClient {
        return streamingHttpClientInstance ?: synchronized(this) {
            streamingHttpClientInstance ?: OkHttpClient.Builder()
                .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .cache(null)
                .addInterceptor(AppSecurityInterceptor())
                .build()
                .also { streamingHttpClientInstance = it }
        }
    }

    private fun createHttpDataSourceFactory(): OkHttpAudioDataSource.Factory {
        return OkHttpAudioDataSource.Factory(getStreamingOkHttpClient())
            .setUserAgent("AlaktraAndroidApp/${com.example.BuildConfig.VERSION_NAME}")
            .setCacheControl(CacheControl.Builder().noTransform().build())
            .setDefaultRequestProperties(
                mapOf("X-App-Secret" to com.example.BuildConfig.API_SECRET)
            )
    }

    /**
     * Builds a DataSource.Factory:
     * - Delegates file://, content://, asset:// directly (used for local downloaded MP3s).
     * - Delegates http:// and https:// through CacheDataSource with SimpleCache and OkHttp upstream.
     * - Fails upstream HTTP opens immediately when offline instead of hanging for socket timeouts.
     * - Truncates gracefully with C.RESULT_END_OF_INPUT at the boundary of already-cached bytes when offline
     *   so ExoPlayer finishes playing every cached millisecond smoothly and transitions via STATE_ENDED.
     */
    fun createCacheDataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        val cache = runCatching { getSimpleCache(appContext) }.getOrNull()
        val rawHttpFactory = createHttpDataSourceFactory()

        val offlineFastFailUpstream = DataSource.Factory {
            val delegate = rawHttpFactory.createDataSource() as OkHttpAudioDataSource
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) {
                    delegate.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    if (!NetworkMonitor.isOnline(appContext)) {
                        throw IOException("Offline: skipping upstream HTTP request")
                    }
                    return try {
                        val len = delegate.open(dataSpec)
                        val openedUri = dataSpec.uri.toString()
                        val cacheKey = buildCacheKey(openedUri, dataSpec.key)
                        val songId = extractSongIdFromKey(cacheKey)
                        if (songId != null) {
                            val totalLen = delegate.getDocumentSizeBytes()
                            if (totalLen > 0L) {
                                cachedContentLengthBySongId[songId] = totalLen
                            } else if (dataSpec.position == 0L && len > 0L) {
                                cachedContentLengthBySongId[songId] = len
                            }
                        }
                        if (NetworkMonitor.matchesActiveServer(openedUri)) {
                            NetworkMonitor.markServerReachable()
                        }
                        len
                    } catch (e: IOException) {
                        markUnreachableIfServerError(appContext, dataSpec.uri.toString(), e, isSpeculative = true)
                        throw e
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return delegate.read(buffer, offset, length)
                }

                override fun getUri(): Uri? = delegate.uri
                override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
                override fun close() {
                    delegate.close()
                }
            }
        }

        if (cache == null) {
            return DefaultDataSource.Factory(appContext, offlineFastFailUpstream)
        }

        val cacheDataSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(CACHE_FRAGMENT_SIZE_BYTES)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
            .setUpstreamDataSourceFactory(offlineFastFailUpstream)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val gracefulCacheFactory = DataSource.Factory {
            val cacheDs = cacheDataSourceFactory.createDataSource()
            object : DataSource {
                private var bytesServedThisOpen: Long = 0L
                private var currentSongId: Int? = null
                private var hitOfflineEndOfCache: Boolean = false

                override fun addTransferListener(transferListener: TransferListener) {
                    cacheDs.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    bytesServedThisOpen = 0L
                    hitOfflineEndOfCache = false
                    val cacheKey = buildCacheKey(dataSpec.uri.toString(), dataSpec.key)
                    currentSongId = extractSongIdFromKey(cacheKey)
                    val len = cacheDs.open(dataSpec)
                    if (currentSongId != null && dataSpec.position == 0L && len > 0L) {
                        cachedContentLengthBySongId[currentSongId!!] = len
                    }
                    return len
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (hitOfflineEndOfCache) {
                        return C.RESULT_END_OF_INPUT
                    }
                    return try {
                        val read = cacheDs.read(buffer, offset, length)
                        if (read > 0) {
                            bytesServedThisOpen += read
                        }
                        read
                    } catch (e: IOException) {
                        if (!NetworkMonitor.isOnline(appContext) && bytesServedThisOpen > 0L) {
                            hitOfflineEndOfCache = true
                            markEarlyOfflineTruncation(currentSongId)
                            C.RESULT_END_OF_INPUT
                        } else {
                            throw e
                        }
                    }
                }

                override fun getUri(): Uri? = cacheDs.uri
                override fun getResponseHeaders(): Map<String, List<String>> = cacheDs.responseHeaders
                override fun close() {
                    bytesServedThisOpen = 0L
                    hitOfflineEndOfCache = false
                    try {
                        cacheDs.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }

        return DefaultDataSource.Factory(appContext, gracefulCacheFactory)
    }

    /**
     * Returns all song IDs currently retained in SimpleCache memory.
     */
    /**
     * Checks if a song is completely and fully cached (not partially) in SimpleCache or local downloads.
     */
    fun isSongFullyCached(context: Context, song: Song): Boolean {
        // 1. Permanent local downloaded file
        if (song.localPath != null && File(song.localPath).exists() && File(song.localPath).length() > 0) {
            return true
        }
        val downloadedFile = File(File(context.filesDir, "downloads"), "${song.id}.mp3")
        if (downloadedFile.exists() && downloadedFile.length() > 0) {
            return true
        }
        return isKeyFullyCached(context, "song:${song.id}", song.duration)
    }

    fun isSongFullyCached(context: Context, songId: Int, durationSec: Double = 0.0): Boolean {
        val downloadedFile = File(File(context.filesDir, "downloads"), "$songId.mp3")
        if (downloadedFile.exists() && downloadedFile.length() > 0) {
            return true
        }
        return isKeyFullyCached(context, "song:$songId", durationSec)
    }

    /**
     * Inspects Media3 SimpleCache to verify whether an entire song is 100% cached.
     * Partial streaming chunks (e.g. 512KB preloads or partially listened tracks) return false.
     */
    fun isKeyFullyCached(context: Context, key: String, durationSec: Double = 0.0): Boolean {
        return try {
            val cache = getSimpleCache(context)
            if (!cache.keys.contains(key)) return false

            val metadata = cache.getContentMetadata(key)
            val contentLength = ContentMetadata.getContentLength(metadata)

            if (contentLength > 0L) {
                // Exact file size is known: verify entire range [0, contentLength) is completely cached
                cache.isCached(key, 0L, contentLength)
            } else {
                // ContentLength unset: verify contiguous cached audio starting at byte 0 meets expected size
                val spans = cache.getCachedSpans(key)
                if (spans.isEmpty()) return false
                spans.firstOrNull { it.position == 0L } ?: return false
                val contiguousFrom0 = cache.getCachedLength(key, 0L, Long.MAX_VALUE)
                val minBytes = if (durationSec > 0.0) {
                    (durationSec * 12_000.0).toLong().coerceAtLeast(350_000L)
                } else {
                    1_000_000L // 1 MB minimum for complete audio file
                }
                contiguousFrom0 >= minBytes
            }
        } catch (e: Exception) {
            false
        }
    }

    fun extractSongIdFromKey(key: String): Int? {
        return if (key.startsWith("song:")) {
            key.removePrefix("song:").toIntOrNull()
        } else {
            Regex("""/songs/(\d+)""").find(key)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /**
     * Returns all song IDs that are FULLY cached in SimpleCache memory.
     * Once the index has warmed, returns the live in-memory set in O(1) without
     * re-acquiring SimpleCache locks unless [forceRescan] is true.
     */
    fun getFullyCachedSongIds(context: Context, forceRescan: Boolean = false): Set<Int> {
        if (!forceRescan && cacheIndexWarmed) {
            return fullyCachedSongIdsMemory.toSet()
        }
        return try {
            val cache = getSimpleCache(context)
            val ids = mutableSetOf<Int>()
            for (key in cache.keys) {
                val songId = extractSongIdFromKey(key) ?: continue
                val contentLen = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (contentLen > 0L) {
                    cachedContentLengthBySongId[songId] = contentLen
                }
                if (isKeyFullyCached(context, key)) {
                    ids.add(songId)
                }
            }
            fullyCachedSongIdsMemory.retainAll(ids)
            fullyCachedSongIdsMemory.addAll(ids)
            cacheIndexWarmed = true
            _cacheIndexWarmedState.value = true
            publishPlayableOfflineIds()
            ids
        } catch (e: Exception) {
            if (cacheIndexWarmed) fullyCachedSongIdsMemory.toSet() else emptySet()
        }
    }

    /**
     * Returns all song IDs currently retained in SimpleCache memory that are fully cached.
     */
    fun getCachedSongIds(context: Context): Set<Int> {
        return getFullyCachedSongIds(context, forceRescan = false)
    }

    /**
     * Returns the cached byte count for a given song in SimpleCache.
     */
    fun getSongCachedBytes(context: Context, songId: Int): Long {
        return try {
            val cache = getSimpleCache(context)
            val key = "song:$songId"
            cache.getCachedBytes(key, 0, -1)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Checks if a given song is fully cached in SimpleCache or downloaded.
     */
    fun isSongCached(context: Context, songId: Int): Boolean {
        return isSongFullyCached(context, songId)
    }

    /**
     * Estimates byte length for [seconds] of audio (used purely by offline/cache tests).
     */
    fun estimateBytesForSeconds(
        durationSec: Double,
        contentLength: Long,
        seconds: Int
    ): Long {
        if (seconds <= 0) return C.LENGTH_UNSET.toLong()
        val headerMarginBytes = 65_536L
        return if (contentLength > 0L && durationSec > 1.0) {
            val bytesPerSecond = (contentLength.toDouble() / durationSec).coerceIn(16_000.0, 250_000.0)
            ((bytesPerSecond * seconds).toLong() + headerMarginBytes).coerceAtMost(contentLength)
        } else {
            val estimate = (seconds.toLong() * 40_000L) + headerMarginBytes
            if (contentLength > 0L) estimate.coerceAtMost(contentLength) else estimate
        }
    }

    private fun markUnreachableIfServerError(
        context: Context,
        uriString: String?,
        error: IOException,
        isSpeculative: Boolean = false
    ) {
        if (!NetworkMonitor.hasNetworkTransport(context)) return
        if (!uriString.isNullOrBlank() && !NetworkMonitor.matchesActiveServer(uriString)) return
        if (error is HttpDataSource.InvalidResponseCodeException) {
            if (NetworkMonitor.isUnreachableHttpStatus(error.responseCode)) {
                NetworkMonitor.reportSuspectedServerFailure(
                    context = context,
                    serverUrl = uriString ?: "",
                    error = error,
                    httpCode = error.responseCode,
                    verifyFirst = isSpeculative
                )
            }
            return
        }
        NetworkMonitor.reportSuspectedServerFailure(
            context = context,
            serverUrl = uriString ?: "",
            error = error,
            verifyFirst = isSpeculative
        )
    }

    /**
     * Returns total cached bytes in the streaming audio cache.
     */
    fun getCacheSizeBytes(context: Context): Long {
        return try {
            getSimpleCache(context).cacheSpace
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Returns total cached bytes in the Coil artwork disk cache.
     */
    fun getImageCacheSizeBytes(context: Context): Long {
        return try {
            val dir = File(context.cacheDir, "image_cache")
            getFolderSizeBytes(dir)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Returns total cached bytes in the OkHttp API disk cache.
     */
    fun getHttpCacheSizeBytes(context: Context): Long {
        return try {
            val dir = File(context.cacheDir, "http_cache")
            getFolderSizeBytes(dir)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getFolderSizeBytes(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            size += getFolderSizeBytes(child)
        }
        return size
    }

    /**
     * Clears all cached streaming chunks without touching permanent downloads.
     */
    @Synchronized
    fun clearCache(context: Context) {
        try {
            val cache = getSimpleCache(context)
            for (key in cache.keys) {
                cache.removeResource(key)
            }
            fullyCachedSongIdsMemory.clear()
            cachedContentLengthBySongId.clear()
            publishPlayableOfflineIds()
            Log.d(TAG, "Cleared streaming audio cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear audio cache", e)
        }
    }

    /**
     * Clears artwork and HTTP disk cache.
     */
    fun clearArtworkAndHttpCache(context: Context) {
        try {
            val imageDir = File(context.cacheDir, "image_cache")
            if (imageDir.exists()) imageDir.deleteRecursively()
            val httpDir = File(context.cacheDir, "http_cache")
            if (httpDir.exists()) httpDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear artwork and HTTP cache", e)
        }
    }
}
