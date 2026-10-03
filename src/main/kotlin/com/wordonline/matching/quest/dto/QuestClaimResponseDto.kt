package com.wordonline.matching.quest.dto

/** Response of `POST /api/users/mine/quests/{questId}/claim`: one entry per reward granted by that call. */
data class QuestClaimResponseDto(
    val rewards: List<QuestSummaryRewardDto>,
)
