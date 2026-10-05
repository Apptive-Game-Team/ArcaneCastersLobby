package com.wordonline.matching.chest.service

import com.wordonline.matching.chest.dto.ChestRewardDto
import com.wordonline.matching.chest.dto.UnopenedChestResponseDto
import com.wordonline.matching.chest.entity.ChestReward
import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.chest.repository.UnopenedChestRow
import com.wordonline.matching.quest.reward.RewardNotGrantableException
import com.wordonline.matching.quest.service.FixedProgressCondition
import com.wordonline.matching.quest.service.QuestRegistry
import com.wordonline.matching.quest.service.RecordingGrantor
import com.wordonline.matching.quest.service.RecordingTransactionalOperator
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.time.Instant

class ChestServiceTest {

    private val userId = 1L

    private lateinit var chestRepository: ChestRepository
    private lateinit var transactionalOperator: RecordingTransactionalOperator
    private lateinit var appearanceGrantor: RecordingGrantor
    private lateinit var magicGrantor: RecordingGrantor
    private lateinit var chestGrantor: RecordingGrantor
    private lateinit var chestService: ChestService

    @BeforeEach
    fun setUp() {
        transactionalOperator = RecordingTransactionalOperator()
        appearanceGrantor = RecordingGrantor("APPEARANCE").apply { keyPrefix = "appearance-" }
        magicGrantor = RecordingGrantor("MAGIC")
        chestGrantor = RecordingGrantor("CHEST")
    }

    @Test
    @DisplayName("열지_않은_상자_목록은_상자마다_내용물_미리보기를_한_번의_조회로_싣는다")
    fun findMyChests_LoadsContentsOnce() = runTest {
        val acquiredAt = Instant.parse("2026-10-02T12:00:00Z")
        chestRepository = mock {
            onBlocking { findUnopened(userId) } doReturn listOf(
                UnopenedChestRow(10L, 1L, "forest_chest", acquiredAt),
                UnopenedChestRow(11L, 1L, "forest_chest", acquiredAt.plusSeconds(60)),
                UnopenedChestRow(12L, 2L, "fortress_chest", acquiredAt.plusSeconds(120)),
            )
            onBlocking { findRewardsOfChests(listOf(1L, 1L, 2L)) } doReturn listOf(
                ChestReward(100L, 1L, "APPEARANCE", 6L, 1),
                ChestReward(101L, 2L, "APPEARANCE", 5L, 1),
                ChestReward(102L, 2L, "MAGIC", 44L, 2),
            )
        }
        setUpServiceWith(chestRepository)

        val chests = chestService.findMyChests(userId)

        assertThat(chests).containsExactly(
            UnopenedChestResponseDto(10L, 1L, "forest_chest", acquiredAt, listOf(ChestRewardDto("APPEARANCE", 6L, "appearance-6", 1))),
            UnopenedChestResponseDto(
                11L, 1L, "forest_chest", acquiredAt.plusSeconds(60),
                listOf(ChestRewardDto("APPEARANCE", 6L, "appearance-6", 1)),
            ),
            UnopenedChestResponseDto(
                12L, 2L, "fortress_chest", acquiredAt.plusSeconds(120),
                listOf(ChestRewardDto("APPEARANCE", 5L, "appearance-5", 1), ChestRewardDto("MAGIC", 44L, null, 2)),
            ),
        )
        assertThat(appearanceGrantor.granted).isEmpty()
        assertThat(transactionalOperator.commits + transactionalOperator.rollbacks).isZero()
    }

    @Test
    @DisplayName("상자를_열면_claim_한_뒤_내용물을_모두_지급하고_돌려준다")
    fun openChest_ClaimsThenGrantsEveryReward() = runTest {
        chestRepository = mock {
            onBlocking { claimUnopened(10L, userId) } doReturn 2L
            onBlocking { findRewardsOfChests(listOf(2L)) } doReturn listOf(
                ChestReward(101L, 2L, "APPEARANCE", 5L, 1),
                ChestReward(102L, 2L, "MAGIC", 44L, 2),
            )
        }
        setUpServiceWith(chestRepository)

        val result = chestService.openChest(userId, 10L)

        assertThat(result).isEqualTo(
            ChestOpenResult.Opened(listOf(ChestRewardDto("APPEARANCE", 5L, "appearance-5", 1), ChestRewardDto("MAGIC", 44L, null, 2))),
        )
        assertThat(appearanceGrantor.granted.map { (it as ChestReward).id }).containsExactly(101L)
        assertThat(magicGrantor.granted.map { (it as ChestReward).id }).containsExactly(102L)
        assertThat(transactionalOperator.commits).isEqualTo(1)
    }

    @Test
    @DisplayName("claim_이_0_행이고_행이_없으면_NotFound_이미_열렸으면_AlreadyOpened_이다")
    fun openChest_ZeroRowsDecidesByFollowUpRead() = runTest {
        chestRepository = mock {
            onBlocking { claimUnopened(any(), any()) } doReturn null
            onBlocking { findOpened(10L, userId) } doReturn null
            onBlocking { findOpened(11L, userId) } doReturn true
        }
        setUpServiceWith(chestRepository)

        assertThat(chestService.openChest(userId, 10L)).isEqualTo(ChestOpenResult.NotFound)
        assertThat(chestService.openChest(userId, 11L)).isEqualTo(ChestOpenResult.AlreadyOpened)
        assertThat(appearanceGrantor.granted + magicGrantor.granted).isEmpty()
        verifyBlocking(chestRepository, never()) { findRewardsOfChests(any()) }
    }

    @Test
    @DisplayName("내용물_하나의_지급이_실패하면_트랜잭션이_rollback_되고_예외가_올라간다")
    fun openChest_GrantorFailureRollsBack() = runTest {
        magicGrantor.failOnNullTarget = true
        chestRepository = mock {
            onBlocking { claimUnopened(10L, userId) } doReturn 2L
            onBlocking { findRewardsOfChests(listOf(2L)) } doReturn listOf(
                ChestReward(101L, 2L, "APPEARANCE", 5L, 1),
                ChestReward(102L, 2L, "MAGIC", null, 1),
            )
        }
        setUpServiceWith(chestRepository)

        assertThrows<RewardNotGrantableException> { chestService.openChest(userId, 10L) }
        assertThat(transactionalOperator.rollbacks).isEqualTo(1)
        assertThat(transactionalOperator.commits).isZero()
    }

    @Test
    @DisplayName("상자_안의_CHEST_보상은_지급하지_않고_rollback_한다")
    fun openChest_ChestInsideChestRollsBack() = runTest {
        chestRepository = mock {
            onBlocking { claimUnopened(10L, userId) } doReturn 2L
            onBlocking { findRewardsOfChests(listOf(2L)) } doReturn listOf(ChestReward(103L, 2L, "CHEST", 1L, 1))
        }
        setUpServiceWith(chestRepository)

        assertThrows<RewardNotGrantableException> { chestService.openChest(userId, 10L) }
        assertThat(chestGrantor.granted).isEmpty()
        assertThat(transactionalOperator.rollbacks).isEqualTo(1)
    }

    private fun setUpServiceWith(repository: ChestRepository) {
        chestService = ChestService(
            repository,
            QuestRegistry(listOf(FixedProgressCondition("STAGE_CLEAR")), listOf(appearanceGrantor, magicGrantor, chestGrantor)),
            transactionalOperator,
        )
    }
}
