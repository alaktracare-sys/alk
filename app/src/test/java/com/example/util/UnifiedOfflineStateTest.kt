package com.example.util

import android.content.ContextWrapper
import com.example.data.remote.cache.OfflineCacheInterceptor
import com.example.model.Song
import com.example.ui.components.isSongPlayableInUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UnifiedOfflineStateTest {

    private val context = ContextWrapper(null)

    @Before
    fun setUp() {
        NetworkMonitor.setServerRecoveryProbe(null)
        NetworkMonitor.updateActiveServerUrl("https://api.alaktracare.in")
        NetworkMonitor.markServerReachable()
    }

    @After
    fun tearDown() {
        NetworkMonitor.setServerRecoveryProbe(null)
        NetworkMonitor.markServerReachable()
    }

    @Test
    fun `marking server unreachable unifies isOnline, observeNetwork, and UI grey-out`() = runBlocking {
        assertTrue(NetworkMonitor.hasNetworkTransport(context))
        assertTrue(NetworkMonitor.isOnline(context))
        assertTrue(NetworkMonitor.observeNetwork(context).first())

        val uncachedSong = Song(
            id = 101,
            title = "Uncached",
            artist = "Artist",
            audioUrl = "https://api.alaktracare.in/songs/101/stream",
            coverUrl = ""
        )
        val cachedSong = Song(
            id = 102,
            title = "Cached",
            artist = "Artist",
            audioUrl = "https://api.alaktracare.in/songs/102/stream",
            coverUrl = "",
            isCached = true
        )

        val isOnlineInitially = NetworkMonitor.isOnline(context)
        assertTrue(isSongPlayableInUi(uncachedSong, isOnlineInitially, setOf(102), cacheIndexWarmed = true))
        assertTrue(isSongPlayableInUi(cachedSong, isOnlineInitially, setOf(102), cacheIndexWarmed = true))

        // Server/tunnel becomes unreachable while phone network transport stays active
        NetworkMonitor.markServerUnreachable("https://api.alaktracare.in", cooldownMs = 30_000L)

        assertTrue(NetworkMonitor.hasNetworkTransport(context))
        assertFalse(NetworkMonitor.isOnline(context))
        assertFalse(NetworkMonitor.observeNetwork(context).first())
        assertTrue(NetworkMonitor.shouldSkipNetworkRequests(context, forceNetwork = false))

        // Explicit forceNetwork allows manual refresh/probe while transport exists
        assertFalse(NetworkMonitor.shouldSkipNetworkRequests(context, forceNetwork = true))

        // UI now greys out uncached tracks even though transport is on
        val unifiedAfterFailure = NetworkMonitor.isOnline(context)
        assertFalse(isSongPlayableInUi(uncachedSong, unifiedAfterFailure, setOf(102), cacheIndexWarmed = true))
        assertTrue(isSongPlayableInUi(cachedSong, unifiedAfterFailure, setOf(102), cacheIndexWarmed = true))

        // Server recovers -> unified online state immediately flips back to true
        NetworkMonitor.markServerReachable()
        assertTrue(NetworkMonitor.isOnline(context))
        assertTrue(NetworkMonitor.observeNetwork(context).first())
        assertTrue(isSongPlayableInUi(uncachedSong, NetworkMonitor.isOnline(context), setOf(102), cacheIndexWarmed = true))
    }

    @Test
    fun `recovery probe prevents flicker while server stays down and restores once healthy`() = runBlocking {
        val probeShouldSucceed = AtomicBoolean(false)
        val probeCalls = AtomicInteger(0)

        NetworkMonitor.setServerRecoveryProbe {
            probeCalls.incrementAndGet()
            delay(50)
            probeShouldSucceed.get()
        }

        NetworkMonitor.isOnline(context)
        NetworkMonitor.markServerUnreachable("https://api.alaktracare.in", cooldownMs = 1_000L)
        assertFalse(NetworkMonitor.isOnline(context))

        // Wait until first recovery probe completes with failure (server still down)
        delay(1_250)
        assertTrue(probeCalls.get() >= 1)
        assertFalse(
            "Unified state must stay offline without flickering when probe fails",
            NetworkMonitor.isOnline(context)
        )

        // Allow next recovery probe to succeed (server came back online)
        probeShouldSucceed.set(true)
        delay(1_500L)
        NetworkMonitor.markServerReachable()
        assertTrue(NetworkMonitor.isOnline(context))
    }

    @Test
    fun `cancelled IOException does not trigger offline state`() = runBlocking {
        val probeCalls = AtomicInteger(0)
        NetworkMonitor.setServerRecoveryProbe {
            probeCalls.incrementAndGet()
            false
        }
        assertTrue(NetworkMonitor.isOnline(context))

        NetworkMonitor.reportSuspectedServerFailure(
            context = context,
            serverUrl = "https://api.alaktracare.in",
            error = java.io.IOException("Canceled")
        )
        delay(100)
        assertTrue(NetworkMonitor.isOnline(context))
        assertTrue(probeCalls.get() == 0)
    }

    @Test
    fun `transient timeout verifies with health probe before flickering offline`() = runBlocking {
        val serverHealthy = AtomicBoolean(true)
        val probeCalls = AtomicInteger(0)
        NetworkMonitor.setServerRecoveryProbe {
            probeCalls.incrementAndGet()
            delay(30)
            serverHealthy.get()
        }
        assertTrue(NetworkMonitor.isOnline(context))

        // Healthy server + slow single track -> probe returns true -> stays online without flicker
        NetworkMonitor.reportSuspectedServerFailure(
            context = context,
            serverUrl = "https://api.alaktracare.in",
            error = java.net.SocketTimeoutException("timeout")
        )
        assertTrue(NetworkMonitor.isOnline(context))
        delay(120)
        assertTrue(probeCalls.get() == 1)
        assertTrue("Healthy server must stay online after verified timeout", NetworkMonitor.isOnline(context))
    }

    @Test
    fun `offline interceptor forces FORCE_CACHE even for noCache GET requests when server unreachable except health`() {
        NetworkMonitor.isOnline(context)
        NetworkMonitor.markServerUnreachable("https://api.alaktracare.in", cooldownMs = 30_000L)

        val interceptor = OfflineCacheInterceptor(context)

        var capturedSongsRequest: Request? = null
        val songsChain = fakeChain(
            Request.Builder()
                .url("https://api.alaktracare.in/songs?limit=20")
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()
        ) { req ->
            capturedSongsRequest = req
        }
        interceptor.intercept(songsChain)
        assertTrue(
            "noCache GET requests must fall back to FORCE_CACHE while server is unreachable",
            capturedSongsRequest?.cacheControl?.onlyIfCached == true
        )

        var capturedHealthRequest: Request? = null
        val healthChain = fakeChain(
            Request.Builder()
                .url("https://api.alaktracare.in/health")
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()
        ) { req ->
            capturedHealthRequest = req
        }
        interceptor.intercept(healthChain)
        assertFalse(
            "/health probe must never be forced to cache while server is unreachable",
            capturedHealthRequest?.cacheControl?.onlyIfCached == true
        )
    }

    private fun fakeChain(initial: Request, onProceed: (Request) -> Unit): Interceptor.Chain {
        return object : Interceptor.Chain {
            override fun request(): Request = initial
            override fun proceed(request: Request): Response {
                onProceed(request)
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            override fun connection(): Connection? = null
            override fun call(): okhttp3.Call = throw UnsupportedOperationException()
            override fun connectTimeoutMillis(): Int = 10_000
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun readTimeoutMillis(): Int = 10_000
            override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 10_000
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        }
    }
}
