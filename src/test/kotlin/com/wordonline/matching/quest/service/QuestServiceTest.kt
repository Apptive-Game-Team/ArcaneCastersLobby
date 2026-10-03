package com.wordonline.matching.quest.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.wordonline.matching.quest.condition.QuestCondition
import com.wordonline.matching.quest.domain.QuestState
import com.wordonline.matching.quest.dto.QuestProgressResponseDto
import com.wordonline.matching.quest.dto.QuestRewardDto
import com.wordonline.matching.quest.dto.QuestSummaryRewardDto
import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.entity.QuestReward
import com.wordonline.matching.quest.entity.UserQuest
import com.wordonline.matching.quest.repository.QuestRepository
import com.wordonline.matching.quest.repository.QuestRewardRepository
import com.wordonline.matching.quest.repository.UserQuestRepository
import com.wordonline.matching.quest.reward.Reward
import com.wordonline.matching.quest.reward.RewardGrantor
import com.wordonline.matching.quest.reward.RewardNotGrantableException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections

class QuestServiceTest {

    private val userId = 1L

    private lateinit var questRepository: QuestRepository
    private lateinit var questRewardRepository: QuestRewardRepository
    private lateinit var userQuestRepository: UserQuestRepository
    private lateinit var transactionalOperator: RecordingTransactionalOperator
    private lateinit var condition: FixedProgressCondition
    private lateinit var magicGrantor: RecordingGrantor
    private lateinit var decorationGrantor: RecordingGrantor
    private lateinit var questService: QuestService

    @BeforeEach
    fun setUp() {
        questRepository = mock()
        questRewardRepository = mock()
        userQuestRepository = mock {
            on { insertMissing(any()) } doReturn Mono.just(0L)
        }
        transactionalOperator = RecordingTransactionalOperator()
        condition = FixedProgressCondition("STAGE_CLEAR")
        magicGrantor = RecordingGrantor("MAGIC")
        decorationGrantor = RecordingGrantor("DECORATION")
        questService = QuestService(
            questRepository,
            questRewardRepository,
            userQuestRepository,
            QuestRegistry(listOf(condition), listOf(magicGrantor, decorationGrantor)),
            transactionalOperator,
        )
    }

    private fun quest(
        id: Long,
        requireValue: Int = 100,
        conditionType: String = "STAGE_CLEAR",
        targetId: Long? = null,
        claimMode: String = "AUTO",
    ) = Quest(id, requireValue, "DEFAULT", conditionType, targetId, claimMode)

    private fun reward(id: Long, questId: Long, type: String, targetId: Long?, amount: Int = 1) =
        QuestReward(id, questId, type, targetId, amount)

    // ----- progress by card and decoration (read-only) -----

