package com.wordonline.matching.publicstats.dto

import java.time.Instant

/** One ranked player. Carries neither the account email nor the user id on purpose. */
data class PublicRankingPlayerDto(
    val rank: Int,
    val name: String,
    val mmr: Int,
    val wins: Long,
    val losses: Long,
    val draws: Long,
)

data class PublicRankingResponse(
    val updatedAt: Instant,
    val players: List<PublicRankingPlayerDto>,
)

data class PublicMagicStatisticDto(
    val magicId: Long,
    val name: String,
    val prefab: String?,
    val decks: Long,
    val pickRate: Double,
    /** `null` while the magic has fewer WIN-outcome deck entries than the configured minimum sample. */
    val winRate: Double?,
    val casts: Long,
)

data class PublicMagicStatisticsResponse(
    val updatedAt: Instant,
    val from: Instant,
    val to: Instant,
    val totalGames: Long,
    val magics: List<PublicMagicStatisticDto>,
)
