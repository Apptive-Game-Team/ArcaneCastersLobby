package com.wordonline.matching.quest.repository

import com.wordonline.matching.quest.entity.Quest
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Reads `quests`. Every query names its columns so the legacy `progress_checker` and
 * `reward_giver` columns are never read.
 */
interface QuestRepository : R2dbcRepository<Quest, Long> {

    /** Every quest that is not `DEPRECATED`, ordered by id. */
    @Query(
        """
        SELECT q.id, q.require_value, q.access_type, q.condition_type, q.condition_target_id
        FROM quests q
        WHERE q.access_type <> 'DEPRECATED'
        ORDER BY q.id
        """,
    )
    fun findAllActive(): Flux<Quest>

    /** Quests this user may still claim: their row is `IN_PROGRESS` and the quest is not `DEPRECATED`. */
    @Query(
        """
        SELECT q.id, q.require_value, q.access_type, q.condition_type, q.condition_target_id
        FROM quests q
        JOIN user_quests uq ON uq.quest_id = q.id
        WHERE uq.user_id = :userId
          AND uq.state = 'IN_PROGRESS'
          AND q.access_type <> 'DEPRECATED'
        ORDER BY q.id
        """,
    )
    fun findClaimable(userId: Long): Flux<Quest>

    /**
     * The quest that grants this reward. Several quests can grant the same thing, so this picks one
     * deterministically: a quest that is not `DEPRECATED` before one that is, then the lowest id.
     */
    @Query(
        """
        SELECT q.id, q.require_value, q.access_type, q.condition_type, q.condition_target_id
        FROM quests q
        JOIN quest_rewards qr ON qr.quest_id = q.id
        WHERE qr.reward_type = :rewardType
          AND qr.target_id = :targetId
        ORDER BY (q.access_type = 'DEPRECATED'), q.id
        LIMIT 1
        """,
    )
    fun findFirstByReward(rewardType: String, targetId: Long): Mono<Quest>
}
