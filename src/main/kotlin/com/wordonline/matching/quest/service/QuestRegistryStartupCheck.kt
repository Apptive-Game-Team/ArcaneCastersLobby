package com.wordonline.matching.quest.service

import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.quest.repository.QuestRepository
import com.wordonline.matching.quest.repository.QuestRewardRepository
import com.wordonline.matching.quest.reward.ChestRewardGrantor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Stops the application at startup when a quest that is not `DEPRECATED` names a
 * `condition_type`, or one of its rewards a `reward_type`, that no component handles, or when a
 * `chest_rewards` row names an unhandled `reward_type` or `CHEST` (a chest cannot contain a chest).
 * Without this such a quest would never complete, or would fail on every check, such a chest would
 * fail on every open, and nobody would notice until a player asked.
 *
 * A row inserted after startup with an unknown type is not caught here; the check path logs it and
 * skips that quest, and opening such a chest fails and leaves it unopened. `DEPRECATED` quests are
 * never claimed, so their types are not checked.
 */
@Component
class QuestRegistryStartupCheck(
    private val questRepository: QuestRepository,
    private val questRewardRepository: QuestRewardRepository,
    private val chestRepository: ChestRepository,
    private val questRegistry: QuestRegistry,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    // ApplicationRunner is a plain function; runBlocking runs once on the startup thread, not on an event loop.
    override fun run(args: ApplicationArguments) {
        runBlocking { verify() }
    }

    suspend fun verify() {
        val quests = questRepository.findAllActive().asFlow().toList()
        val rewards = questRewardRepository.findAllOfActiveQuests().asFlow().toList()
        val chestRewards = chestRepository.findAllRewards()

        val problems = quests
            .filter { questRegistry.findCondition(it.conditionType) == null }
            .map { "quest ${it.id} has condition_type '${it.conditionType}'" } +
            rewards
                .filter { questRegistry.findGrantor(it.rewardType) == null }
                .map { "quest_rewards ${it.id} of quest ${it.questId} has reward_type '${it.rewardType}'" } +
            chestRewards
                .filter { questRegistry.findGrantor(it.rewardType) == null }
                .map { "chest_rewards ${it.id} of chest ${it.chestId} has reward_type '${it.rewardType}'" } +
            chestRewards
                .filter { it.rewardType == ChestRewardGrantor.TYPE }
                .map {
                    "chest_rewards ${it.id} of chest ${it.chestId} has reward_type '${ChestRewardGrantor.TYPE}', " +
                        "but a chest cannot contain a chest"
                }

        if (problems.isNotEmpty()) {
            throw UnknownQuestTypeException(
                problems.joinToString(
                    prefix = "Quests or chests use reward or condition types this server cannot handle " +
                        "(conditions: ${questRegistry.conditionTypes}, rewards: ${questRegistry.rewardTypes}): ",
                    separator = "; ",
                ),
            )
        }
        log.info(
            "Quest types checked: {} active quests, {} quest rewards, {} chest rewards, all registered",
            quests.size,
            rewards.size,
            chestRewards.size,
        )
    }
}
