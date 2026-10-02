package com.wordonline.matching.quest.service

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

    private fun quest(id: Long, requireValue: Int = 100, conditionType: String = "STAGE_CLEAR", targetId: Long? = null) =
        Quest(id, requireValue, "DEFAULT", conditionType, targetId)

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
            QuestRewardDto("MAGIC", 10L, 3, 1L),
            QuestRewardDto("DECORATION", 20L, 1, 2L),
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
            QuestRewardDto("MAGIC", 5L, 2, 1L),
            QuestRewardDto("MAGIC", 6L, 1, 1L),
            QuestRewardDto("DECORATION", 7L, 1, 1L),
        )
        assertThat(magicGrantor.granted.map { it.id }).containsExactly(10L, 11L)
        assertThat(decorationGrantor.granted.map { it.id }).containsExactly(12L)
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

        assertThat(results.flatten()).containsExactly(QuestRewardDto("MAGIC", 5L, 1, 1L))
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

        assertThat(rewards).containsExactly(QuestRewardDto("DECORATION", 9L, 1, 2L))
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
        assertThat(first.rewards).containsExactly(QuestSummaryRewardDto("MAGIC", 8L, 2))
        val second = quests[1]
        assertThat(second.conditionTargetId).isEqualTo(41L)
        assertThat(second.state).isEqualTo(QuestState.COMPLETED)
        assertThat(second.progress).isEqualTo(3)
        assertThat(second.rewards).containsExactly(
            QuestSummaryRewardDto("DECORATION", 0L, 1),
            QuestSummaryRewardDto("MAGIC", 9L, 1),
        )

        verify(userQuestRepository, never()).insertMissing(any())
        verify(userQuestRepository, never()).claim(any(), any())
        verify(userQuestRepository, never()).save(any<UserQuest>())
        verify(questRewardRepository, never()).findAllByQuestIds(anyOrNull())
        assertThat(transactionalOperator.commits + transactionalOperator.rollbacks).isZero()
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
    val granted = mutableListOf<QuestReward>()
    var failOnNullTarget = false

    override suspend fun grant(userId: Long, reward: QuestReward) {
        if (failOnNullTarget && reward.targetId == null) {
            throw RewardNotGrantableException(reward, "target_id is null")
        }
        granted += reward
    }
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
