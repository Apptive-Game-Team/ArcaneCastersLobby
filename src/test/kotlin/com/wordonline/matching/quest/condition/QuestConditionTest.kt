package com.wordonline.matching.quest.condition

import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.repository.QuestConditionRepository
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class QuestConditionTest {

    private val repository = mock<QuestConditionRepository>()
    private val userId = 1L

    private fun quest(type: String, targetId: Long?) = Quest(9L, 3, "DEFAULT", type, targetId)

    @Test
    @DisplayName("ADVENTURE_CLEAR 는_그_모험에서_끝낸_스테이지_수가_진행이다")
    fun adventureClear_CountsFinishedStagesOfTheAdventure() = runTest {
        whenever(repository.countFinishedStagesOfAdventure(userId, 7L)).thenReturn(2)

        assertThat(AdventureClearCondition(repository).progress(userId, quest("ADVENTURE_CLEAR", 7L))).isEqualTo(2)
    }

    @Test
    @DisplayName("ADVENTURE_CLEAR 에_condition_target_id 가_없으면_0_이_아니라_예외를_던진다")
    fun adventureClear_NullTargetThrows() = runTest {
        val error = assertThrows<MissingConditionTargetException> {
            AdventureClearCondition(repository).progress(userId, quest("ADVENTURE_CLEAR", null))
        }

        assertThat(error.message).contains("quest 9").contains("ADVENTURE_CLEAR").contains("condition_target_id")
        verifyNoInteractions(repository)
    }

    @Test
    @DisplayName("STAGE_CLEAR 는_대상이_없으면_끝낸_스테이지_전체_수_있으면_그_스테이지를_끝냈는지_1_또는_0_이다")
    fun stageClear_WithAndWithoutTarget() = runTest {
        whenever(repository.countFinishedStages(userId)).thenReturn(4)
        whenever(repository.isStageFinished(userId, 50L)).thenReturn(true)
        whenever(repository.isStageFinished(userId, 51L)).thenReturn(false)
        val condition = StageClearCondition(repository)

        assertThat(condition.progress(userId, quest("STAGE_CLEAR", null))).isEqualTo(4)
        assertThat(condition.progress(userId, quest("STAGE_CLEAR", 50L))).isEqualTo(1)
        assertThat(condition.progress(userId, quest("STAGE_CLEAR", 51L))).isEqualTo(0)
    }

    @Test
    @DisplayName("TOTAL_WIN 은_users_total_wins 가_진행이다")
    fun totalWin_ReadsTotalWins() = runTest {
        whenever(repository.findTotalWins(userId)).thenReturn(6)

        assertThat(TotalWinCondition(repository).progress(userId, quest("TOTAL_WIN", null))).isEqualTo(6)
        verify(repository, never()).countFinishedStages(userId)
    }
}