    @Test
    @DisplayName("데코ID로_퀘스트_진행상황_조회_성공")
    fun findQuestProgressByDecoration_Success() = runTest {
        val quest = quest(1L)
        whenever(questRepository.findFirstByReward("DECORATION", 7L)).thenReturn(Mono.just(quest))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 1L))
            .thenReturn(Mono.just(UserQuest(1L, userId, 1L, QuestState.IN_PROGRESS)))
        condition.progressByQuest[1L] = 50

        val result = questService.findQuestProgressByDecoration(userId, 7L)

        assertThat(result).isEqualTo(QuestProgressResponseDto(QuestState.IN_PROGRESS, 50, 100))
    }

    @Test
    @DisplayName("카드ID로_퀘스트_진행상황_조회_성공")
    fun findQuestProgressByCard_Success() = runTest {
        val quest = quest(1L)
        whenever(questRepository.findFirstByReward("MAGIC", 3L)).thenReturn(Mono.just(quest))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 1L))
            .thenReturn(Mono.just(UserQuest(1L, userId, 1L, QuestState.IN_PROGRESS)))
        condition.progressByQuest[1L] = 50

        val result = questService.findQuestProgressByCard(userId, 3L)

        assertThat(result).isEqualTo(QuestProgressResponseDto(QuestState.IN_PROGRESS, 50, 100))
    }

    @Test
    @DisplayName("조건을_채웠지만_아직_check_하지_않은_퀘스트는_IN_PROGRESS_로_읽히고_아무것도_쓰지_않는다")
    fun findQuestProgress_MetButUnclaimed_IsReadOnly() = runTest {
        val quest = quest(2L, requireValue = 100)
        whenever(questRepository.findFirstByReward("MAGIC", 3L)).thenReturn(Mono.just(quest))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 2L)).thenReturn(Mono.empty())
        condition.progressByQuest[2L] = 100

        val result = questService.findQuestProgressByCard(userId, 3L)

        assertThat(result).isEqualTo(QuestProgressResponseDto(QuestState.IN_PROGRESS, 100, 100))
        verify(userQuestRepository, never()).insertMissing(any())
        verify(userQuestRepository, never()).claim(any(), any())
        verify(userQuestRepository, never()).save(any<UserQuest>())
        assertThat(transactionalOperator.commits + transactionalOperator.rollbacks).isZero()
    }

    @Test
    @DisplayName("진행이_없으면_PENDING_보상을_받았으면_COMPLETED_로_읽힌다")
    fun findQuestProgress_DerivesPendingAndCompleted() = runTest {
        whenever(questRepository.findFirstByReward("MAGIC", 3L)).thenReturn(Mono.just(quest(3L)))
        whenever(questRepository.findFirstByReward("MAGIC", 4L)).thenReturn(Mono.just(quest(4L)))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 3L)).thenReturn(Mono.empty())
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 4L))
            .thenReturn(Mono.just(UserQuest(4L, userId, 4L, QuestState.COMPLETED)))
        condition.progressByQuest[3L] = 0
        condition.progressByQuest[4L] = 100

        assertThat(questService.findQuestProgressByCard(userId, 3L)?.state).isEqualTo(QuestState.PENDING)
        assertThat(questService.findQuestProgressByCard(userId, 4L)?.state).isEqualTo(QuestState.COMPLETED)
    }

    @Test
    @DisplayName("보상으로_주는_퀘스트가_없으면_null_을_돌려준다")
    fun findQuestProgress_NoQuest_ReturnsNull() = runTest {
        whenever(questRepository.findFirstByReward("MAGIC", 99L)).thenReturn(Mono.empty())

        assertThat(questService.findQuestProgressByCard(userId, 99L)).isNull()
    }

    // ----- check (the only writer) -----

    @Test
    @DisplayName("check 는 먼저 빠진 user_quests 행을 만들고 조건을 채운 퀘스트의 보상을 돌려준다")
    fun checkQuestsWithRewards_Success() = runTest {
        val first = quest(1L, requireValue = 100)
        val second = quest(2L, requireValue = 50)
        val unmet = quest(3L, requireValue = 10)
        whenever(questRepository.findClaimable(userId)).thenReturn(Flux.just(first, second, unmet))
        condition.progressByQuest.putAll(mapOf(1L to 100, 2L to 60, 3L to 9))
        whenever(questRewardRepository.findAllByQuestIds(listOf(1L, 2L))).thenReturn(
            Flux.just(reward(10L, 1L, "MAGIC", 10L, amount = 3), reward(20L, 2L, "DECORATION", 20L)),
        )
        whenever(userQuestRepository.claim(any(), any())).thenReturn(Mono.just(1L))

        val rewards = questService.checkQuestsWithRewards(userId)

        assertThat(rewards).containsExactly(
            QuestRewardDto("MAGIC", 10L, null, 3, 1L),
            QuestRewardDto("DECORATION", 20L, null, 1, 2L),
        )
        verify(userQuestRepository).insertMissing(userId)
        verify(userQuestRepository, never()).claim(userId, 3L)
        assertThat(transactionalOperator.commits).isEqualTo(2)
    }

    @Test
    @DisplayName("보상이_여러_개인_퀘스트는_보상마다_QuestRewardDto_하나를_돌려준다")
    fun checkQuestsWithRewards_MultiRewardQuest() = runTest {
        whenever(questRepository.findClaimable(userId)).thenReturn(Flux.just(quest(1L, requireValue = 1)))
        condition.progressByQuest[1L] = 1
        whenever(questRewardRepository.findAllByQuestIds(listOf(1L))).thenReturn(
            Flux.just(
                reward(10L, 1L, "MAGIC", 5L, amount = 2),
                reward(11L, 1L, "MAGIC", 6L, amount = 1),
                reward(12L, 1L, "DECORATION", 7L),
            ),
        )
        whenever(userQuestRepository.claim(userId, 1L)).thenReturn(Mono.just(1L))

        val rewards = questService.checkQuestsWithRewards(userId)

        assertThat(rewards).containsExactly(
            QuestRewardDto("MAGIC", 5L, null, 2, 1L),
            QuestRewardDto("MAGIC", 6L, null, 1, 1L),
            QuestRewardDto("DECORATION", 7L, null, 1, 1L),
        )
        assertThat(magicGrantor.granted.map { (it as QuestReward).id }).containsExactly(10L, 11L)
        assertThat(decorationGrantor.granted.map { (it as QuestReward).id }).containsExactly(12L)
    }

    @Test
    @DisplayName("check_와_목록의_보상은_grantor_의_describe_값을_rewardKey_로_싣는다")
    fun rewardKeyComesFromGrantorDescribe() = runTest {
        decorationGrantor.keyPrefix = "decoration-"
        whenever(questRepository.findClaimable(userId)).thenReturn(Flux.just(quest(1L, requireValue = 1)))
        condition.progressByQuest[1L] = 1
        whenever(questRewardRepository.findAllByQuestIds(listOf(1L)))
            .thenReturn(Flux.just(reward(10L, 1L, "MAGIC", 5L), reward(11L, 1L, "DECORATION", 7L)))
        whenever(userQuestRepository.claim(userId, 1L)).thenReturn(Mono.just(1L))

        assertThat(questService.checkQuestsWithRewards(userId)).containsExactly(
            QuestRewardDto("MAGIC", 5L, null, 1, 1L),
            QuestRewardDto("DECORATION", 7L, "decoration-7", 1, 1L),
        )

        whenever(questRepository.findAllActive()).thenReturn(Flux.just(quest(1L, requireValue = 1)))
        whenever(questRewardRepository.findAllOfActiveQuests())
            .thenReturn(Flux.just(reward(10L, 1L, "MAGIC", 5L), reward(11L, 1L, "DECORATION", 7L)))
        whenever(userQuestRepository.findAllByUserId(userId)).thenReturn(Flux.empty())

        assertThat(questService.findMyQuests(userId).single().rewards).containsExactly(
            QuestSummaryRewardDto("MAGIC", 5L, null, 1),
            QuestSummaryRewardDto("DECORATION", 7L, "decoration-7", 1),
        )
    }

    @Test
    @DisplayName("같은_사용자의_check_두_번이_겹쳐도_보상은_한_번만_준다")
    fun checkQuestsWithRewards_ConcurrentChecksGrantOnce() = runTest {
        whenever(questRepository.findClaimable(userId)).thenReturn(Flux.just(quest(1L, requireValue = 1)))
        condition.progressByQuest[1L] = 1
        // Both callers saw the quest IN_PROGRESS before either claimed it. The database lets only the
        // first conditional UPDATE change the row; the second changes 0 rows.
        condition.yieldBeforeAnswer = true
        whenever(questRewardRepository.findAllByQuestIds(listOf(1L)))
            .thenReturn(Flux.just(reward(10L, 1L, "MAGIC", 5L)))
        whenever(userQuestRepository.claim(userId, 1L)).thenReturn(Mono.just(1L), Mono.just(0L))

        val results = listOf(
            async { questService.checkQuestsWithRewards(userId) },
            async { questService.checkQuestsWithRewards(userId) },
        ).awaitAll()

        assertThat(results.flatten()).containsExactly(QuestRewardDto("MAGIC", 5L, null, 1, 1L))
        assertThat(magicGrantor.granted).hasSize(1)
        assertThat(condition.calls).isEqualTo(2)
    }

    @Test
    @DisplayName("보상_지급이_실패하면_그_퀘스트의_claim_은_rollback_되고_다른_퀘스트는_계속_지급된다")
    fun checkQuestsWithRewards_GrantorFailureRollsBackClaim() = runTest {
        whenever(questRepository.findClaimable(userId))
            .thenReturn(Flux.just(quest(1L, requireValue = 1), quest(2L, requireValue = 1)))
        condition.progressByQuest.putAll(mapOf(1L to 1, 2L to 1))
        whenever(questRewardRepository.findAllByQuestIds(listOf(1L, 2L))).thenReturn(
            Flux.just(
                reward(10L, 1L, "MAGIC", 5L),
                reward(11L, 1L, "MAGIC", null),
                reward(20L, 2L, "DECORATION", 9L),
            ),
        )
        whenever(userQuestRepository.claim(any(), any())).thenReturn(Mono.just(1L))
        magicGrantor.failOnNullTarget = true

        val rewards = questService.checkQuestsWithRewards(userId)

        assertThat(rewards).containsExactly(QuestRewardDto("DECORATION", 9L, null, 1, 2L))
        verify(userQuestRepository).claim(userId, 1L)
        assertThat(transactionalOperator.rollbacks).isEqualTo(1)
        assertThat(transactionalOperator.commits).isEqualTo(1)
        assertThat(transactionalOperator.failures.single()).isInstanceOf(RewardNotGrantableException::class.java)
    }

    @Test
    @DisplayName("claim 이 두_행_이상을_바꾸면_보상_없이_rollback_한다")
    fun checkQuestsWithRewards_MoreThanOneClaimedRowRollsBack() = runTest {
        whenever(questRepository.findClaimable(userId)).thenReturn(Flux.just(quest(1L, requireValue = 1)))
        condition.progressByQuest[1L] = 1
        whenever(questRewardRepository.findAllByQuestIds(listOf(1L)))
            .thenReturn(Flux.just(reward(10L, 1L, "MAGIC", 5L)))
        whenever(userQuestRepository.claim(userId, 1L)).thenReturn(Mono.just(2L))

        assertThat(questService.checkQuestsWithRewards(userId)).isEmpty()
        assertThat(magicGrantor.granted).isEmpty()
        assertThat(transactionalOperator.rollbacks).isEqualTo(1)
    }

    @Test
    @DisplayName("등록되지_않은_condition_type_의_퀘스트는_건너뛰고_claim_하지_않는다")
    fun checkQuestsWithRewards_UnknownConditionTypeIsSkipped() = runTest {
        whenever(questRepository.findClaimable(userId))
            .thenReturn(Flux.just(quest(1L, requireValue = 0, conditionType = "NOT_REGISTERED")))

        assertThat(questService.checkQuestsWithRewards(userId)).isEmpty()
        verify(userQuestRepository, never()).claim(any(), any())
    }

    @Test
    @DisplayName("check 는_조건을_채운_MANUAL_퀘스트와_모르는_claim_mode_퀘스트를_지급하지_않고_모르는_값은_한_번만_WARN_으로_남긴다")
    fun checkQuestsWithRewards_SkipsManualAndUnknownClaimModes() = runTest {
        whenever(questRepository.findClaimable(userId)).thenReturn(
            Flux.just(
                quest(1L, requireValue = 1, claimMode = "MANUAL"),
                quest(2L, requireValue = 1, claimMode = "LATER"),
                quest(3L, requireValue = 1, claimMode = "LATER"),
                quest(4L, requireValue = 1),
            ),
        )
        condition.progressByQuest.putAll(mapOf(1L to 1, 2L to 1, 3L to 1, 4L to 1))
        whenever(questRewardRepository.findAllByQuestIds(listOf(4L))).thenReturn(Flux.just(reward(40L, 4L, "MAGIC", 8L)))
        // The second check finds quest 4 already COMPLETED.
        whenever(userQuestRepository.claim(userId, 4L)).thenReturn(Mono.just(1L), Mono.just(0L))

        val warnings = captureWarnings {
            assertThat(questService.checkQuestsWithRewards(userId)).containsExactly(QuestRewardDto("MAGIC", 8L, null, 1, 4L))
            assertThat(questService.checkQuestsWithRewards(userId)).isEmpty()
        }

        verify(userQuestRepository, never()).claim(userId, 1L)
        verify(userQuestRepository, never()).claim(userId, 2L)
        verify(userQuestRepository, never()).claim(userId, 3L)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("'LATER'")
    }

    // ----- explicit claim -----

    @Test
    @DisplayName("claim 은_조건을_채운_MANUAL_퀘스트를_행을_만든_뒤_한_transaction_에서_claim_하고_보상을_돌려준다")
    fun claimQuest_GrantsMetManualQuest() = runTest {
        decorationGrantor.keyPrefix = "decoration-"
        whenever(questRepository.findActiveById(11L)).thenReturn(Mono.just(quest(11L, requireValue = 3, claimMode = "MANUAL")))
        condition.progressByQuest[11L] = 3
        whenever(questRewardRepository.findAllByQuestIds(listOf(11L)))
            .thenReturn(Flux.just(reward(110L, 11L, "DECORATION", 1L)))
        whenever(userQuestRepository.claim(userId, 11L)).thenReturn(Mono.just(1L))

        val result = questService.claimQuest(userId, 11L)

        assertThat(result).isEqualTo(QuestClaimResult.Claimed(listOf(QuestSummaryRewardDto("DECORATION", 1L, "decoration-1", 1))))
        verify(userQuestRepository).insertMissing(userId)
        assertThat(decorationGrantor.granted.map { (it as QuestReward).id }).containsExactly(110L)
        assertThat(transactionalOperator.commits).isEqualTo(1)
    }

    @Test
    @DisplayName("claim 은_AUTO_퀘스트도_같은_방식으로_지급한다")
    fun claimQuest_GrantsAutoQuestToo() = runTest {
        whenever(questRepository.findActiveById(4L)).thenReturn(Mono.just(quest(4L, requireValue = 1)))
        condition.progressByQuest[4L] = 2
        whenever(questRewardRepository.findAllByQuestIds(listOf(4L))).thenReturn(Flux.just(reward(40L, 4L, "MAGIC", 8L, amount = 2)))
        whenever(userQuestRepository.claim(userId, 4L)).thenReturn(Mono.just(1L))

        assertThat(questService.claimQuest(userId, 4L))
            .isEqualTo(QuestClaimResult.Claimed(listOf(QuestSummaryRewardDto("MAGIC", 8L, null, 2))))
    }

    @Test
    @DisplayName("없거나_DEPRECATED_인_퀘스트의_claim_은_NotFound_이고_아무것도_쓰지_않는다")
    fun claimQuest_UnknownQuestIsNotFound() = runTest {
        whenever(questRepository.findActiveById(5L)).thenReturn(Mono.empty())

        assertThat(questService.claimQuest(userId, 5L)).isEqualTo(QuestClaimResult.NotFound)
        verify(userQuestRepository, never()).insertMissing(any())
        verify(userQuestRepository, never()).claim(any(), any())
    }

    @Test
    @DisplayName("조건을_못_채운_퀘스트의_claim_은_NotClaimable_이고_행도_만들지_않는다")
    fun claimQuest_UnmetConditionIsNotClaimable() = runTest {
        whenever(questRepository.findActiveById(11L)).thenReturn(Mono.just(quest(11L, requireValue = 3, claimMode = "MANUAL")))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 11L)).thenReturn(Mono.empty())
        condition.progressByQuest[11L] = 2

        assertThat(questService.claimQuest(userId, 11L)).isEqualTo(QuestClaimResult.NotClaimable)
        verify(userQuestRepository, never()).insertMissing(any())
        verify(userQuestRepository, never()).claim(any(), any())
        assertThat(transactionalOperator.commits + transactionalOperator.rollbacks).isZero()
    }

    @Test
    @DisplayName("진행을_잴_수_없는_퀘스트의_claim_은_NotClaimable_이다")
    fun claimQuest_UnmeasurableProgressIsNotClaimable() = runTest {
        whenever(questRepository.findActiveById(7L))
            .thenReturn(Mono.just(quest(7L, requireValue = 0, conditionType = "NOT_REGISTERED", claimMode = "MANUAL")))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 7L)).thenReturn(Mono.empty())

        assertThat(questService.claimQuest(userId, 7L)).isEqualTo(QuestClaimResult.NotClaimable)
        verify(userQuestRepository, never()).claim(any(), any())
    }

    @Test
    @DisplayName("이미_받은_퀘스트의_claim_은_조건과_상관없이_AlreadyClaimed_다")
    fun claimQuest_CompletedRowIsAlreadyClaimed() = runTest {
        whenever(questRepository.findActiveById(11L)).thenReturn(Mono.just(quest(11L, requireValue = 3, claimMode = "MANUAL")))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 11L))
            .thenReturn(Mono.just(UserQuest(1L, userId, 11L, QuestState.COMPLETED)))
        whenever(questRewardRepository.findAllByQuestIds(listOf(11L))).thenReturn(Flux.just(reward(110L, 11L, "MAGIC", 1L)))
        whenever(userQuestRepository.claim(userId, 11L)).thenReturn(Mono.just(0L))

        // Condition met: the claim changes no row and the follow-up read finds COMPLETED.
        condition.progressByQuest[11L] = 3
        assertThat(questService.claimQuest(userId, 11L)).isEqualTo(QuestClaimResult.AlreadyClaimed)
        // Condition no longer met: the stored row still answers.
        condition.progressByQuest[11L] = 0
        assertThat(questService.claimQuest(userId, 11L)).isEqualTo(QuestClaimResult.AlreadyClaimed)

        assertThat(magicGrantor.granted).isEmpty()
    }

    @Test
    @DisplayName("claim 이_행을_못_바꾸고_행도_없으면_users_행이_없는_호출자이므로_NotFound_다")
    fun claimQuest_NoUserQuestRowIsNotFound() = runTest {
        whenever(questRepository.findActiveById(11L)).thenReturn(Mono.just(quest(11L, requireValue = 1, claimMode = "MANUAL")))
        condition.progressByQuest[11L] = 1
        whenever(questRewardRepository.findAllByQuestIds(listOf(11L))).thenReturn(Flux.empty())
        whenever(userQuestRepository.claim(userId, 11L)).thenReturn(Mono.just(0L))
        whenever(userQuestRepository.findByUserIdAndQuestId(userId, 11L)).thenReturn(Mono.empty())

        assertThat(questService.claimQuest(userId, 11L)).isEqualTo(QuestClaimResult.NotFound)
    }

    @Test
    @DisplayName("claim 의_보상_지급이_실패하면_예외가_나가고_transaction_은_rollback_된다")
    fun claimQuest_GrantorFailureRollsBack() = runTest {
        whenever(questRepository.findActiveById(11L)).thenReturn(Mono.just(quest(11L, requireValue = 1, claimMode = "MANUAL")))
        condition.progressByQuest[11L] = 1
        whenever(questRewardRepository.findAllByQuestIds(listOf(11L)))
            .thenReturn(Flux.just(reward(110L, 11L, "MAGIC", 5L), reward(111L, 11L, "MAGIC", null)))
        whenever(userQuestRepository.claim(userId, 11L)).thenReturn(Mono.just(1L))
        magicGrantor.failOnNullTarget = true

        val thrown = runCatching { questService.claimQuest(userId, 11L) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RewardNotGrantableException::class.java)
        assertThat(transactionalOperator.rollbacks).isEqualTo(1)
        assertThat(transactionalOperator.commits).isZero()
    }

    // ----- quest list (read-only) -----

    @Test
    @DisplayName("퀘스트_목록은_quest_id_순으로_상태_진행_보상을_담고_아무것도_쓰지_않는다")
    fun findMyQuests_ShapeOrderAndReadOnly() = runTest {
        whenever(questRepository.findAllActive()).thenReturn(
            Flux.just(quest(5L, requireValue = 3, conditionType = "STAGE_CLEAR", targetId = 41L), quest(2L, requireValue = 10)),
        )
        whenever(questRewardRepository.findAllOfActiveQuests()).thenReturn(
            Flux.just(
                reward(1L, 2L, "MAGIC", 8L, amount = 2),
                reward(2L, 5L, "DECORATION", null),
                reward(3L, 5L, "MAGIC", 9L),
            ),
        )
        whenever(userQuestRepository.findAllByUserId(userId))
            .thenReturn(Flux.just(UserQuest(1L, userId, 5L, QuestState.COMPLETED)))
        condition.progressByQuest.putAll(mapOf(2L to 0, 5L to 3))

        val quests = questService.findMyQuests(userId)

        assertThat(quests.map { it.questId }).containsExactly(2L, 5L)
        val first = quests[0]
        assertThat(first.conditionType).isEqualTo("STAGE_CLEAR")
        assertThat(first.conditionTargetId).isNull()
        assertThat(first.state).isEqualTo(QuestState.PENDING)
        assertThat(first.progress).isZero()
        assertThat(first.requireValue).isEqualTo(10)
        assertThat(first.rewards).containsExactly(QuestSummaryRewardDto("MAGIC", 8L, null, 2))
        val second = quests[1]
        assertThat(second.conditionTargetId).isEqualTo(41L)
        assertThat(second.state).isEqualTo(QuestState.COMPLETED)
        assertThat(second.progress).isEqualTo(3)
        assertThat(second.rewards).containsExactly(
            QuestSummaryRewardDto("DECORATION", 0L, null, 1),
            QuestSummaryRewardDto("MAGIC", 9L, null, 1),
        )

        verify(userQuestRepository, never()).insertMissing(any())
        verify(userQuestRepository, never()).claim(any(), any())
        verify(userQuestRepository, never()).save(any<UserQuest>())
        verify(questRewardRepository, never()).findAllByQuestIds(anyOrNull())
        assertThat(transactionalOperator.commits + transactionalOperator.rollbacks).isZero()
    }

    @Test
    @DisplayName("퀘스트_목록은_claimMode_를_그대로_싣고_claimable_은_조건을_채웠고_아직_받지_않은_퀘스트만_true_다")
    fun findMyQuests_ClaimModeAndClaimable() = runTest {
        whenever(questRepository.findAllActive()).thenReturn(
            Flux.just(
                quest(1L, requireValue = 3, claimMode = "MANUAL"),
                quest(2L, requireValue = 3, claimMode = "MANUAL"),
                quest(3L, requireValue = 3),
                quest(4L, requireValue = 0, conditionType = "NOT_REGISTERED", claimMode = "MANUAL"),
                quest(5L, requireValue = 1),
            ),
        )
        whenever(questRewardRepository.findAllOfActiveQuests()).thenReturn(Flux.empty())
        whenever(userQuestRepository.findAllByUserId(userId)).thenReturn(
            Flux.just(
                UserQuest(1L, userId, 1L, QuestState.IN_PROGRESS),
                UserQuest(2L, userId, 2L, QuestState.COMPLETED),
                UserQuest(3L, userId, 3L, QuestState.IN_PROGRESS),
            ),
        )
        // Quest 1 met and unclaimed, 2 met and claimed, 3 not met, 4 unmeasurable, 5 met with no row yet.
        condition.progressByQuest.putAll(mapOf(1L to 3, 2L to 3, 3L to 2, 5L to 1))

        val quests = questService.findMyQuests(userId)

        assertThat(quests.map { it.claimMode }).containsExactly("MANUAL", "MANUAL", "AUTO", "MANUAL", "AUTO")
        assertThat(quests.map { it.claimable }).containsExactly(true, false, false, false, true)
        assertThat(quests.map { it.state }).containsExactly(
            QuestState.IN_PROGRESS,
            QuestState.COMPLETED,
            QuestState.IN_PROGRESS,
            QuestState.PENDING,
            QuestState.IN_PROGRESS,
        )
    }

    @Test
    @DisplayName("퀘스트_목록은_등록되지_않은_condition_type_도_raw_문자열과_진행_0_으로_내려준다")
    fun findMyQuests_UnknownConditionTypeIsListedWithZeroProgress() = runTest {
        whenever(questRepository.findAllActive())
            .thenReturn(Flux.just(quest(1L, requireValue = 5, conditionType = "FUTURE_TYPE")))
        whenever(questRewardRepository.findAllOfActiveQuests()).thenReturn(Flux.empty())
        whenever(userQuestRepository.findAllByUserId(userId)).thenReturn(Flux.empty())

        val quests = questService.findMyQuests(userId)

        assertThat(quests.single().conditionType).isEqualTo("FUTURE_TYPE")
        assertThat(quests.single().progress).isZero()
        assertThat(quests.single().rewards).isEmpty()
    }
}

