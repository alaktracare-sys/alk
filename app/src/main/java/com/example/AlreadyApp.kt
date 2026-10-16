package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.audio.AudioController
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.launch

class AlreadyApp : Application(), ImageLoaderFactory {
    lateinit var repository: MusicRepository
        private set
    lateinit var audioController: AudioController
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MusicRepository(this)
        audioController = AudioController.getInstance(this)
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                repository.syncHistoryIfDue()
            } catch (_: Exception) {}
        }
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .cache(okhttp3.Cache(cacheDir.resolve("okhttp_image_cache"), 50L * 1024 * 1024))
            .addInterceptor(com.example.data.remote.cache.OfflineCacheInterceptor(this))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk cache for artwork
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
