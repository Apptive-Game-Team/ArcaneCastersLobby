package com.wordonline.matching.chest.dto

import java.time.Instant

/**
 * One reward inside a chest. [rewardType] is the `chest_rewards.reward_type` value, [rewardId] its
 * `target_id` (0 when null), and [rewardKey] the string key the client shows it by (the appearance
 * key for `APPEARANCE`; null for `MAGIC` and `DECORATION`).
 */
data class ChestRewardDto(
    val rewardType: String,
    val rewardId: Long,
    val rewardKey: String?,
    val amount: Int,
)

/**
 * One element of `GET /api/users/mine/chests`. [id] is the `user_chests.id` the open endpoint takes,
 * [chestId] and [chestKey] the kind of chest, and [rewards] a preview of what opening it gives.
 */
data class UnopenedChestResponseDto(
    val id: Long,
    val chestId: Long,
    val chestKey: String,
    val acquiredAt: Instant,
    val rewards: List<ChestRewardDto>,
)

/** Response of `POST /api/users/mine/chests/{id}/open`: one entry per reward granted. */
data class ChestOpenResponseDto(
    val rewards: List<ChestRewardDto>,
)
