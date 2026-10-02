package com.wordonline.matching.quest.condition

import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.repository.QuestConditionRepository
import org.springframework.stereotype.Component

/**
 * `ADVENTURE_CLEAR`: `condition_target_id` is an `adventures.id`, and progress is the number of
 * that adventure's stages the user has finished, counted by the same rule as [StageClearCondition].
 * The data sets `require_value` to the adventure's stage count, so the quest completes when every
 * stage is finished.
 *
 * A quest of this type without `condition_target_id` is a configuration error and throws
 * [MissingConditionTargetException] instead of reporting 0.
 */
@Component
class AdventureClearCondition(
    private val questConditionRepository: QuestConditionRepository,
) : QuestCondition {

    override val type: String = TYPE

    override suspend fun progress(userId: Long, quest: Quest): Int {
        val adventureId = quest.conditionTargetId
            ?: throw MissingConditionTargetException(quest, "an adventure id")
        return questConditionRepository.countFinishedStagesOfAdventure(userId, adventureId)
    }

    companion object {
        const val TYPE = "ADVENTURE_CLEAR"
    }
}

/** A quest whose condition needs `condition_target_id` but has none. */
class MissingConditionTargetException(quest: Quest, expected: String) :
    IllegalStateException(
        "quest ${quest.id} has condition_type ${quest.conditionType} with no condition_target_id; " +
            "it must be $expected",
    )
