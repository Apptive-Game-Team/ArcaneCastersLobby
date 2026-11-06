package com.wordonline.matching.progression

import org.springframework.boot.context.properties.ConfigurationProperties
import kotlin.random.Random

@ConfigurationProperties("progression")
data class ProgressionProperties(
    val cardProbability: Double = 0.8,
    val currencyMin: Int = 10,
    val currencyMax: Int = 30,
) {
    init {
        require(cardProbability in 0.0..1.0)
        require(currencyMin > 0 && currencyMax >= currencyMin && currencyMax < Int.MAX_VALUE)
    }
}

object ProgressionPolicy {
    const val MAX_LEVEL = 50
    const val WIN_XP = 100
    const val FIRST_WIN_XP = 25

    fun cost(level: Int): Int = when {
        level in 1..9 -> 60 + (level - 1) * 10
        // 39개 간격으로 150~430을 보간해 가장 가까운 정수로 반올림한다.
        level in 10 until MAX_LEVEL -> 150 + ((level - 10) * 280 + 19) / 39
        else -> 0
    }
    fun threshold(level: Int): Long = (1 until level).sumOf { cost(it).toLong() }
    fun level(xp: Long): Int = (1..MAX_LEVEL).last { xp >= threshold(it) }
    fun slots(level: Int): Int = when {
        level == MAX_LEVEL -> 7
        level % 5 == 0 -> 6
        else -> 3
    }
    fun xp(userId: Long, winner: Long?, loser: Long?, status: String, reason: String?): Int = when {
        status == "ABANDONED" -> 0
        winner == userId -> WIN_XP
        loser == userId && reason in setOf("SURRENDER", "DISCONNECT", "FORFEIT") -> 0
        loser == userId -> 25
        status == "DRAW" -> 50
        else -> 0
    }
    fun drawCard(eligible: Boolean, properties: ProgressionProperties, random: Random): Boolean =
        eligible && random.nextDouble() < properties.cardProbability
}

data class ProgressionResponse(
    val faction: String?, val level: Int, val xp: Long,
    val levelXp: Long, val nextLevelXp: Int, val currency: Long,
    val gainedXp: Long = 0, val grantedChests: Int = 0,
    val matchXp: ProgressionMatchXp? = null,
)

data class ProgressionMatchXp(val baseXp: Int, val firstWinBonusXp: Int)
