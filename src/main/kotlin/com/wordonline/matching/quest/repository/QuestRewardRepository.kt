package com.wordonline.matching.quest.repository

import com.wordonline.matching.quest.entity.QuestReward
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux

interface QuestRewardRepository : R2dbcRepository<QuestReward, Long> {

    /** Rewards of the given quests in one query, ordered by quest id and then reward id. */
    @Query(
        """
        SELECT qr.id, qr.quest_id, qr.reward_type, qr.target_id, qr.amount
        FROM quest_rewards qr
        WHERE qr.quest_id IN (:questIds)
        ORDER BY qr.quest_id, qr.id
        """,
    )
    fun findAllByQuestIds(questIds: Collection<Long>): Flux<QuestReward>

    /** Rewards of every quest that is not `DEPRECATED`, ordered by quest id and then reward id. */
    @Query(
        """
        SELECT qr.id, qr.quest_id, qr.reward_type, qr.target_id, qr.amount
        FROM quest_rewards qr
        JOIN quests q ON q.id = qr.quest_id
        WHERE q.access_type <> 'DEPRECATED'
        ORDER BY qr.quest_id, qr.id
        """,
    )
    fun findAllOfActiveQuests(): Flux<QuestReward>
}
