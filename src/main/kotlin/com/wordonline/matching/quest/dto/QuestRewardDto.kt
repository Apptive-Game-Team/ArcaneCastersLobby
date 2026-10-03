package com.wordonline.matching.quest.dto

/**
 * One granted reward in the check response. [rewardType] is the `quest_rewards.reward_type` value
 * ("MAGIC", "DECORATION", "APPEARANCE", "CHEST"), [rewardId] its `target_id` (0 when the reward has
 * no target), and [rewardKey] the string key the client shows it by (the appearance key or the
 * chest key; null for a magic or a decoration). [rewardKey] was added to the Java record's shape;
 * every other field is unchanged, so the Unity client reads it as before.
 */
data class QuestRewardDto(
    val rewardType: String,
    val rewardId: Long,
    val rewardKey: String?,
    val amount: Int,
    val questId: Long,
)
