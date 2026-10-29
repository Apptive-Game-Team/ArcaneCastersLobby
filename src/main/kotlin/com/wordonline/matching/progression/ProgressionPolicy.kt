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
    fun cost(level: Int): Int = when {
        level < 10 -> 100
        level < 30 -> 250
        level < MAX_LEVEL -> 330
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
        winner == userId -> 100
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
)
