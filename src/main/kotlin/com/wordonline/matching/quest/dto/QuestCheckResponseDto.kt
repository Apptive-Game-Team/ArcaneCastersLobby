package com.wordonline.matching.quest.dto

/** Response of `POST /api/users/mine/quests/check`: one entry per reward granted by that call. */
data class QuestCheckResponseDto(
    val rewards: List<QuestRewardDto>,
)
