package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeWindowPaginationTest {

    @Test
    fun `computeTargetHomeLoadedCount loads only one screen length initially before songs scroll into view`() {
        val target = MusicRepository.computeTargetHomeLoadedCount(
            lastVisibleSongIndex = -1,
            oneScreenTrackCount = 11
        )
        assertEquals(11, target)
    }

    @Test
    fun `computeTargetHomeLoadedCount stays strictly one screen length ahead as user scrolls`() {
        // 3rd song row visible (index 2 => 3 songs shown) + 10 per screen = 13 total loaded
        val target = MusicRepository.computeTargetHomeLoadedCount(
            lastVisibleSongIndex = 2,
            oneScreenTrackCount = 10
        )
        assertEquals(13, target)

        // 15th song row visible (index 14 => 15 songs shown) + 10 per screen = 25 total loaded
        val scrolledTarget = MusicRepository.computeTargetHomeLoadedCount(
            lastVisibleSongIndex = 14,
            oneScreenTrackCount = 10
        )
        assertEquals(25, scrolledTarget)
    }
}
