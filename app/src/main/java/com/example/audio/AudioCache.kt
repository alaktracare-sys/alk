package com.example.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.example.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.TreeSet

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

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
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
    const val AUDIO_CACHE_DIR = "audio_cache"

    val PRESET_CAPS_MB = listOf(128, 256, 512, 1024, 2048, 4096)

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Volatile
    private var evictorInstance: DynamicLruCacheEvictor? = null

    @Volatile
    private var databaseProviderInstance: StandaloneDatabaseProvider? = null

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
                val evictor = DynamicLruCacheEvictor(initialCapBytes).also {
                    evictorInstance = it
                }
                SimpleCache(cacheDir, evictor, dbProvider).also {
                    simpleCacheInstance = it
                    Log.d(TAG, "Initialized shared SimpleCache at ${cacheDir.absolutePath} (cap: ${getCacheCapMb(appContext)} MB)")
                }
            }
        }
    }

    /**
     * Builds a DataSource.Factory:
     * - Delegates file://, content://, asset:// directly (used for local downloaded MP3s).
     * - Delegates http:// and https:// through CacheDataSource with SimpleCache.
     * - Uses FLAG_IGNORE_CACHE_ON_ERROR so any disk/cache failure safely falls back to network.
     */
    fun createCacheDataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        val cache = getSimpleCache(appContext)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultDataSource.Factory(appContext, cacheDataSourceFactory)
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
                // ContentLength unset: verify contiguous span starting at 0 and total bytes exceeds expected audio size
                val spans = cache.getCachedSpans(key)
                if (spans.isEmpty()) return false
                val span0 = spans.firstOrNull { it.position == 0L } ?: return false
                val totalBytes = cache.getCachedBytes(key, 0L, -1L)
                val minBytes = if (durationSec > 0.0) {
                    (durationSec * 12_000.0).toLong().coerceAtLeast(350_000L)
                } else {
                    1_000_000L // 1 MB minimum for complete audio file
                }
                span0.length >= minBytes && totalBytes >= minBytes
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns all song IDs that are FULLY cached in SimpleCache memory.
     */
    fun getFullyCachedSongIds(context: Context): Set<Int> {
        return try {
            val cache = getSimpleCache(context)
            val ids = mutableSetOf<Int>()
            for (key in cache.keys) {
                val songId = if (key.startsWith("song:")) {
                    key.removePrefix("song:").toIntOrNull()
                } else {
                    Regex("""/songs/(\d+)""").find(key)?.groupValues?.get(1)?.toIntOrNull()
                } ?: continue

                if (isKeyFullyCached(context, key)) {
                    ids.add(songId)
                }
            }
            ids
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Returns all song IDs currently retained in SimpleCache memory that are fully cached.
     */
    fun getCachedSongIds(context: Context): Set<Int> {
        return getFullyCachedSongIds(context)
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
     * Preloads an upcoming track into the streaming audio cache asynchronously.
     * Caches the entire track so that transitions to offline mode have full tracks.
     */
    fun preloadTrack(
        context: Context,
        audioUrl: String,
        songId: Int
    ) {
        if (audioUrl.isBlank() || audioUrl.startsWith("file://")) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cache = getSimpleCache(appContext)
                val key = "song:$songId"
                if (isSongFullyCached(appContext, songId)) {
                    return@launch
                }
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(audioUrl))
                    .setKey(key)
                    .setPosition(0)
                    .build()

                val httpDataSource = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                    .createDataSource()

                val cacheDataSource = CacheDataSource(
                    cache,
                    httpDataSource,
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                )

                val cacheWriter = CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    null,
                    null
                )
                cacheWriter.cache()
                Log.d(TAG, "Successfully fully cached upcoming song #$songId")
            } catch (e: Exception) {
                Log.d(TAG, "Preload skipped or interrupted for song #$songId: ${e.message}")
            }
        }
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
