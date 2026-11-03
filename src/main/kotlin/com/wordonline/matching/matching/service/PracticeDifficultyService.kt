package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.PracticeDifficulty
import com.wordonline.matching.matching.dto.PracticeDifficultyOverview
import com.wordonline.matching.matching.dto.PracticeDifficultyTier
import com.wordonline.matching.matching.repository.BotPersonaRepository
import com.wordonline.matching.matching.repository.PracticeHistoryRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.stereotype.Service
import kotlin.math.abs

@Service
class PracticeDifficultyService(
    private val bots: BotPersonaRepository,
    private val learnedBots: LearnedBotAvailability,
    private val history: PracticeHistoryRepository,
) {
    suspend fun overview(userId: Long): PracticeDifficultyOverview {
        val available = eligible(PracticeDifficulty.entries.flatMap { it.botIds }).toSet()
        val records = history.recent(userId).associateBy { it.id }
        val tiers = PracticeDifficulty.entries.map { tier ->
            val record = records[tier.name]
            PracticeDifficultyTier(tier.name, record?.wins ?: 0, record?.losses ?: 0, record?.draws ?: 0,
                tier.botIds.any { it in available })
        }
        return PracticeDifficultyOverview(tiers, recommend(tiers))
    }

    suspend fun select(tier: PracticeDifficulty): Long? = eligible(tier.botIds).randomOrNull()

    private suspend fun eligible(ids: List<Long>): List<Long> {
        val candidates = ids - learnedBots.unavailableBotIds().toSet()
        if (candidates.isEmpty()) return emptyList()
        return bots.findMatchableIds(candidates).asFlow().toList()
    }

    internal fun recommend(tiers: List<PracticeDifficultyTier>): String? {
        val placed = tiers.indices.filter { tiers[it].let { t -> t.wins + t.losses + t.draws >= 5 } }
        // 무승부는 반 승으로 계산해 무승부 다섯 번을 모두 패배로 판단하지 않는다.
        fun points(i: Int) = 2 * tiers[i].wins + tiers[i].draws
        val balanced = placed.lastOrNull { points(it) in 4..6 }
        val highest = placed.lastOrNull()
        val target = when {
            balanced != null -> balanced
            highest == null -> tiers.indexOfLast { it.wins + it.losses + it.draws > 0 }.coerceAtLeast(0)
            points(highest) > 6 -> highest + 1
            else -> highest - 1
        }
        return tiers.indices.filter { tiers[it].available }.minByOrNull { abs(it - target) }?.let { tiers[it].id }
    }
}
