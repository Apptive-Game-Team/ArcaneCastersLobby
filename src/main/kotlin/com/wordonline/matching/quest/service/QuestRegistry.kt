package com.wordonline.matching.quest.service

import com.wordonline.matching.quest.condition.QuestCondition
import com.wordonline.matching.quest.reward.RewardGrantor
import org.springframework.stereotype.Component

/**
 * Looks up the [QuestCondition] for a `quests.condition_type` and the [RewardGrantor] for a
 * `quest_rewards.reward_type`. Built from every bean of each interface, so a new condition or
 * reward kind is registered by declaring its component and nothing else.
 *
 * Two beans that claim the same type would make the lookup depend on bean order, so construction
 * fails instead and the application does not start.
 */
@Component
class QuestRegistry(
    conditions: List<QuestCondition>,
    grantors: List<RewardGrantor>,
) {
    private val conditionsByType: Map<String, QuestCondition> =
        indexByType(conditions, "QuestCondition") { it.type }
    private val grantorsByType: Map<String, RewardGrantor> =
        indexByType(grantors, "RewardGrantor") { it.type }

    val conditionTypes: Set<String> get() = conditionsByType.keys
    val rewardTypes: Set<String> get() = grantorsByType.keys

    fun findCondition(type: String): QuestCondition? = conditionsByType[type]

    fun findGrantor(type: String): RewardGrantor? = grantorsByType[type]

    fun condition(type: String): QuestCondition =
        findCondition(type) ?: throw UnknownQuestTypeException("no QuestCondition is registered for condition_type '$type'")

    fun grantor(type: String): RewardGrantor =
        findGrantor(type) ?: throw UnknownQuestTypeException("no RewardGrantor is registered for reward_type '$type'")

    private fun <T : Any> indexByType(beans: List<T>, kind: String, typeOf: (T) -> String): Map<String, T> {
        val duplicates = beans.groupBy(typeOf).filterValues { it.size > 1 }
        check(duplicates.isEmpty()) {
            duplicates.entries.joinToString(
                prefix = "Two or more $kind beans declare the same type: ",
                separator = "; ",
            ) { (type, sameType) -> "'$type' by ${sameType.joinToString { it.javaClass.name }}" }
        }
        return beans.associateBy(typeOf)
    }
}

/** A `condition_type` or `reward_type` with no registered implementation. */
class UnknownQuestTypeException(message: String) : IllegalStateException(message)
