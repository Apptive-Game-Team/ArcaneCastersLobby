package com.wordonline.matching.chest.service

import com.wordonline.matching.chest.dto.ChestRewardDto
import com.wordonline.matching.chest.dto.UnopenedChestResponseDto
import com.wordonline.matching.chest.entity.ChestReward
import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.quest.reward.ChestRewardGrantor
import com.wordonline.matching.quest.reward.RewardNotGrantableException
import com.wordonline.matching.quest.service.QuestRegistry
import com.wordonline.matching.quest.service.keyOf
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** Outcome of opening one chest. */
sealed interface ChestOpenResult {
    /** The chest was opened by this call and every reward in [rewards] was granted. */
    data class Opened(val rewards: List<ChestRewardDto>) : ChestOpenResult

    /** No `user_chests` row with that id belongs to this user. */
    data object NotFound : ChestOpenResult

    /** The chest was opened before, by an earlier or a concurrent call. */
    data object AlreadyOpened : ChestOpenResult
}

/**
 * The user's treasure chests. A chest arrives unopened (a `CHEST` reward of a quest) and is opened
 * once, granting every `chest_rewards` row of its kind through the same
 * [com.wordonline.matching.quest.reward.RewardGrantor]s quests use.
 */
@Service
class ChestService(
    private val chestRepository: ChestRepository,
    private val questRegistry: QuestRegistry,
    private val transactionalOperator: TransactionalOperator,
) {
    /**
     * Read-only. This user's unopened chests, oldest first, each with its contents as a preview.
     * The contents of all chests come from one query.
     */
    suspend fun findMyChests(userId: Long): List<UnopenedChestResponseDto> {
        val chests = chestRepository.findUnopened(userId)
        val rewardsByChestId = chestRepository.findRewardsOfChests(chests.map { it.chestId })
            .groupBy(ChestReward::chestId)
        val rewardKeys = questRegistry.describeRewards(rewardsByChestId.values.flatten())

        return chests.map { chest ->
            UnopenedChestResponseDto(
                id = chest.userChestId,
                chestId = chest.chestId,
                chestKey = chest.chestKey,
                acquiredAt = chest.acquiredAt,
                rewards = rewardsByChestId[chest.chestId].orEmpty().map { reward ->
                    ChestRewardDto(reward.rewardType, reward.targetId ?: 0L, rewardKeys.keyOf(reward), reward.amount)
                },
            )
        }
    }

    /**
     * Opens one chest: claim before grant, in one transaction.
     *
     * The conditional `UPDATE ... WHERE opened_at IS NULL` ([ChestRepository.claimUnopened]) marks
     * the chest opened and takes its row lock. When it changes no row, a follow-up read tells a
     * missing or foreign chest ([ChestOpenResult.NotFound]) from an opened one
     * ([ChestOpenResult.AlreadyOpened]). When it changes the row, every `chest_rewards` row of the
     * chest is granted; any grantor that throws rolls back the claim and everything granted before
     * it, so the chest stays unopened and the error reaches the caller. Two concurrent opens of the
     * same chest grant once: the second waits on the row lock and then finds it opened.
     */
    suspend fun openChest(userId: Long, userChestId: Long): ChestOpenResult =
        transactionalOperator.executeAndAwait {
            val chestId = chestRepository.claimUnopened(userChestId, userId)
            if (chestId == null) {
                when (chestRepository.findOpened(userChestId, userId)) {
                    null -> ChestOpenResult.NotFound
                    true -> ChestOpenResult.AlreadyOpened
                    // The UPDATE waits out any concurrent opener, so an unopened row here means
                    // something else is wrong; failing beats answering 404 for a chest that exists.
                    false -> throw IllegalStateException(
                        "user_chests $userChestId of user $userId is unopened but could not be claimed",
                    )
                }
            } else {
                val rewards = chestRepository.findRewardsOfChests(listOf(chestId)).map { reward ->
                    // The startup check rejects this; a row added after startup must not mint chests.
                    if (reward.rewardType == ChestRewardGrantor.TYPE) {
                        throw RewardNotGrantableException(reward, "a chest cannot contain a chest")
                    }
                    val grantor = questRegistry.grantor(reward.rewardType)
                    grantor.grant(userId, reward)
                    ChestRewardDto(reward.rewardType, reward.targetId ?: 0L, grantor.describe(reward.targetId), reward.amount)
                }
                ChestOpenResult.Opened(rewards)
            }
        }
}
