package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCacheCapTest {

    @Test
    fun testFormatCapMb_megabytesAndGigabytes() {
        assertEquals("128 MB", AudioCache.formatCapMb(128))
        assertEquals("256 MB", AudioCache.formatCapMb(256))
        assertEquals("512 MB", AudioCache.formatCapMb(512))
        assertEquals("1 GB", AudioCache.formatCapMb(1024))
        assertEquals("2 GB", AudioCache.formatCapMb(2048))
        assertEquals("4 GB", AudioCache.formatCapMb(4096))
    }

    @Test
    fun testEstimateSongsForCap_proportionalCalculations() {
        // Average song ~5 MB
        assertEquals(25, AudioCache.estimateSongsForCap(128))
        assertEquals(51, AudioCache.estimateSongsForCap(256))
        assertEquals(102, AudioCache.estimateSongsForCap(512))
        assertEquals(204, AudioCache.estimateSongsForCap(1024))
        assertEquals(409, AudioCache.estimateSongsForCap(2048))
        assertEquals(819, AudioCache.estimateSongsForCap(4096))
    }

    @Test
    fun testPresetCaps_containDefaultAndSensibleOptions() {
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(AudioCache.DEFAULT_CACHE_CAP_MB))
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(128))
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(256))
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(512))
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(1024))
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(2048))
        assertTrue(AudioCache.PRESET_CAPS_MB.contains(4096))
    }

    @Test
    fun testFormatBytes_humanReadable() {
        assertEquals("512 KB", AudioCache.formatBytes(512L * 1024L))
        assertEquals("45.2 MB", AudioCache.formatBytes((45.2 * 1024.0 * 1024.0).toLong()))
        assertEquals("1.0 GB", AudioCache.formatBytes(1024L * 1024L * 1024L))
        assertEquals("2.5 GB", AudioCache.formatBytes((2.5 * 1024.0 * 1024.0 * 1024.0).toLong()))
    }

    @Test
    fun testDynamicLruCacheEvictor_initialAndSetMaxBytes() {
        val initialBytes = 512L * 1024L * 1024L
        val evictor = DynamicLruCacheEvictor(initialBytes)
        assertEquals(initialBytes, evictor.maxBytes)

        val newBytes = 1024L * 1024L * 1024L
        evictor.setMaxBytes(newBytes)
        assertEquals(newBytes, evictor.maxBytes)

        assertTrue(evictor.requiresCacheSpanTouches())
    }
}
