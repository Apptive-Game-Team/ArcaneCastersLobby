package com.wordonline.matching.publicstats

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Settings of the unauthenticated website endpoints under `/api/public`.
 *
 * @property cacheTtl how long a computed result is served from memory before the database is read again
 * @property rankingSize number of players in the ranking
 * @property rankingMinGames finished PVP games (WIN or DRAW) a player needs to appear in the ranking
 * @property rankingCandidateFactor candidates read from the database per ranking slot, so that
 *   players whose account lookup fails, and guest accounts, can be skipped without leaving the
 *   ranking short. The pool is bounded (size times this factor) rather than paged, so one refresh
 *   costs at most that many account lookups
 * @property magicWindow how far back the magic statistics look
 * @property magicMinSample WIN-outcome deck entries a magic needs before its win rate is reported
 */
@ConfigurationProperties(prefix = "public-stats")
data class PublicStatsProperties(
    val cacheTtl: Duration = Duration.ofMinutes(5),
    val rankingSize: Int = 50,
    val rankingMinGames: Int = 5,
    val rankingCandidateFactor: Int = 3,
    val magicWindow: Duration = Duration.ofDays(30),
    val magicMinSample: Int = 20,
) {
    init {
        require(rankingSize > 0) { "public-stats.ranking-size must be positive" }
        require(rankingCandidateFactor >= 1) { "public-stats.ranking-candidate-factor must be at least 1" }
        require(!cacheTtl.isNegative) { "public-stats.cache-ttl must not be negative" }
    }
}
