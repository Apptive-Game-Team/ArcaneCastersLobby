package com.wordonline.matching.quest.reward

import com.wordonline.matching.appearance.repository.AppearanceRepository
import org.springframework.stereotype.Component

/**
 * `APPEARANCE`: gives the user the appearance whose `appearances.id` is `target_id`. An appearance
 * is owned or not, so `amount` does not multiply it, and granting one the user already owns changes
 * nothing and is not an error. The appearance `default` is owned by everybody without a row, so
 * granting it writes nothing. Granting does not change the selected appearance (`users.appearance`).
 *
 * A `target_id` that is null or names no appearance throws [RewardNotGrantableException].
 */
@Component
class AppearanceRewardGrantor(
    private val appearanceRepository: AppearanceRepository,
) : RewardGrantor {

    override val type: String = TYPE

    override suspend fun grant(userId: Long, reward: Reward) {
        val appearanceId = requireTargetId(reward)
        val key = appearanceRepository.findKeyById(appearanceId)
            ?: throw RewardNotGrantableException(reward, "appearance $appearanceId does not exist")
        if (key == AppearanceRepository.DEFAULT_KEY) {
            return
        }
        appearanceRepository.insertOwnership(userId, appearanceId)
    }

    /** The appearance key, which is also the client's directory name under `Resources/PlayerAppearances/`. */
    override suspend fun describe(targetId: Long?): String? =
        targetId?.let { appearanceRepository.findKeyById(it) }

    companion object {
        const val TYPE = "APPEARANCE"
    }
}
