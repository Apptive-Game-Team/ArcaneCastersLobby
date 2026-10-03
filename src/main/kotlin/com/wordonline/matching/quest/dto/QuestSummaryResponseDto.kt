package com.wordonline.matching.quest.dto

import com.wordonline.matching.quest.domain.QuestState

/**
 * One element of `GET /api/users/mine/quests`.
 *
 * [conditionType] is the raw `quests.condition_type` string, so a client can pick a presentation
 * per type and fall back to a plain progress bar for a type it does not know yet.
 *
 * [claimMode] is the raw `quests.claim_mode` string ("AUTO" or "MANUAL"). [claimable] is true when
 * the condition is met (progress at least [requireValue]) and the rewards were not granted yet, so
 * `POST /api/users/mine/quests/{questId}/claim` would grant them now. An "AUTO" quest can read
 * claimable until the next `POST /api/users/mine/quests/check` grants it.
 */
data class QuestSummaryResponseDto(
    val questId: Long,
    val conditionType: String,
    val conditionTargetId: Long?,
    val state: QuestState,
    val progress: Int,
    val requireValue: Int,
    val claimMode: String,
    val claimable: Boolean,
    val rewards: List<QuestSummaryRewardDto>,
)

/**
 * One reward of a listed quest, or one reward granted by the claim endpoint. [rewardId] is `quest_rewards.target_id`, 0 when it is null, and
 * [rewardKey] the string key the client shows it by, as in [QuestRewardDto].
 */
data class QuestSummaryRewardDto(
    val rewardType: String,
    val rewardId: Long,
    val rewardKey: String?,
    val amount: Int,
)
