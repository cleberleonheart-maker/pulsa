package com.pulsa.player.util

import com.pulsa.player.model.Song
import kotlin.random.Random

object DjEngine {

    enum class Source { ALL, FAVORITES }

    enum class Intensity(val jitter: Double, val artistSpread: Int) {
        CALM(0.25, 2),
        BALANCED(0.55, 4),
        WILD(0.95, 8)
    }

    data class Learn(
        val disliked: Set<Long> = emptySet(),
        val skipCount: Map<Long, Int> = emptyMap(),
        val plays: Map<Long, Int> = emptyMap(),
        val liked: Map<Long, Int> = emptyMap()
    )

    fun build(
        pool: List<Song>,
        favoriteIds: Set<Long>,
        source: Source,
        intensity: Intensity,
        learn: Learn = Learn()
    ): List<Song> {
        val base = when (source) {
            Source.FAVORITES -> pool.filter { it.id in favoriteIds }
            Source.ALL -> pool
        }
        if (base.isEmpty()) return emptyList()
        if (base.size == 1) return base

        val backfill = base.filter { it.id !in learn.disliked }
        val candidatesPool = if (backfill.isNotEmpty()) backfill else base
        val remaining = candidatesPool.shuffled().toMutableList()
        val used = linkedSetOf<Song>()
        val recentArtists = ArrayDeque<String>()
        var lastDurBin = -1

        fun learnScore(s: Song): Double {
            var v = if (s.id in favoriteIds) 1.6 else 0.0
            val skips = learn.skipCount[s.id] ?: 0
            if (skips > 0) v -= 0.8 * skips.coerceAtMost(4)
            if (s.id in learn.disliked) v -= 4.0
            v += 0.3 * (learn.plays[s.id] ?: 0).coerceAtMost(3)
            v += 1.1 * (learn.liked[s.id] ?: 0).coerceAtMost(3)
            return v
        }

        fun durBin(s: Song): Int = when {
            s.durationMs <= 0L -> 0
            s.durationMs < 150_000L -> 0
            s.durationMs < 260_000L -> 1
            else -> 2
        }

        fun artistRecent(artist: String): Boolean = recentArtists.contains(artist)

        while (used.size < candidatesPool.size) {
            if (remaining.isEmpty()) {
                remaining += candidatesPool.filter { s -> used.none { it.id == s.id } }.shuffled()
                if (remaining.isEmpty()) break
            }

            var best: Song? = null
            var bestScore = Double.NEGATIVE_INFINITY
            val candidates = remaining.take(12)
            for (s in candidates) {
                var score = Random.nextDouble(0.0, 1.0) * (intensity.jitter + 0.35) + learnScore(s)
                if (!artistRecent(s.artist)) score += 2.2
                if (s.album != if (used.isEmpty()) "" else used.last().album) score += 1.0
                if (s.id in favoriteIds) score += 0.9
                if (durBin(s) != lastDurBin) score += 0.7
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            val pick = best ?: candidates.first()
            remaining.remove(pick)
            used.add(pick)
            recentArtists.addLast(pick.artist)
            while (recentArtists.size > intensity.artistSpread) recentArtists.removeFirst()
            lastDurBin = durBin(pick)
        }
        return used.toList()
    }
}