package com.wordonline.matching.quest.service

import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.entity.QuestReward
import com.wordonline.matching.quest.repository.QuestRepository
import com.wordonline.matching.quest.repository.QuestRewardRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Flux

class QuestRegistryTest {

    private val registry = QuestRegistry(
        listOf(FixedProgressCondition("STAGE_CLEAR"), FixedProgressCondition("TOTAL_WIN")),
        listOf(RecordingGrantor("MAGIC"), RecordingGrantor("DECORATION")),
    )

    @Test
    @DisplayName("같은_condition_type_을_두_condition_이_선언하면_생성에_실패한다")
    fun duplicateConditionTypeFails() {
        assertThatThrownBy {
            QuestRegistry(
                listOf(FixedProgressCondition("STAGE_CLEAR"), FixedProgressCondition("STAGE_CLEAR")),
                listOf(RecordingGrantor("MAGIC")),
            )
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("'STAGE_CLEAR'")
    }

    @Test
    @DisplayName("같은_reward_type_을_두_grantor_가_선언하면_생성에_실패한다")
    fun duplicateRewardTypeFails() {
        assertThatThrownBy {
            QuestRegistry(
                listOf(FixedProgressCondition("STAGE_CLEAR")),
                listOf(RecordingGrantor("MAGIC"), RecordingGrantor("MAGIC")),
            )
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("'MAGIC'")
    }

    @Test
    @DisplayName("등록된_type_은_찾고_없는_type_은_UnknownQuestTypeException_을_던진다")
    fun lookup() {
        assertThat(registry.condition("TOTAL_WIN").type).isEqualTo("TOTAL_WIN")
        assertThat(registry.grantor("DECORATION").type).isEqualTo("DECORATION")
        assertThatThrownBy { registry.grantor("GOLD") }.isInstanceOf(UnknownQuestTypeException::class.java)
        assertThat(registry.findCondition("NOPE")).isNull()
    }

    private fun startupCheck(quests: List<Quest>, rewards: List<QuestReward>): QuestRegistryStartupCheck {
        val questRepository = mock<QuestRepository> { on { findAllActive() } doReturn Flux.fromIterable(quests) }
        val questRewardRepository = mock<QuestRewardRepository> {
            on { findAllOfActiveQuests() } doReturn Flux.fromIterable(rewards)
        }
        return QuestRegistryStartupCheck(questRepository, questRewardRepository, registry)
    }

    @Test
    @DisplayName("시작_검사는_등록되지_않은_condition_type_이_있으면_실패한다")
    fun startupCheckFailsOnUnknownConditionType() {
        val check = startupCheck(
            listOf(Quest(1L, 1, "DEFAULT", "STAGE_CLEAR"), Quest(2L, 1, "DEFAULT", "LOGIN_STREAK")),
            listOf(QuestReward(10L, 1L, "MAGIC", 3L, 1)),
        )

        assertThatThrownBy { runBlocking { check.verify() } }
            .isInstanceOf(UnknownQuestTypeException::class.java)
            .hasMessageContaining("quest 2 has condition_type 'LOGIN_STREAK'")
    }

    @Test
    @DisplayName("시작_검사는_등록되지_않은_reward_type_이_있으면_실패한다")
    fun startupCheckFailsOnUnknownRewardType() {
        val check = startupCheck(
            listOf(Quest(1L, 1, "DEFAULT", "STAGE_CLEAR")),
            listOf(QuestReward(10L, 1L, "MAGIC", 3L, 1), QuestReward(11L, 1L, "GOLD", null, 100)),
        )

        assertThatThrownBy { runBlocking { check.verify() } }
            .isInstanceOf(UnknownQuestTypeException::class.java)
            .hasMessageContaining("quest_rewards 11 of quest 1 has reward_type 'GOLD'")
    }

    @Test
    @DisplayName("시작_검사는_모든_type_이_등록되어_있으면_통과한다")
    fun startupCheckPassesWhenAllRegistered() = runTest {
        startupCheck(
            listOf(Quest(1L, 1, "DEFAULT", "STAGE_CLEAR"), Quest(2L, 3, "DEFAULT", "TOTAL_WIN")),
            listOf(QuestReward(10L, 1L, "MAGIC", 3L, 1), QuestReward(11L, 2L, "DECORATION", 4L, 1)),
        ).verify()
    }
}
