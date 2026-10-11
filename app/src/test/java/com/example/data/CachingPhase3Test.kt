package com.example.data

import com.example.data.remote.cache.ApiCacheConfig
import com.example.data.remote.cache.ApiCacheManager
import com.example.data.remote.cache.NetworkCacheInterceptor
import com.example.data.remote.cache.OfflineCacheInterceptor
import com.example.model.Playlist
import com.example.model.Song
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CachingPhase3Test {

    private fun sampleSong(id: Int, title: String = "Track $id", artist: String = "Artist $id", isLiked: Boolean = false): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            audioUrl = "https://cdn.example.com/audio/$id.mp3",
            coverUrl = "https://cdn.example.com/cover/$id.jpg",
            duration = 180.0,
            isLiked = isLiked
        )
    }

    // ==========================================
    // 1. CACHE KEYS & NORMALIZATION (Search & Pagination)
    // ==========================================

    @Test
    fun testSearchCacheKey_normalizationAndDeterminism() {
        val key1 = ApiCacheConfig.buildSearchCacheKey("  Central Cee  ")
        val key2 = ApiCacheConfig.buildSearchCacheKey("central cee")
        assertEquals("Search cache keys must be normalized and trimmed lowercase", key1, key2)
        assertEquals("search:central cee:page=1:limit=30:offset=0", key1)
    }

    @Test
    fun testSearchCacheKey_paginationIsolation() {
        val page1Key = ApiCacheConfig.buildSearchCacheKey("synthwave", page = 1, limit = 20, offset = 0)
        val page2Key = ApiCacheConfig.buildSearchCacheKey("synthwave", page = 2, limit = 20, offset = 20)

        assertNotEquals("Page 1 and Page 2 must never share the same cache key", page1Key, page2Key)
        assertTrue(page1Key.contains("offset=0"))
        assertTrue(page2Key.contains("offset=20"))
    }

    @Test
    fun testSearchCacheKey_filtersAndSortPreserved() {
        val allKey = ApiCacheConfig.buildSearchCacheKey("lofi", filter = null)
        val downloadedKey = ApiCacheConfig.buildSearchCacheKey("lofi", filter = "downloaded")
        val favoritesKey = ApiCacheConfig.buildSearchCacheKey("lofi", filter = "favorites")

        assertNotEquals(allKey, downloadedKey)
        assertNotEquals(downloadedKey, favoritesKey)
        assertTrue(downloadedKey.contains(":filter=downloaded"))
    }

    @Test
    fun testDomainCacheKeys_formatting() {
        assertEquals("playlists:user:42", ApiCacheConfig.buildUserPlaylistsCacheKey(42))
        assertEquals("playlist_songs:101", ApiCacheConfig.buildPlaylistSongsCacheKey(101))
        assertEquals("album:midnight memories", ApiCacheConfig.buildAlbumCacheKey("  Midnight Memories  "))
        assertEquals("artist:the weeknd", ApiCacheConfig.buildArtistCacheKey("  The Weeknd  "))
        assertEquals("recommendations:user:7:seed=99", ApiCacheConfig.buildRecommendationsCacheKey(7, 99))
        assertEquals("recently_played:user:7", ApiCacheConfig.buildRecentlyPlayedCacheKey(7))
    }

    // ==========================================
    // 2. BOUNDED LRU STORAGE & CAPACITY LIMITS
    // ==========================================

    @Test
    fun testBoundedLru_strictlyEnforcesCapacityAndEvictsLeastRecentlyUsed() {
        val virtualTime = AtomicLong(1000L)
        val cache = ApiCacheManager.BoundedLruCache<String, String>(
            maxCapacity = 3,
            clock = { virtualTime.get() }
        )

        cache.put("k1", "v1", ttlMs = 100_000)
        cache.put("k2", "v2", ttlMs = 100_000)
        cache.put("k3", "v3", ttlMs = 100_000)
        assertEquals(3, cache.size())

        // Access k1 so k2 becomes the eldest / least recently used
        assertEquals("v1", cache.get("k1"))

        // Insert k4, exceeding capacity 3 -> k2 should be evicted
        cache.put("k4", "v4", ttlMs = 100_000)
        assertEquals(3, cache.size())
        assertNotNull("k1 was recently accessed and must remain", cache.get("k1"))
        assertNull("k2 was least recently used and must be evicted", cache.get("k2"))
        assertNotNull("k3 must remain", cache.get("k3"))
        assertNotNull("k4 must remain", cache.get("k4"))
    }

    // ==========================================
    // 3. TTL EXPIRATION BEHAVIOR
    // ==========================================

    @Test
    fun testTtlExpiration_evictsExpiredEntriesOnAccess() {
        val virtualTime = AtomicLong(1000L)
        val cacheManager = ApiCacheManager(clock = { virtualTime.get() })

        val key = ApiCacheConfig.buildSearchCacheKey("synthwave")
        val songs = listOf(sampleSong(1, "Resonance"))

        // Put search results (TTL = SEARCH_TTL_MS = 2 minutes)
        cacheManager.putSearch(key, songs)

        // 1. Within TTL (1 minute later): entry is valid
        virtualTime.addAndGet(60 * 1000L)
        val cachedBeforeExpiry = cacheManager.getSearch(key)
        assertNotNull("Search results must be valid within TTL", cachedBeforeExpiry)
        assertEquals(1, cachedBeforeExpiry?.size)

        // 2. Beyond TTL (2.5 minutes later = 3.5 minutes total from put): entry expires
        virtualTime.addAndGet(150 * 1000L)
        val cachedAfterExpiry = cacheManager.getSearch(key)
        assertNull("Search results must expire and return null after TTL", cachedAfterExpiry)
    }

    @Test
    fun testDifferentiatedTtl_albumOutlivesSearchAndFeed() {
        val virtualTime = AtomicLong(1000L)
        val cacheManager = ApiCacheManager(clock = { virtualTime.get() })

        val searchKey = ApiCacheConfig.buildSearchCacheKey("jazz")
        val feedKey = "home_feed"
        val albumKey = "kind of blue"

        cacheManager.putSearch(searchKey, listOf(sampleSong(1)))
        cacheManager.putFeed(feedKey, listOf(sampleSong(2)))
        cacheManager.putAlbum(albumKey, listOf(sampleSong(3)))

        // Advance 10 minutes (search TTL: 2m, feed TTL: 5m, album TTL: 30m)
        virtualTime.addAndGet(10 * 60 * 1000L)

        assertNull("Search should have expired after 10m (TTL=2m)", cacheManager.getSearch(searchKey))
        assertNull("Feed should have expired after 10m (TTL=5m)", cacheManager.getFeed(feedKey))
        assertNotNull("Album metadata must remain valid after 10m (TTL=30m)", cacheManager.getAlbum(albumKey))
    }

    // ==========================================
    // 4. TARGETED CACHE INVALIDATION & LIKE PROPAGATION
    // ==========================================

    @Test
    fun testUpdateSongLike_propagatesAcrossAllActivePoolsWithoutEviction() {
        val cacheManager = ApiCacheManager()

        val searchKey = ApiCacheConfig.buildSearchCacheKey("dance")
        val song1 = sampleSong(10, "One More Time", isLiked = false)
        val song2 = sampleSong(20, "Harder Better", isLiked = false)

        cacheManager.putSearch(searchKey, listOf(song1, song2))
        cacheManager.putFeed("home_feed", listOf(song1))
        cacheManager.putPlaylistSongs(5, listOf(song1))
        cacheManager.putAlbum("discovery", listOf(song1))
        cacheManager.putArtist("daft punk", listOf(song1))
        cacheManager.putRecommendations("recs", listOf(song1))
        cacheManager.putRecentlyPlayed("recent", listOf(song1))

        // User likes song 10
        cacheManager.updateSongLike(songId = 10, isLiked = true)

        // Verify all pools reflect new liked status immediately
        assertTrue(cacheManager.getSearch(searchKey)?.first()?.isLiked == true)
        assertFalse(cacheManager.getSearch(searchKey)?.get(1)?.isLiked == true)
        assertTrue(cacheManager.getFeed("home_feed")?.first()?.isLiked == true)
        assertTrue(cacheManager.getPlaylistSongs(5)?.first()?.isLiked == true)
        assertTrue(cacheManager.getAlbum("discovery")?.first()?.isLiked == true)
        assertTrue(cacheManager.getArtist("daft punk")?.first()?.isLiked == true)
        assertTrue(cacheManager.getRecommendations("recs")?.first()?.isLiked == true)
        assertTrue(cacheManager.getRecentlyPlayed("recent")?.first()?.isLiked == true)
    }

    @Test
    fun testTargetedInvalidation_invalidatesOnlyTargetedEntries() {
        val cacheManager = ApiCacheManager()

        val p1 = Playlist(id = 1, name = "Workout")
        val p2 = Playlist(id = 2, name = "Study")
        cacheManager.putUserPlaylists(userId = 1, listOf(p1, p2))
        cacheManager.putUserPlaylists(userId = 2, listOf(p2))

        cacheManager.putPlaylistSongs(1, listOf(sampleSong(1)))
        cacheManager.putPlaylistSongs(2, listOf(sampleSong(2)))

        // Invalidate only Playlist 1's songs
        cacheManager.invalidatePlaylistSongs(1)
        assertNull(cacheManager.getPlaylistSongs(1))
        assertNotNull(cacheManager.getPlaylistSongs(2))

        // Invalidate only User 1's playlists
        cacheManager.invalidateUserPlaylists(userId = 1)
        assertNull(cacheManager.getUserPlaylists(1))
        assertNotNull(cacheManager.getUserPlaylists(2))
    }

    @Test
    fun testClearAll_clearsAllPoolsOnLogout() {
        val cacheManager = ApiCacheManager()

        cacheManager.putSearch("search:k", listOf(sampleSong(1)))
        cacheManager.putFeed("home_feed", listOf(sampleSong(2)))
        cacheManager.putPlaylistSongs(1, listOf(sampleSong(3)))
        cacheManager.putUserPlaylists(1, listOf(Playlist(1, "List")))
        cacheManager.putAlbum("album", listOf(sampleSong(4)))
        cacheManager.putArtist("artist", listOf(sampleSong(5)))
        cacheManager.putRecommendations("rec", listOf(sampleSong(6)))
        cacheManager.putRecentlyPlayed("recent", listOf(sampleSong(7)))

        cacheManager.clearAll()

        assertNull(cacheManager.getSearch("search:k"))
        assertNull(cacheManager.getFeed("home_feed"))
        assertNull(cacheManager.getPlaylistSongs(1))
        assertNull(cacheManager.getUserPlaylists(1))
        assertNull(cacheManager.getAlbum("album"))
        assertNull(cacheManager.getArtist("artist"))
        assertNull(cacheManager.getRecommendations("rec"))
        assertNull(cacheManager.getRecentlyPlayed("recent"))
    }

    // ==========================================
    // 5. HTTP CACHE INTERCEPTORS
    // ==========================================

    @Test
    fun testNetworkCacheInterceptor_injectsSensibleMaxAgeForReadOnlyEndpoints() {
        val interceptor = NetworkCacheInterceptor()

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("https://music.example.com/songs")
                .get()
                .build()

            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }

            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val response = interceptor.intercept(mockChain)
        val cacheControl = response.header("Cache-Control")
        assertNotNull("Response must have Cache-Control header", cacheControl)
        assertTrue("Catalog songs endpoint must have 300s max-age", cacheControl!!.contains("max-age=300"))
    }

    @Test
    fun testNetworkCacheInterceptor_neverCachesMutationsOrStreams() {
        val interceptor = NetworkCacheInterceptor()

        val postChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder()
                .url("https://music.example.com/playlists/create")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }

            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val response = interceptor.intercept(postChain)
        assertNull("POST mutations must never have synthetic Cache-Control headers injected", response.header("Cache-Control"))
    }
}
