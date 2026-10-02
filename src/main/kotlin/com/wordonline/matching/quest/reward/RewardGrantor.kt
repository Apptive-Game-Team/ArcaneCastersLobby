package com.wordonline.matching.quest.reward

import com.wordonline.matching.quest.entity.QuestReward

/**
 * Grants one kind of quest reward.
 *
 * To add a reward kind, write one `@Component` that implements this interface with a new [type]
 * and insert `quest_rewards` rows whose `reward_type` is that value.
 * [com.wordonline.matching.quest.service.QuestRegistry] picks the component up; no schema change
 * and no edit to another class is needed.
 *
 * [grant] runs inside the transaction that claims the quest. It must throw on a reward it cannot
 * grant (for example [RewardNotGrantableException] for a missing `target_id`) rather than skip it:
 * the exception rolls the claim back, so the quest stays claimable and nothing is half granted.
 */
interface RewardGrantor {

    /** The `quest_rewards.reward_type` value this grantor handles. Unique across all grantors. */
    val type: String

    suspend fun grant(userId: Long, reward: QuestReward)
}

/** Thrown by a [RewardGrantor] for a `quest_rewards` row it cannot grant. */
class RewardNotGrantableException(reward: QuestReward, reason: String) :
    IllegalStateException(
        "quest_rewards row ${reward.id} (quest ${reward.questId}, reward_type ${reward.rewardType}) " +
            "cannot be granted: $reason",
    )

/** The `target_id` of [reward], or [RewardNotGrantableException] when it is null. */
internal fun requireTargetId(reward: QuestReward): Long =
    reward.targetId ?: throw RewardNotGrantableException(reward, "target_id is null")
