package com.wordonline.matching.quest.reward

import com.wordonline.matching.chest.repository.ChestRepository
import org.springframework.stereotype.Component

/**
 * `CHEST`: gives the user `amount` unopened chests whose `chests.id` is `target_id`, one
 * `user_chests` row each. The user opens them later through `POST /api/users/mine/chests/{id}/open`.
 *
 * A `target_id` that is null or names no chest, or an `amount` that is not positive, throws
 * [RewardNotGrantableException]. Only `quest_rewards` may use this type: `chest_rewards` never holds
 * `CHEST`, and the startup check and the open path both reject it.
 */
@Component
class ChestRewardGrantor(
    private val chestRepository: ChestRepository,
) : RewardGrantor {

    override val type: String = TYPE

    override suspend fun grant(userId: Long, reward: Reward) {
        val chestId = requireTargetId(reward)
        if (reward.amount <= 0) {
            throw RewardNotGrantableException(reward, "amount ${reward.amount} is not positive")
        }
        if (chestRepository.findKeyById(chestId) == null) {
            throw RewardNotGrantableException(reward, "chest $chestId does not exist")
        }
        val inserted = chestRepository.insertUnopened(userId, chestId, reward.amount)
        if (inserted != reward.amount.toLong()) {
            throw RewardNotGrantableException(reward, "user_chests inserted $inserted rows, expected ${reward.amount}")
        }
    }

    /** The chest key, for example `forest_chest`. */
    override suspend fun describe(targetId: Long?): String? =
        targetId?.let { chestRepository.findKeyById(it) }

    companion object {
        const val TYPE = "CHEST"
    }
}