/** Runs [block] and returns the formatted WARN messages [QuestService] logged during it. */
private inline fun captureWarnings(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(QuestService::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
}

/** A condition whose progress the test sets per quest id. */
class FixedProgressCondition(override val type: String) : QuestCondition {
    val progressByQuest = Collections.synchronizedMap(mutableMapOf<Long, Int>())
    var yieldBeforeAnswer = false
    var calls = 0

    override suspend fun progress(userId: Long, quest: Quest): Int {
        calls++
        if (yieldBeforeAnswer) yield()
        return progressByQuest[quest.id] ?: 0
    }
}

/** Records what it granted; with [failOnNullTarget] it throws on a null target like the real grantors. */
class RecordingGrantor(override val type: String) : RewardGrantor {
    val granted = mutableListOf<Reward>()
    var failOnNullTarget = false

    /** When set, [describe] answers "<prefix><targetId>"; otherwise null like MAGIC and DECORATION. */
    var keyPrefix: String? = null

    override suspend fun grant(userId: Long, reward: Reward) {
        if (failOnNullTarget && reward.targetId == null) {
            throw RewardNotGrantableException(reward, "target_id is null")
        }
        granted += reward
    }

    override suspend fun describe(targetId: Long?): String? = keyPrefix?.let { "$it$targetId" }
}

/**
 * Runs the callback like a real operator and counts how each transaction ended. A transaction that
 * ends in an error is the one a real operator rolls back.
 */
class RecordingTransactionalOperator : TransactionalOperator {
    var commits = 0
    var rollbacks = 0
    val failures = mutableListOf<Throwable>()

    override fun <T> execute(action: TransactionCallback<T>): Flux<T> =
        Flux.defer { Flux.from(action.doInTransaction(mock<ReactiveTransaction>())) }
            .doOnComplete { commits++ }
            .doOnError {
                rollbacks++
                failures += it
            }

    override fun <T> transactional(mono: Mono<T>): Mono<T> = mono
}
