package com.wordonline.matching.quest.dto

/**
 * One granted reward in the check response. [rewardType] is the `quest_rewards.reward_type` value
 * ("MAGIC", "DECORATION"), [rewardId] its `target_id` (0 when the reward has no target). The JSON
 * shape is unchanged from the Java record, so the Unity client reads it as before.
 */
data class QuestRewardDto(
    val rewardType: String,
    val rewardId: Long,
    val amount: Int,
    val questId: Long,
)
