package com.example.audio

import com.example.model.Song
import kotlin.random.Random

/**
 * Spotify-accurate artist-spread shuffle algorithm.
 *
 * Uniform randomness (such as a bare Fisher-Yates shuffle) produces clusters
 * and clumps of tracks from the same artist, which users perceive as broken or
 * biased. Spotify uses an artist-spread redistribution pass to prevent
 * back-to-back songs by the same artist while maintaining unpredictable randomness.
 */
object ShuffleUtils {

    /**
     * Shuffles a list of songs using Fisher-Yates followed by an artist-spread redistribution pass.
     */
    fun artistSpreadShuffle(tracks: List<Song>, random: Random = Random.Default): List<Song> {
        if (tracks.size <= 2) return tracks.shuffled(random)

        // Pass 1: Standard Fisher-Yates shuffle
        val list = tracks.toMutableList()
        for (i in list.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = list[i]
            list[i] = list[j]
            list[j] = temp
        }

        // Pass 2: Artist redistribution pass
        // Walk array; whenever track[i].artist === track[i-1].artist, swap track[i] forward
        // to the nearest index whose neighbours do not share that artist.
        // Cap search to avoid infinite loops on single-artist collections.
        val n = list.size
        for (i in 1 until n) {
            val currentArtist = list[i].artist.trim().lowercase()
            val prevArtist = list[i - 1].artist.trim().lowercase()

            if (currentArtist.isNotEmpty() && currentArtist == prevArtist) {
                var swapIndex = -1
                // Look ahead up to 15 positions for a non-clumping swap candidate
                val maxLookahead = minOf(n, i + 15)
                for (k in (i + 1) until maxLookahead) {
                    val candidateArtist = list[k].artist.trim().lowercase()
                    if (candidateArtist != currentArtist) {
                        val nextAtK = if (k + 1 < n) list[k + 1].artist.trim().lowercase() else null
                        if (currentArtist != nextAtK) {
                            swapIndex = k
                            break
                        } else if (swapIndex == -1) {
                            swapIndex = k
                        }
                    }
                }
                if (swapIndex != -1) {
                    val temp = list[i]
                    list[i] = list[swapIndex]
                    list[swapIndex] = temp
                }
            }
        }

        return list
    }
}
