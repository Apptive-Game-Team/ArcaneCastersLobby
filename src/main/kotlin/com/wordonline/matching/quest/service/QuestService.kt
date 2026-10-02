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

/**
 * Quest progress and rewards.
 *
 * [checkQuestsWithRewards] is the only method that writes. The read methods derive the state from
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

    /**
     * Grants the rewards of every quest this user has met and not yet claimed, and returns one
     * [QuestRewardDto] per granted reward.
     *
     * Each quest is claimed in its own transaction before its rewards are granted (see
     * [claimAndGrant]), so concurrent calls grant a quest once. A quest whose rewards fail to grant
     * is rolled back to `IN_PROGRESS`, logged, and left for the next call; the other quests still
     * complete, because their rewards are already committed and the client should hear about them.
     */
    suspend fun checkQuestsWithRewards(userId: Long): List<QuestRewardDto> {
        userQuestRepository.insertMissing(userId).awaitSingle()

        val metQuests = questRepository.findClaimable(userId).asFlow().toList()
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
                claimAndGrant(userId, quest, rewards)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Quest {} for user {} was not granted and stays claimable", quest.id, userId, e)
                emptyList()
            }
        }
    }

    /**
     * Claim before grant, in one transaction. The conditional `UPDATE ... WHERE state =
     * 'IN_PROGRESS'` takes the row lock; a second transaction on the same row blocks until the first
     * ends and then sees `COMPLETED`, so it changes 0 rows and grants nothing. Rewards are granted
     * only when exactly one row changed, and a grantor that throws rolls the claim back with
     * everything else granted so far in this transaction.
     */
    private suspend fun claimAndGrant(userId: Long, quest: Quest, rewards: List<QuestReward>): List<QuestRewardDto> =
        transactionalOperator.executeAndAwait {
            when (val claimed = userQuestRepository.claim(userId, quest.id).awaitSingle()) {
                0L -> emptyList()
                1L -> rewards.map { reward ->
                    val grantor = questRegistry.grantor(reward.rewardType)
                    grantor.grant(userId, reward)
                    QuestRewardDto(
                        rewardType = reward.rewardType,
                        rewardId = reward.targetId ?: 0L,
                        rewardKey = grantor.describe(reward.targetId),
                        amount = reward.amount,
                        questId = quest.id,
                    )
                }
                // Only possible without the unique (user_id, quest_id) constraint. Throwing rolls
                // the claim back rather than marking duplicate rows completed with nothing granted.
                else -> throw IllegalStateException(
                    "user_quests has $claimed IN_PROGRESS rows for user $userId and quest ${quest.id}, expected at most 1",
                )
            }
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
            val progress = measureProgress(userId, quest) ?: 0
            QuestSummaryResponseDto(
                questId = quest.id,
                conditionType = quest.conditionType,
                conditionTargetId = quest.conditionTargetId,
                state = QuestState.derive(storedStates[quest.id], progress),
                progress = progress,
                requireValue = quest.requireValue,
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
