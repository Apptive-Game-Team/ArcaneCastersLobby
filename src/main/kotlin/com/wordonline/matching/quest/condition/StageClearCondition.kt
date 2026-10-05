package com.wordonline.matching.quest.condition

import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.repository.QuestConditionRepository
import org.springframework.stereotype.Component

/**
 * `STAGE_CLEAR`: with no `condition_target_id`, progress is the number of stages the user has
 * finished. With a `condition_target_id`, it is a stage id and progress is 1 when that stage is
 * finished, 0 otherwise, so such a quest should have `require_value` 1.
 */
@Component
class StageClearCondition(
    private val questConditionRepository: QuestConditionRepository,
) : QuestCondition {

    override val type: String = TYPE

    override suspend fun progress(userId: Long, quest: Quest): Int {
        val stageId = quest.conditionTargetId
            ?: return questConditionRepository.countFinishedStages(userId)
        return if (questConditionRepository.isStageFinished(userId, stageId)) 1 else 0
    }

    companion object {
        const val TYPE = "STAGE_CLEAR"
    }
}
