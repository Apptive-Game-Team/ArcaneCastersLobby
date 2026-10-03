package com.wordonline.matching.quest.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * One row of `quests`.
 *
 * The legacy columns `progress_checker` and `reward_giver` are deliberately not mapped: they stay
 * in the table only until a later migration drops them, and this server neither reads nor writes
 * them. This entity is read-only; nothing here saves a quest.
 *
 * [claimMode] is the raw `claim_mode` string. The database defaults it to [AUTO_CLAIM_MODE].
 */
@Table("quests")
data class Quest(
    @Id val id: Long,
    val requireValue: Int,
    val accessType: String,
    val conditionType: String,
    val conditionTargetId: Long? = null,
    val claimMode: String = AUTO_CLAIM_MODE,
) {
    /**
     * Whether `POST /api/users/mine/quests/check` may grant this quest. Only [AUTO_CLAIM_MODE] is;
     * [MANUAL_CLAIM_MODE] and any value this server does not know wait for the explicit claim.
     */
    val isClaimedAutomatically: Boolean get() = claimMode == AUTO_CLAIM_MODE

    val hasKnownClaimMode: Boolean get() = claimMode == AUTO_CLAIM_MODE || claimMode == MANUAL_CLAIM_MODE

    companion object {
        /** `access_type` of a retired quest: it gets no `user_quests` row and is never claimed. */
        const val DEPRECATED_ACCESS_TYPE = "DEPRECATED"

        /** `claim_mode` of a quest the check path grants as soon as its condition is met. */
        const val AUTO_CLAIM_MODE = "AUTO"

        /** `claim_mode` of a quest granted only by `POST /api/users/mine/quests/{questId}/claim`. */
        const val MANUAL_CLAIM_MODE = "MANUAL"
    }
}
