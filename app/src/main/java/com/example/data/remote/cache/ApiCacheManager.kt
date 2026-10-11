package com.example.data.remote.cache

import com.example.model.Playlist
import com.example.model.Song

/**
 * Thread-safe, bounded in-memory LRU cache manager for temporary API responses.
 *
 * Implements strict Time-To-Live (TTL) expiration, deterministic cache keys,
 * bounded entries to prevent memory leaks, and targeted cache invalidations on mutations.
 */
class ApiCacheManager(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val ttlMs: Long
    ) {
        fun isExpired(currentTime: Long): Boolean = (currentTime - timestamp) > ttlMs
    }

    /**
     * Internal generic bounded LRU container with time-based expiration.
     */
    class BoundedLruCache<K, V>(
        private val maxCapacity: Int,
        private val clock: () -> Long = { System.currentTimeMillis() }
    ) {
        private val map = object : LinkedHashMap<K, CacheEntry<V>>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean {
                return size > maxCapacity
            }
        }

        @Synchronized
        fun get(key: K): V? {
            val entry = map[key] ?: return null
            if (entry.isExpired(clock())) {
                map.remove(key)
                return null
            }
            return entry.data
        }

        @Synchronized
        fun getEntry(key: K): CacheEntry<V>? {
            val entry = map[key] ?: return null
            if (entry.isExpired(clock())) {
                map.remove(key)
                return null
            }
            return entry
        }

        @Synchronized
        fun containsKey(key: K): Boolean {
            val entry = map[key] ?: return false
            if (entry.isExpired(clock())) {
                map.remove(key)
                return false
            }
            return true
        }

        @Synchronized
        fun put(key: K, value: V, ttlMs: Long) {
            map[key] = CacheEntry(value, clock(), ttlMs)
        }

        @Synchronized
        fun remove(key: K) {
            map.remove(key)
        }

        @Synchronized
        fun removeMatching(predicate: (K) -> Boolean) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                if (predicate(it.next().key)) {
                    it.remove()
                }
            }
        }

        @Synchronized
        fun updateAll(transform: (V) -> V) {
            for ((key, entry) in map.entries) {
                map[key] = entry.copy(data = transform(entry.data))
            }
        }

        @Synchronized
        fun clear() {
            map.clear()
        }

        @Synchronized
        fun size(): Int = map.size
    }

    // Separate bounded LRU pools per domain
    private val searchCache = BoundedLruCache<String, List<Song>>(ApiCacheConfig.MAX_SEARCH_ENTRIES, clock)
    private val feedCache = BoundedLruCache<String, List<Song>>(ApiCacheConfig.MAX_FEED_ENTRIES, clock)
    private val playlistSongsCache = BoundedLruCache<Int, List<Song>>(ApiCacheConfig.MAX_PLAYLIST_ENTRIES, clock)
    private val userPlaylistsCache = BoundedLruCache<Int, List<Playlist>>(ApiCacheConfig.MAX_PLAYLIST_ENTRIES, clock)
    private val albumCache = BoundedLruCache<String, List<Song>>(ApiCacheConfig.MAX_ALBUM_ENTRIES, clock)
    private val artistCache = BoundedLruCache<String, List<Song>>(ApiCacheConfig.MAX_ARTIST_ENTRIES, clock)
    private val recommendationsCache = BoundedLruCache<String, List<Song>>(ApiCacheConfig.MAX_RECOMMENDATIONS_ENTRIES, clock)
    private val recentlyPlayedCache = BoundedLruCache<String, List<Song>>(ApiCacheConfig.MAX_RECENTLY_PLAYED_ENTRIES, clock)

    // ==========================================
    // SEARCH RESULTS CACHE (Short TTL)
    // ==========================================

    fun getSearch(key: String): List<Song>? {
        return searchCache.get(key)
    }

    fun putSearch(key: String, songs: List<Song>) {
        searchCache.put(key, songs, ApiCacheConfig.SEARCH_TTL_MS)
    }

    fun invalidateSearch(key: String? = null) {
        if (key != null) {
            searchCache.remove(key)
        } else {
            searchCache.clear()
        }
    }

    // ==========================================
    // HOME FEED / CATALOG CACHE (Medium TTL)
    // ==========================================

    fun getFeed(key: String = "home_feed"): List<Song>? {
        return feedCache.get(key)
    }

    fun putFeed(key: String = "home_feed", songs: List<Song>) {
        feedCache.put(key, songs, ApiCacheConfig.HOME_FEED_TTL_MS)
    }

    fun invalidateFeed(key: String = "home_feed") {
        feedCache.remove(key)
    }

    // ==========================================
    // PLAYLISTS CACHE (Medium TTL)
    // ==========================================

    fun getUserPlaylists(userId: Int): List<Playlist>? {
        return userPlaylistsCache.get(userId)
    }

    fun putUserPlaylists(userId: Int, playlists: List<Playlist>) {
        userPlaylistsCache.put(userId, playlists, ApiCacheConfig.PLAYLISTS_TTL_MS)
    }

    fun invalidateUserPlaylists(userId: Int) {
        userPlaylistsCache.remove(userId)
    }

    fun getPlaylistSongs(playlistId: Int): List<Song>? {
        return playlistSongsCache.get(playlistId)
    }

    fun putPlaylistSongs(playlistId: Int, songs: List<Song>) {
        playlistSongsCache.put(playlistId, songs, ApiCacheConfig.PLAYLIST_SONGS_TTL_MS)
    }

    fun invalidatePlaylistSongs(playlistId: Int) {
        playlistSongsCache.remove(playlistId)
    }

    // ==========================================
    // ALBUM CACHE (Long TTL)
    // ==========================================

    fun getAlbum(key: String): List<Song>? {
        return albumCache.get(key.lowercase().trim())
    }

    fun putAlbum(key: String, songs: List<Song>) {
        albumCache.put(key.lowercase().trim(), songs, ApiCacheConfig.ALBUM_TTL_MS)
    }

    fun invalidateAlbum(key: String) {
        albumCache.remove(key.lowercase().trim())
    }

    // ==========================================
    // ARTIST CACHE (Long TTL)
    // ==========================================

    fun getArtist(key: String): List<Song>? {
        return artistCache.get(key.lowercase().trim())
    }

    fun putArtist(key: String, songs: List<Song>) {
        artistCache.put(key.lowercase().trim(), songs, ApiCacheConfig.ARTIST_TTL_MS)
    }

    fun invalidateArtist(key: String) {
        artistCache.remove(key.lowercase().trim())
    }

    // ==========================================
    // RECOMMENDATIONS CACHE (Short TTL)
    // ==========================================

    fun getRecommendations(key: String = "recommendations"): List<Song>? {
        return recommendationsCache.get(key)
    }

    fun putRecommendations(key: String = "recommendations", songs: List<Song>) {
        recommendationsCache.put(key, songs, ApiCacheConfig.RECOMMENDATIONS_TTL_MS)
    }

    fun invalidateRecommendations(key: String = "recommendations") {
        recommendationsCache.remove(key)
    }

    // ==========================================
    // RECENTLY PLAYED CACHE (Short TTL)
    // ==========================================

    fun getRecentlyPlayed(key: String = "recently_played"): List<Song>? {
        return recentlyPlayedCache.get(key)
    }

    fun putRecentlyPlayed(key: String = "recently_played", songs: List<Song>) {
        recentlyPlayedCache.put(key, songs, ApiCacheConfig.RECENTLY_PLAYED_TTL_MS)
    }

    fun invalidateRecentlyPlayed(key: String = "recently_played") {
        recentlyPlayedCache.remove(key)
    }

    // ==========================================
    // TARGETED MUTATION INVALIDATION
    // ==========================================

    /**
     * Propagates like/unlike changes through all in-memory cache pools so stale like status
     * is never served before TTL expiry.
     */
    fun updateSongLike(songId: Int, isLiked: Boolean) {
        val songUpdater: (List<Song>) -> List<Song> = { list ->
            list.map { if (it.id == songId) it.copy(isLiked = isLiked) else it }
        }
        searchCache.updateAll(songUpdater)
        feedCache.updateAll(songUpdater)
        playlistSongsCache.updateAll(songUpdater)
        albumCache.updateAll(songUpdater)
        artistCache.updateAll(songUpdater)
        recommendationsCache.updateAll(songUpdater)
        recentlyPlayedCache.updateAll(songUpdater)
    }

    /**
     * Clear all in-memory caches on user logout.
     */
    fun clearAll() {
        searchCache.clear()
        feedCache.clear()
        playlistSongsCache.clear()
        userPlaylistsCache.clear()
        albumCache.clear()
        artistCache.clear()
        recommendationsCache.clear()
        recentlyPlayedCache.clear()
    }
}
