package com.wordonline.matching.quest.entity

import com.wordonline.matching.quest.domain.QuestState
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/** One row of `user_quests`, unique on (`user_id`, `quest_id`). */
@Table("user_quests")
data class UserQuest(
    @Id val id: Long,
    val userId: Long,
    val questId: Long,
    val state: QuestState,
)
