package com.wordonline.matching.quest.reward

/**
 * One reward row a [RewardGrantor] can grant: a `quest_rewards` row when a quest is claimed, or a
 * `chest_rewards` row when a chest is opened. Both tables share the `reward_type` vocabulary, so the
 * same grantors serve both.
 */
interface Reward {

    /** Picks the [RewardGrantor] by its [RewardGrantor.type]. */
    val rewardType: String

    /** What the grantor grants (a magic id, an appearance id, a chest id), or null for a type that needs none. */
    val targetId: Long?

    /** Always positive; both tables check `amount > 0`. */
    val amount: Int

    /** Names the row for error messages, for example `quest_rewards row 5 (quest 9)`. */
    fun describeRow(): String
}
