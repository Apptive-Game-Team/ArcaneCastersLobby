package com.wordonline.matching.quest.service

import com.wordonline.matching.quest.domain.QuestState
import com.wordonline.matching.quest.dto.QuestProgressResponseDto
import com.wordonline.matching.quest.dto.QuestRewardDto
import com.wordonline.matching.quest.dto.QuestSummaryResponseDto
import com.wordonline.matching.quest.dto.QuestSummaryRewardDto
import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.entity.QuestReward
import com.wordonline.matching.quest.repository.QuestRepository
import com.wordonline.matching.quest.repository.QuestRewardRepository
import com.wordonline.matching.quest.repository.UserQuestRepository
import com.wordonline.matching.quest.reward.DecorationRewardGrantor
import com.wordonline.matching.quest.reward.MagicRewardGrantor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.concurrent.ConcurrentHashMap

/** Outcome of claiming one quest through [QuestService.claimQuest]. */
sealed interface QuestClaimResult {
    /** This call claimed the quest and granted every reward in [rewards]. */
    data class Claimed(val rewards: List<QuestSummaryRewardDto>) : QuestClaimResult

    /** No such quest, a `DEPRECATED` one, or a caller with no `users` row. */
    data object NotFound : QuestClaimResult

    /** The rewards were granted before, by an earlier or a concurrent claim or check. */
    data object AlreadyClaimed : QuestClaimResult

    /** The condition is not met yet (or its progress could not be measured); nothing was written. */
    data object NotClaimable : QuestClaimResult
}

/**
 * Quest progress and rewards.
 *
 * [checkQuestsWithRewards] and [claimQuest] are the only methods that write. The read methods derive the state from
 * `user_quests` and the condition's progress without changing anything.
 */
