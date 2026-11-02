package com.wordonline.matching.progression

import com.wordonline.matching.chest.dto.ChestRewardDto
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

@Service
class ProgressionService(
    private val repository: ProgressionRepository,
    private val properties: ProgressionProperties,
    private val transaction: TransactionalOperator,
) {
    suspend fun rewards(userId: Long): ProgressionRewardsResponse? {
        val faction = repository.faction(userId) ?: return null
        val magics = repository.rewardCatalog(faction).groupBy { it.level }
        return ProgressionRewardsResponse(faction, (1..ProgressionPolicy.MAX_LEVEL).map { level ->
            ProgressionLevelReward(level, ProgressionPolicy.threshold(level),
                if (level == 1) 0 else 1, if (level == 1) 0 else ProgressionPolicy.slots(level),
                magics[level].orEmpty().map { ProgressionFixedReward(it.magicId, it.magicName, if (level == 1) 3 else 2) })
        })
    }

    suspend fun sync(userId: Long): ProgressionResponse = transaction.executeAndAwait {
        syncLocked(userId, repository.lock(userId))
    }

    suspend fun choose(userId: Long, faction: String): ProgressionResponse? = transaction.executeAndAwait {
        val state = repository.lock(userId)
        if (state.faction != null && state.faction != faction) return@executeAndAwait null
        if (state.faction == null) {
            val magics = repository.fixedMagics(faction, 1)
            check(magics.size == 5) { "Missing starter magic catalog for $faction" }
            repository.choose(userId, faction)
            magics.forEach { repository.grantMagic(userId, it.id, 3) }
            repository.createStarterDeck(userId, faction)
        }
        syncLocked(userId, state.copy(faction = faction))
    }

    private suspend fun syncLocked(userId: Long, state: ProgressionState): ProgressionResponse {
        var gained = 0L
        var chests = 0
        if (state.faction != null) {
            repository.pendingMatches(userId).forEach { match ->
                val xp = ProgressionPolicy.xp(userId, match.winner, match.loser, match.status, match.reason)
                if (repository.recordXp(userId, match.id, xp)) gained += xp
            }
            val level = ProgressionPolicy.level(state.xp + gained)
            for (newLevel in state.level + 1..level) {
                if (newLevel % 5 == 0) {
                    val magics = repository.fixedMagics(state.faction, newLevel)
                    check(magics.size == 1) { "Missing level $newLevel reward for ${state.faction}" }
                    magics.forEach { repository.grantMagic(userId, it.id, 2) }
                }
                if (repository.issueChest(userId, "level:$newLevel", ProgressionPolicy.slots(newLevel))) chests++
            }
            repository.save(userId, state.xp + gained, level)
            val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
            if (repository.issueChest(userId, "daily:$date", 3)) chests++
        }
        val xp = state.xp + gained
        val level = ProgressionPolicy.level(xp)
        return ProgressionResponse(state.faction, level, xp,
            if (level == 50) 0 else xp - ProgressionPolicy.threshold(level),
            ProgressionPolicy.cost(level), state.currency, gained, chests)
    }

    // 호출자는 player_progression 행을 먼저 잠그고 상자 소유권을 확정해야 한다.
    suspend fun openLocked(userId: Long, userChestId: Long, state: ProgressionState): List<ChestRewardDto> {
        checkNotNull(state.faction) { "Choose a faction before opening progression chests" }
        val slots = repository.chestSlots(userId, userChestId)
        check(slots in 1..7) { "Invalid progression chest slots: $slots" }
        val rewards = mutableListOf<ChestRewardDto>()
        repeat(slots) {
            val eligible = repository.eligibleMagics(userId, state.level)
            if (ProgressionPolicy.drawCard(eligible.isNotEmpty(), properties, Random.Default)) {
                val magic = eligible.random()
                repository.grantMagic(userId, magic.id, 1)
                rewards += ChestRewardDto("MAGIC", magic.id, magic.name, 1)
            } else {
                val amount = Random.nextInt(properties.currencyMin, properties.currencyMax + 1)
                repository.addCurrency(userId, amount)
                rewards += ChestRewardDto("CURRENCY", 0, "gold", amount)
            }
        }
        return rewards.groupBy { Triple(it.rewardType, it.rewardId, it.rewardKey) }.values.map { group ->
            group.first().copy(amount = group.sumOf { it.amount })
        }
    }
}
