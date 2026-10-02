package com.wordonline.matching.quest.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * One row of `quests`.
 *
 * The legacy columns `progress_checker` and `reward_giver` are deliberately not mapped: they stay
 * in the table only until a later migration drops them, and this server neither reads nor writes
 * them. This entity is read-only; nothing here saves a quest.
 */
@Table("quests")
data class Quest(
    @Id val id: Long,
    val requireValue: Int,
    val accessType: String,
    val conditionType: String,
    val conditionTargetId: Long? = null,
) {
    companion object {
        /** `access_type` of a retired quest: it gets no `user_quests` row and is never claimed. */
        const val DEPRECATED_ACCESS_TYPE = "DEPRECATED"
    }
}
