package com.wordonline.matching.chest.entity

import com.wordonline.matching.quest.reward.Reward

/**
 * One row of `chest_rewards`: one thing a chest gives when it is opened. A chest can carry several.
 * The `reward_type` vocabulary is the one `quest_rewards` uses, except that `CHEST` is never
 * allowed here (no chest inside a chest).
 */
data class ChestReward(
    val id: Long,
    val chestId: Long,
    override val rewardType: String,
    override val targetId: Long?,
    override val amount: Int,
) : Reward {
    override fun describeRow(): String = "chest_rewards row $id (chest $chestId)"
}
