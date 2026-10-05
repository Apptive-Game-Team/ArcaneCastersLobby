package com.wordonline.matching.quest.reward

/**
 * Grants one kind of reward.
 *
 * To add a reward kind, write one `@Component` that implements this interface with a new [type]
 * and insert `quest_rewards` (or `chest_rewards`) rows whose `reward_type` is that value.
 * [com.wordonline.matching.quest.service.QuestRegistry] picks the component up; no schema change
 * and no edit to another class is needed.
 *
 * [grant] runs inside the transaction that claims the quest or opens the chest. It must throw on a
 * reward it cannot grant (for example [RewardNotGrantableException] for a missing `target_id`)
 * rather than skip it: the exception rolls the claim back, so the quest stays claimable, the chest
 * stays unopened, and nothing is half granted.
 */
interface RewardGrantor {

    /** The `reward_type` value this grantor handles. Unique across all grantors. */
    val type: String

    suspend fun grant(userId: Long, reward: Reward)

    /**
     * A stable string key the client uses to show the reward, sent as `rewardKey`: for example the
     * appearance key for an appearance id. Null when this type has no such key, or when [targetId]
     * is null or names nothing. Must not write to the database: the read endpoints call it too.
     */
    suspend fun describe(targetId: Long?): String?
}

/** Thrown by a [RewardGrantor] for a reward row it cannot grant. */
class RewardNotGrantableException(reward: Reward, reason: String) :
    IllegalStateException(
        "${reward.describeRow()} with reward_type ${reward.rewardType} cannot be granted: $reason",
    )

/** The `target_id` of [reward], or [RewardNotGrantableException] when it is null. */
internal fun requireTargetId(reward: Reward): Long =
    reward.targetId ?: throw RewardNotGrantableException(reward, "target_id is null")
