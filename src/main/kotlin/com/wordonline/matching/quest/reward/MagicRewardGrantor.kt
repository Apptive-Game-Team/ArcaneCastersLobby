package com.wordonline.matching.quest.reward

import com.wordonline.matching.deck.repository.UserCardRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component

/**
 * `MAGIC`: adds `amount` copies of the magic `target_id` to the user's `user_magics` row, creating
 * the row with `amount` copies when the user has none.
 *
 * The Java `MagicRewardGiver` inserted a new `user_magics` row through `save`, which broke on the
 * unique (`user_id`, `magic_id`) constraint whenever the user already owned that magic. Granting
 * now adds to the existing count.
 */
@Component
class MagicRewardGrantor(
    private val userCardRepository: UserCardRepository,
) : RewardGrantor {

    override val type: String = TYPE

    override suspend fun grant(userId: Long, reward: Reward) {
        val magicId = requireTargetId(reward)
        val changed = userCardRepository.addCount(userId, magicId, reward.amount).awaitSingle()
        if (changed != 1L) {
            throw RewardNotGrantableException(reward, "user_magics changed $changed rows, expected 1")
        }
    }

    /** A magic is shown by its id; there is no string key. */
    override suspend fun describe(targetId: Long?): String? = null

    companion object {
        const val TYPE = "MAGIC"
    }
}