@Service
class QuestService(
    private val questRepository: QuestRepository,
    private val questRewardRepository: QuestRewardRepository,
    private val userQuestRepository: UserQuestRepository,
    private val questRegistry: QuestRegistry,
    private val transactionalOperator: TransactionalOperator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** `claim_mode` values already logged by [isClaimedAutomatically]. */
    private val warnedClaimModes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Grants the rewards of every `AUTO` quest this user has met and not yet claimed, and returns one
     * [QuestRewardDto] per granted reward.
     *
     * `MANUAL` quests, and quests whose `claim_mode` this server does not know, are skipped here:
     * their `user_quests` row is still created so the state can be tracked, but only [claimQuest]
     * grants them.
     *
     * Each quest is claimed in its own transaction before its rewards are granted (see
     * [claimAndGrant]), so concurrent calls grant a quest once. A quest whose rewards fail to grant
     * is rolled back to `IN_PROGRESS`, logged, and left for the next call; the other quests still
     * complete, because their rewards are already committed and the client should hear about them.
     */
    suspend fun checkQuestsWithRewards(userId: Long): List<QuestRewardDto> {
        userQuestRepository.insertMissing(userId).awaitSingle()

        val metQuests = questRepository.findClaimable(userId).asFlow().toList()
            .filter(::isClaimedAutomatically)
            .filter { quest ->
                val progress = measureProgress(userId, quest) ?: return@filter false
                progress >= quest.requireValue
            }
        if (metQuests.isEmpty()) {
            return emptyList()
        }

        val rewardsByQuestId = questRewardRepository.findAllByQuestIds(metQuests.map(Quest::id))
            .asFlow().toList()
            .groupBy(QuestReward::questId)

        return metQuests.flatMap { quest ->
            val rewards = rewardsByQuestId[quest.id].orEmpty()
            try {
                transactionalOperator.executeAndAwait {
                    when (val outcome = claimAndGrant(userId, quest, rewards)) {
                        is ClaimOutcome.Granted -> outcome.rewards
                        ClaimOutcome.NothingChanged -> emptyList()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Quest {} for user {} was not granted and stays claimable", quest.id, userId, e)
                emptyList()
            }
        }
    }

    /**
     * Claims one quest on the player's request, whatever its `claim_mode`, through the same
     * claim-before-grant step as [checkQuestsWithRewards].
     *
     * The quest must exist and not be `DEPRECATED` ([QuestClaimResult.NotFound]). Progress is
     * measured first; when the condition is not met, nothing is written and the stored row tells an
     * earlier claim ([QuestClaimResult.AlreadyClaimed]) from a quest still in progress
     * ([QuestClaimResult.NotClaimable]). When it is met, the missing `user_quests` rows are created
     * (so a first claim finds its row) and one transaction claims and grants. When the claim changes
     * no row, a follow-up read in that transaction decides the answer: a `COMPLETED` row is
     * [QuestClaimResult.AlreadyClaimed] (an earlier or a concurrent claim won; the `UPDATE` waited
     * for it), no row means the user has no `users` row ([QuestClaimResult.NotFound]). A grantor that
     * throws rolls the claim back and the exception reaches the caller.
     */
    suspend fun claimQuest(userId: Long, questId: Long): QuestClaimResult {
        val quest = questRepository.findActiveById(questId).awaitSingleOrNull() ?: return QuestClaimResult.NotFound
        val progress = measureProgress(userId, quest)
        if (progress == null || progress < quest.requireValue) {
            val storedState = userQuestRepository.findByUserIdAndQuestId(userId, quest.id).awaitSingleOrNull()?.state
            return if (storedState == QuestState.COMPLETED) QuestClaimResult.AlreadyClaimed else QuestClaimResult.NotClaimable
        }

        userQuestRepository.insertMissing(userId).awaitSingle()
        val rewards = questRewardRepository.findAllByQuestIds(listOf(quest.id)).asFlow().toList()

        return transactionalOperator.executeAndAwait {
            when (val outcome = claimAndGrant(userId, quest, rewards)) {
                is ClaimOutcome.Granted -> QuestClaimResult.Claimed(
                    outcome.rewards.map { QuestSummaryRewardDto(it.rewardType, it.rewardId, it.rewardKey, it.amount) },
                )
                ClaimOutcome.NothingChanged ->
                    when (userQuestRepository.findByUserIdAndQuestId(userId, quest.id).awaitSingleOrNull()?.state) {
                        null -> QuestClaimResult.NotFound
                        QuestState.COMPLETED -> QuestClaimResult.AlreadyClaimed
                        // The UPDATE waits out any concurrent claimer, so a row still claimable here
                        // means something else is wrong; failing beats answering 422 for a met quest.
                        else -> throw IllegalStateException(
                            "user_quests row of user $userId and quest ${quest.id} is not COMPLETED but could not be claimed",
                        )
                    }
            }
        }
    }

    /**
     * Claim before grant. Must run inside a transaction, which the callers open. The conditional
     * `UPDATE ... WHERE state = 'IN_PROGRESS'` takes the row lock; a second transaction on the same
     * row blocks until the first ends and then sees `COMPLETED`, so it changes 0 rows and grants
     * nothing. Rewards are granted only when exactly one row changed, and a grantor that throws rolls
     * the claim back with everything else granted so far in this transaction.
     */
    private suspend fun claimAndGrant(userId: Long, quest: Quest, rewards: List<QuestReward>): ClaimOutcome =
        when (val claimed = userQuestRepository.claim(userId, quest.id).awaitSingle()) {
            0L -> ClaimOutcome.NothingChanged
            1L -> ClaimOutcome.Granted(
                rewards.map { reward ->
                    val grantor = questRegistry.grantor(reward.rewardType)
                    grantor.grant(userId, reward)
                    QuestRewardDto(
                        rewardType = reward.rewardType,
                        rewardId = reward.targetId ?: 0L,
                        rewardKey = grantor.describe(reward.targetId),
                        amount = reward.amount,
                        questId = quest.id,
                    )
                },
            )
            // Only possible without the unique (user_id, quest_id) constraint. Throwing rolls
            // the claim back rather than marking duplicate rows completed with nothing granted.
            else -> throw IllegalStateException(
                "user_quests has $claimed IN_PROGRESS rows for user $userId and quest ${quest.id}, expected at most 1",
            )
        }

    /** What [claimAndGrant] did inside its transaction. */
    private sealed interface ClaimOutcome {
        data class Granted(val rewards: List<QuestRewardDto>) : ClaimOutcome

        /** The row was not `IN_PROGRESS` (or does not exist), so nothing was granted. */
        data object NothingChanged : ClaimOutcome
    }

    /**
     * Whether the check path may grant [quest]. A `claim_mode` this server does not know is treated
     * like `MANUAL`, so a value added by a later migration never hands out rewards automatically;
     * each such value is logged once at WARN.
     */
    private fun isClaimedAutomatically(quest: Quest): Boolean {
        if (!quest.hasKnownClaimMode && warnedClaimModes.add(quest.claimMode)) {
            log.warn(
                "Quest {} has claim_mode '{}', which this server does not know; it is never granted automatically " +
                    "and only the claim endpoint grants it. Further quests with this claim_mode are not logged.",
                quest.id,
                quest.claimMode,
            )
        }
        return quest.isClaimedAutomatically
    }

    suspend fun findQuestProgressByCard(userId: Long, cardId: Long): QuestProgressResponseDto? =
        findQuestProgressByReward(userId, MagicRewardGrantor.TYPE, cardId)

    suspend fun findQuestProgressByDecoration(userId: Long, decorationId: Long): QuestProgressResponseDto? =
        findQuestProgressByReward(userId, DecorationRewardGrantor.TYPE, decorationId)

    /** Read-only. Null when no quest grants this reward, which the endpoints answer with an empty body as before. */
    private suspend fun findQuestProgressByReward(
        userId: Long,
        rewardType: String,
        targetId: Long,
    ): QuestProgressResponseDto? {
        val quest = questRepository.findFirstByReward(rewardType, targetId).awaitSingleOrNull() ?: return null
        val storedState = userQuestRepository.findByUserIdAndQuestId(userId, quest.id).awaitSingleOrNull()?.state
        val progress = measureProgress(userId, quest) ?: 0
        return QuestProgressResponseDto(QuestState.derive(storedState, progress), progress, quest.requireValue)
    }

    /**
     * Read-only. Every quest that is not `DEPRECATED`, ordered by quest id, with this user's state
     * and progress and the quest's rewards. Rewards come from one query for all quests.
     */
    suspend fun findMyQuests(userId: Long): List<QuestSummaryResponseDto> {
        val (quests, rewardsByQuestId, storedStates) = coroutineScope {
            val quests = async { questRepository.findAllActive().asFlow().toList() }
            val rewards = async {
                questRewardRepository.findAllOfActiveQuests().asFlow().toList().groupBy(QuestReward::questId)
            }
            val states = async {
                userQuestRepository.findAllByUserId(userId).asFlow().toList()
                    .associate { it.questId to it.state }
            }
            Triple(quests.await(), rewards.await(), states.await())
        }

        val rewardKeys = questRegistry.describeRewards(rewardsByQuestId.values.flatten())

        return quests.sortedBy(Quest::id).map { quest ->
            val measuredProgress = measureProgress(userId, quest)
            val progress = measuredProgress ?: 0
            val storedState = storedStates[quest.id]
            QuestSummaryResponseDto(
                questId = quest.id,
                conditionType = quest.conditionType,
                conditionTargetId = quest.conditionTargetId,
                state = QuestState.derive(storedState, progress),
                progress = progress,
                requireValue = quest.requireValue,
                claimMode = quest.claimMode,
                // The list holds no DEPRECATED quest, so that part of the rule is the query's filter.
                claimable = measuredProgress != null &&
                    measuredProgress >= quest.requireValue &&
                    storedState != QuestState.COMPLETED,
                rewards = rewardsByQuestId[quest.id].orEmpty().map { reward ->
                    QuestSummaryRewardDto(
                        rewardType = reward.rewardType,
                        rewardId = reward.targetId ?: 0L,
                        rewardKey = rewardKeys.keyOf(reward),
                        amount = reward.amount,
                    )
                },
            )
        }
    }

    /**
     * Progress through the registered condition, or null when it cannot be measured: no condition
     * is registered for the type (a quest added after the startup check) or the condition failed.
     * Both are logged. The check path skips such a quest; the read paths show progress 0.
     */
    private suspend fun measureProgress(userId: Long, quest: Quest): Int? {
        val condition = questRegistry.findCondition(quest.conditionType)
        if (condition == null) {
            log.error("Quest {} has condition_type '{}' with no registered QuestCondition", quest.id, quest.conditionType)
            return null
        }
        return try {
            condition.progress(userId, quest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Progress of quest {} for user {} could not be measured", quest.id, userId, e)
            null
        }
    }
}
