package com.wordonline.matching.quest.reward

import com.wordonline.matching.deck.repository.UserCardRepository
import com.wordonline.matching.decoration.repository.UserDecorationRepository
import com.wordonline.matching.quest.entity.QuestReward
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import reactor.core.publisher.Mono

class MagicRewardGrantorTest {

    @Test
    @DisplayName("보상_지급시_유저ID와_마법ID와_지정된_장수를_기존_장수에_더한다")
    fun grant_AddsConfiguredCountForUserAndMagic() = runTest {
        val userCardRepository = mock<UserCardRepository> {
            on { addCount(any(), any(), any()) } doReturn Mono.just(1L)
        }

        MagicRewardGrantor(userCardRepository).grant(7L, QuestReward(1L, 9L, "MAGIC", 3L, 3))

        verify(userCardRepository).addCount(7L, 3L, 3)
        verify(userCardRepository, never()).save(anyOrNull())
    }

    @Test
    @DisplayName("target_id 가_없는_마법_보상은_아무것도_쓰기_전에_실패한다")
    fun grant_NullTargetFailsBeforeWriting() = runTest {
        val userCardRepository = mock<UserCardRepository>()

        val error = assertThrows<RewardNotGrantableException> {
            MagicRewardGrantor(userCardRepository).grant(7L, QuestReward(5L, 9L, "MAGIC", null, 1))
        }

        assertThat(error.message).contains("quest_rewards row 5").contains("target_id is null")
        verifyNoInteractions(userCardRepository)
    }

    @Test
    @DisplayName("user_magics 가_한_행도_바뀌지_않으면_실패한다")
    fun grant_FailsWhenNoRowChanged() = runTest {
        val userCardRepository = mock<UserCardRepository> {
            on { addCount(any(), any(), any()) } doReturn Mono.just(0L)
        }

        assertThrows<RewardNotGrantableException> {
            MagicRewardGrantor(userCardRepository).grant(7L, QuestReward(5L, 9L, "MAGIC", 3L, 1))
        }
    }

    @Test
    @DisplayName("target_id 가_없는_장식_보상은_아무것도_쓰기_전에_실패한다")
    fun decorationGrant_NullTargetFailsBeforeWriting() = runTest {
        val userDecorationRepository = mock<UserDecorationRepository>()

        assertThrows<RewardNotGrantableException> {
            DecorationRewardGrantor(userDecorationRepository).grant(7L, QuestReward(5L, 9L, "DECORATION", null, 1))
        }
        verifyNoInteractions(userDecorationRepository)
    }

    @Test
    @DisplayName("장식_보상은_이미_가진_장식을_다시_넣지_않는_쿼리로_지급한다")
    fun decorationGrant_InsertsIfAbsent() = runTest {
        val userDecorationRepository = mock<UserDecorationRepository> {
            on { insertIfAbsent(any(), any()) } doReturn Mono.just(1L)
        }

        DecorationRewardGrantor(userDecorationRepository).grant(7L, QuestReward(5L, 9L, "DECORATION", 4L, 1))

        verify(userDecorationRepository).insertIfAbsent(7L, 4L)
    }
}
