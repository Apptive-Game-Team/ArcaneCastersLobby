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

    suspend fun sync(userId: Long, manualClaims: Boolean = false): ProgressionResponse = transaction.executeAndAwait {
        syncLocked(userId, repository.lock(userId), manualClaims)
    }

    suspend fun syncMatch(userId: Long, manualClaims: Boolean, sessionId: String): ProgressionResponse =
        transaction.executeAndAwait {
            val state = syncLocked(userId, repository.lock(userId), manualClaims)
            state.copy(matchXp = repository.matchXp(userId, sessionId))
        }

    suspend fun choose(userId: Long, faction: String, manualClaims: Boolean = false): ProgressionResponse? = transaction.executeAndAwait {
        val state = repository.lock(userId)
        if (state.faction != null && state.faction != faction) return@executeAndAwait null
        if (state.faction == null) {
            val magics = repository.fixedMagics(faction, 1)
            check(magics.size == 5) { "Missing starter magic catalog for $faction" }
            repository.choose(userId, faction)
            magics.forEach { repository.grantMagic(userId, it.id, 3) }
            repository.createStarterDeck(userId, faction)
        }
        syncLocked(userId, state.copy(faction = faction), manualClaims)
    }

    suspend fun pendingRewards(userId: Long, manualClaims: Boolean = false): PendingProgressionRewardsResponse = transaction.executeAndAwait {
        syncLocked(userId, repository.lock(userId), manualClaims)
        val state = repository.lock(userId)
        PendingProgressionRewardsResponse(manualClaims, state.baselineLevel, repository.claimedLevels(userId),
            repository.pendingRewardClaims(userId).map { claim ->
                PendingProgressionReward(claim.id, claim.source, claim.level, claim.createdAt,
                    repository.rewardItems(claim.id).map { it.reward })
            })
    }

    suspend fun claimReward(userId: Long, claimId: Long, manualClaims: Boolean = false): ProgressionClaimResponse? = transaction.executeAndAwait {
        repository.lock(userId)
        val claim = repository.lockRewardClaim(userId, claimId) ?: return@executeAndAwait null
        val result = claimLocked(userId, claim)
        if (!manualClaims) drainPendingLocked(userId)
        result
    }

    private suspend fun claimLocked(userId: Long, claim: ProgressionRepository.RewardClaim): ProgressionClaimResponse {
        val items = repository.rewardItems(claim.id)
        if (claim.claimed) return ProgressionClaimResponse(claim.id, true,
            items.map { it.reward.copy(amount = checkNotNull(it.grantedAmount)) })
        val owned = repository.ownsSource(userId, claim.source)
        val receipt = items.map { item ->
            val reward = item.reward
            val amount = when {
                owned -> 0
                reward.rewardType == "MAGIC" -> repository.grantMagicDelta(userId, reward.rewardId, reward.amount)
                reward.rewardType == "CHEST" -> if (repository.grantSnapshotChest(userId, claim.source, reward)) 1 else 0
                else -> error("Unsupported progression reward ${reward.rewardType}")
            }
            repository.saveReceipt(claim.id, item.position, amount)
            reward.copy(amount = amount)
        }
        repository.finishRewardClaim(claim.id)
        return ProgressionClaimResponse(claim.id, false, receipt)
    }

    private suspend fun drainPendingLocked(userId: Long): Int {
        var chests = 0
        for (pending in repository.pendingRewardClaims(userId)) {
            val claim = checkNotNull(repository.lockRewardClaim(userId, pending.id))
            chests += claimLocked(userId, claim).rewards.filter { it.rewardType == "CHEST" }.sumOf { it.amount }
        }
        return chests
    }

    private suspend fun syncLocked(userId: Long, state: ProgressionState, manualClaims: Boolean): ProgressionResponse {
        var gained = 0L
        var chests = 0
        if (state.faction != null) {
            repository.pendingMatches(userId).forEach { match ->
                val baseXp = ProgressionPolicy.xp(userId, match.winner, match.loser, match.status, match.reason)
                val date = match.rankedEndDate
                val bonus = if (baseXp == ProgressionPolicy.WIN_XP && date != null && !repository.hasRankedWinXp(userId, date)) {
                    ProgressionPolicy.FIRST_WIN_XP
                } else {
                    0
                }
                val xp = baseXp + bonus
                if (repository.recordXp(userId, match.id, xp)) gained += xp
            }
            val level = ProgressionPolicy.level(state.xp + gained)
            for (newLevel in maxOf(state.level, state.baselineLevel) + 1..level) {
                val magics = if (newLevel % 5 == 0) repository.fixedMagics(state.faction, newLevel) else emptyList()
                check(newLevel % 5 != 0 || magics.size == 1) { "Missing level $newLevel reward for ${state.faction}" }
                repository.enqueueReward(userId, "level:$newLevel", newLevel, magics, ProgressionPolicy.slots(newLevel))
            }
            repository.save(userId, state.xp + gained, level)
            val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
            repository.enqueueReward(userId, "daily:$date", null, emptyList(), 3)
            if (!manualClaims) {
                chests = drainPendingLocked(userId)
                repository.advanceBaseline(userId, level)
            }
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
