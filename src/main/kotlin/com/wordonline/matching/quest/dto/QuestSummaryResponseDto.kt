package com.wordonline.matching.quest.dto

import com.wordonline.matching.quest.domain.QuestState

/**
 * One element of `GET /api/users/mine/quests`.
 *
 * [conditionType] is the raw `quests.condition_type` string, so a client can pick a presentation
 * per type and fall back to a plain progress bar for a type it does not know yet.
 */
data class QuestSummaryResponseDto(
    val questId: Long,
    val conditionType: String,
    val conditionTargetId: Long?,
    val state: QuestState,
    val progress: Int,
    val requireValue: Int,
    val rewards: List<QuestSummaryRewardDto>,
)

/** One reward of a listed quest. [rewardId] is `quest_rewards.target_id`, 0 when it is null. */
data class QuestSummaryRewardDto(
    val rewardType: String,
    val rewardId: Long,
    val amount: Int,
)
