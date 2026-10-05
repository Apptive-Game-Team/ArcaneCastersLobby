package com.wordonline.matching.quest.condition

import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.repository.QuestConditionRepository
import org.springframework.stereotype.Component

/** `TOTAL_WIN`: progress is `users.total_wins`. `condition_target_id` has no meaning here and is ignored. */
@Component
class TotalWinCondition(
    private val questConditionRepository: QuestConditionRepository,
) : QuestCondition {

    override val type: String = TYPE

    override suspend fun progress(userId: Long, quest: Quest): Int =
        questConditionRepository.findTotalWins(userId)

    companion object {
        const val TYPE = "TOTAL_WIN"
    }
}
