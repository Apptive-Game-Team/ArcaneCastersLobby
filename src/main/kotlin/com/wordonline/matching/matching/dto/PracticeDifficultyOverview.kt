package com.wordonline.matching.matching.dto

data class PracticeDifficultyTier(
    val id: String,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val available: Boolean,
)

data class PracticeDifficultyOverview(
    val tiers: List<PracticeDifficultyTier>,
    val recommendedId: String?,
)
