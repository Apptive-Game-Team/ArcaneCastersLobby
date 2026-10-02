package com.wordonline.matching.quest.reward

import com.wordonline.matching.decoration.repository.UserDecorationRepository
import com.wordonline.matching.quest.entity.QuestReward
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component

/**
 * `DECORATION`: gives the user the decoration `target_id`, unequipped. A decoration is owned or
 * not, so `amount` does not multiply it, and a user who already owns it gets no second row.
 */
@Component
class DecorationRewardGrantor(
    private val userDecorationRepository: UserDecorationRepository,
) : RewardGrantor {

    override val type: String = TYPE

    override suspend fun grant(userId: Long, reward: QuestReward) {
        val decorationId = requireTargetId(reward)
        userDecorationRepository.insertIfAbsent(userId, decorationId).awaitSingle()
    }

    companion object {
        const val TYPE = "DECORATION"
    }
}
