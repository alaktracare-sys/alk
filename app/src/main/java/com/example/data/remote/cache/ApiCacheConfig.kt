package com.example.data.remote.cache

/**
 * Centralized configuration and TTL definitions for Phase 3 API/Network caching.
 */
object ApiCacheConfig {
    // OkHttp Disk Cache configuration
    const val HTTP_CACHE_DIR = "http_cache"
    const val HTTP_CACHE_MAX_BYTES = 20L * 1024L * 1024L // 20 MB bounded disk cache

    // Centralized TTLs (Time-To-Live in milliseconds)
    const val SEARCH_TTL_MS = 2L * 60 * 1000            // 2 minutes for search results
    const val HOME_FEED_TTL_MS = 5L * 60 * 1000         // 5 minutes for home feed/catalog
    const val PLAYLISTS_TTL_MS = 3L * 60 * 1000         // 3 minutes for user playlist list
    const val PLAYLIST_SONGS_TTL_MS = 3L * 60 * 1000     // 3 minutes for playlist songs
    const val ALBUM_ARTIST_TTL_MS = 30L * 60 * 1000     // 30 minutes for album/artist metadata
    const val ALBUM_TTL_MS = 30L * 60 * 1000            // 30 minutes for album details & track listing
    const val ARTIST_TTL_MS = 30L * 60 * 1000           // 30 minutes for artist details & discography
    const val RECOMMENDATIONS_TTL_MS = 3L * 60 * 1000   // 3 minutes for recommendations
    const val RECENTLY_PLAYED_TTL_MS = 2L * 60 * 1000   // 2 minutes for recently played API results

    // In-memory bounded LRU capacities
    const val MAX_SEARCH_ENTRIES = 50
    const val MAX_FEED_ENTRIES = 20
    const val MAX_PLAYLIST_ENTRIES = 50
    const val MAX_ALBUM_ENTRIES = 50
    const val MAX_ARTIST_ENTRIES = 50
    const val MAX_RECOMMENDATIONS_ENTRIES = 20
    const val MAX_RECENTLY_PLAYED_ENTRIES = 20

    /**
     * Constructs a deterministic, normalized search cache key including pagination parameters.
     */
    fun buildSearchCacheKey(
        query: String,
        page: Int = 1,
        limit: Int = 30,
        offset: Int = 0,
        filter: String? = null,
        sort: String? = null
    ): String {
        val normalized = query.trim().lowercase()
        val filterPart = if (!filter.isNullOrBlank()) ":filter=${filter.trim().lowercase()}" else ""
        val sortPart = if (!sort.isNullOrBlank()) ":sort=${sort.trim().lowercase()}" else ""
        return "search:$normalized:page=$page:limit=$limit:offset=$offset$filterPart$sortPart"
    }

    /**
     * Constructs a cache key for user playlists.
     */
    fun buildUserPlaylistsCacheKey(userId: Int): String {
        return "playlists:user:$userId"
    }

    /**
     * Constructs a cache key for playlist songs.
     */
    fun buildPlaylistSongsCacheKey(playlistId: Int): String {
        return "playlist_songs:$playlistId"
    }

    /**
     * Constructs a cache key for album track listing and metadata.
     */
    fun buildAlbumCacheKey(album: String): String {
        return "album:${album.trim().lowercase()}"
    }

    /**
     * Constructs a cache key for artist discography and metadata.
     */
    fun buildArtistCacheKey(artist: String): String {
        return "artist:${artist.trim().lowercase()}"
    }

    /**
     * Constructs a cache key for recommendations.
     */
    fun buildRecommendationsCacheKey(userId: Int, seedSongId: Int? = null): String {
        val seed = if (seedSongId != null) ":seed=$seedSongId" else ""
        return "recommendations:user:$userId$seed"
    }

    /**
     * Constructs a cache key for recently played API results.
     */
    fun buildRecentlyPlayedCacheKey(userId: Int): String {
        return "recently_played:user:$userId"
    }
}
