package com.wordonline.matching.quest.reward

import com.wordonline.matching.appearance.repository.AppearanceRepository
import com.wordonline.matching.chest.entity.ChestReward
import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.quest.entity.QuestReward
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions

class AppearanceAndChestRewardGrantorTest {

    // ----- APPEARANCE -----

    @Test
    @DisplayName("외형_보상은_user_appearances_에_그_외형을_넣는다")
    fun appearanceGrant_InsertsOwnership() = runTest {
        val appearanceRepository = mock<AppearanceRepository> {
            onBlocking { findKeyById(6L) } doReturn "grass"
            onBlocking { insertOwnership(any(), any()) } doReturn 1L
        }

        AppearanceRewardGrantor(appearanceRepository).grant(7L, QuestReward(1L, 11L, "APPEARANCE", 6L, 1))

        verifyBlocking(appearanceRepository) { insertOwnership(7L, 6L) }
    }

    @Test
    @DisplayName("이미_가진_외형을_다시_주면_0_행이어도_실패하지_않는다")
    fun appearanceGrant_OwnedAppearanceIsNoOp() = runTest {
        val appearanceRepository = mock<AppearanceRepository> {
            onBlocking { findKeyById(6L) } doReturn "grass"
            onBlocking { insertOwnership(any(), any()) } doReturn 0L
        }

        AppearanceRewardGrantor(appearanceRepository).grant(7L, ChestReward(1L, 1L, "APPEARANCE", 6L, 1))

        verifyBlocking(appearanceRepository) { insertOwnership(7L, 6L) }
    }

    @Test
    @DisplayName("default_외형은_행을_만들지_않는다")
    fun appearanceGrant_DefaultWritesNothing() = runTest {
        val appearanceRepository = mock<AppearanceRepository> {
            onBlocking { findKeyById(1L) } doReturn AppearanceRepository.DEFAULT_KEY
        }

        AppearanceRewardGrantor(appearanceRepository).grant(7L, QuestReward(1L, 11L, "APPEARANCE", 1L, 1))

        verifyBlocking(appearanceRepository, never()) { insertOwnership(any(), any()) }
    }

    @Test
    @DisplayName("target_id_가_없는_외형_보상은_아무것도_읽거나_쓰기_전에_실패한다")
    fun appearanceGrant_NullTargetFails() = runTest {
        val appearanceRepository = mock<AppearanceRepository>()

        val error = assertThrows<RewardNotGrantableException> {
            AppearanceRewardGrantor(appearanceRepository).grant(7L, ChestReward(3L, 2L, "APPEARANCE", null, 1))
        }

        assertThat(error.message).contains("chest_rewards row 3 (chest 2)").contains("target_id is null")
        verifyNoInteractions(appearanceRepository)
    }

    @Test
    @DisplayName("없는_외형_id_는_실패한다")
    fun appearanceGrant_UnknownAppearanceFails() = runTest {
        val appearanceRepository = mock<AppearanceRepository> { onBlocking { findKeyById(99L) } doReturn null }

        val error = assertThrows<RewardNotGrantableException> {
            AppearanceRewardGrantor(appearanceRepository).grant(7L, QuestReward(1L, 11L, "APPEARANCE", 99L, 1))
        }

        assertThat(error.message).contains("appearance 99 does not exist")
        verifyBlocking(appearanceRepository, never()) { insertOwnership(any(), any()) }
    }

    @Test
    @DisplayName("외형_보상의_rewardKey_는_외형_key_이고_target_이_없으면_null_이다")
    fun appearanceDescribe() = runTest {
        val appearanceRepository = mock<AppearanceRepository> { onBlocking { findKeyById(6L) } doReturn "grass" }
        val grantor = AppearanceRewardGrantor(appearanceRepository)

        assertThat(grantor.describe(6L)).isEqualTo("grass")
        assertThat(grantor.describe(null)).isNull()
    }

    // ----- CHEST -----

    @Test
    @DisplayName("상자_보상은_amount_개의_열지_않은_user_chests_행을_넣는다")
    fun chestGrant_InsertsAmountRows() = runTest {
        val chestRepository = mock<ChestRepository> {
            onBlocking { findKeyById(1L) } doReturn "forest_chest"
            onBlocking { insertUnopened(any(), any(), any()) } doReturn 3L
        }

        ChestRewardGrantor(chestRepository).grant(7L, QuestReward(9L, 11L, "CHEST", 1L, 3))

        verifyBlocking(chestRepository) { insertUnopened(7L, 1L, 3) }
    }

    @Test
    @DisplayName("target_id_가_없는_상자_보상은_아무것도_쓰기_전에_실패한다")
    fun chestGrant_NullTargetFails() = runTest {
        val chestRepository = mock<ChestRepository>()

        val error = assertThrows<RewardNotGrantableException> {
            ChestRewardGrantor(chestRepository).grant(7L, QuestReward(9L, 11L, "CHEST", null, 1))
        }

        assertThat(error.message).contains("quest_rewards row 9 (quest 11)").contains("target_id is null")
        verifyNoInteractions(chestRepository)
    }

    @Test
    @DisplayName("없는_상자_id_는_실패하고_아무것도_넣지_않는다")
    fun chestGrant_UnknownChestFails() = runTest {
        val chestRepository = mock<ChestRepository> { onBlocking { findKeyById(99L) } doReturn null }

        assertThrows<RewardNotGrantableException> {
            ChestRewardGrantor(chestRepository).grant(7L, QuestReward(9L, 11L, "CHEST", 99L, 1))
        }
        verifyBlocking(chestRepository, never()) { insertUnopened(any(), any(), any()) }
    }

    @Test
    @DisplayName("넣은_행_수가_amount_와_다르면_실패한다")
    fun chestGrant_RowCountMismatchFails() = runTest {
        val chestRepository = mock<ChestRepository> {
            onBlocking { findKeyById(1L) } doReturn "forest_chest"
            onBlocking { insertUnopened(any(), any(), any()) } doReturn 1L
        }

        assertThrows<RewardNotGrantableException> {
            ChestRewardGrantor(chestRepository).grant(7L, QuestReward(9L, 11L, "CHEST", 1L, 2))
        }
    }

    @Test
    @DisplayName("상자_보상의_rewardKey_는_상자_key_이고_마법과_장식은_null_이다")
    fun describeKeys() = runTest {
        val chestRepository = mock<ChestRepository> { onBlocking { findKeyById(1L) } doReturn "forest_chest" }

        assertThat(ChestRewardGrantor(chestRepository).describe(1L)).isEqualTo("forest_chest")
        assertThat(MagicRewardGrantor(mock()).describe(3L)).isNull()
        assertThat(DecorationRewardGrantor(mock()).describe(4L)).isNull()
    }
}
