package com.wordonline.matching.quest.condition

import com.wordonline.matching.quest.entity.Quest

/**
 * Measures progress toward one kind of quest.
 *
 * To add a condition, write one `@Component` that implements this interface with a new [type] and
 * insert quests whose `condition_type` is that value. [com.wordonline.matching.quest.service.QuestRegistry]
 * picks the component up; no schema change and no edit to another class is needed.
 */
interface QuestCondition {

    /** The `quests.condition_type` value this condition handles. Unique across all conditions. */
    val type: String

    /**
     * Current progress of [userId] toward [quest]. The quest counts as met when the result is at
     * least `quest.requireValue`. `quest.conditionTargetId` is available for a condition that
     * measures one specific thing. Must not write to the database: the read endpoints call it too.
     */
    suspend fun progress(userId: Long, quest: Quest): Int
}
