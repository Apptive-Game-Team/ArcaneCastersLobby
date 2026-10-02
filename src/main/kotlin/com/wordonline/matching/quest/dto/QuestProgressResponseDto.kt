package com.wordonline.matching.quest.dto

import com.wordonline.matching.quest.domain.QuestState

/** Response of the per-card and per-decoration quest progress endpoints. The JSON shape is unchanged from the Java version. */
data class QuestProgressResponseDto(
    val state: QuestState,
    val progress: Int,
    val requireValue: Int,
)
