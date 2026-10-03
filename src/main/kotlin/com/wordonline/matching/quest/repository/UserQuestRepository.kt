package com.wordonline.matching.quest.repository

import com.wordonline.matching.quest.entity.UserQuest
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface UserQuestRepository : R2dbcRepository<UserQuest, Long> {

    fun findAllByUserId(userId: Long): Flux<UserQuest>

    fun findByUserIdAndQuestId(userId: Long, questId: Long): Mono<UserQuest>

    /**
     * The only place that creates `user_quests` rows. It adds an `IN_PROGRESS` row for every quest
     * that is not `DEPRECATED` and that this user has no row for yet, so it is safe to call
     * repeatedly and also hands existing users any quest added after they registered.
     *
     * `ON CONFLICT` relies on the unique constraint on (`user_id`, `quest_id`). The `EXISTS` guard
     * keeps a caller with no `users` row from failing on the foreign key. Returns the number of
     * rows inserted.
     */
    @Modifying
    @Query(
        """
        INSERT INTO user_quests(user_id, quest_id, state)
        SELECT :userId, q.id, 'IN_PROGRESS'
        FROM quests q
        WHERE q.access_type <> 'DEPRECATED'
          AND EXISTS (SELECT 1 FROM users u WHERE u.id = :userId)
        ON CONFLICT (user_id, quest_id) DO NOTHING
        """,
    )
    fun insertMissing(userId: Long): Mono<Long>

    /**
     * Claims a quest: moves it from `IN_PROGRESS` to `COMPLETED` and returns the number of rows
     * changed. Under concurrent calls only one transaction sees the row still `IN_PROGRESS`; the
     * other waits on the row lock, re-reads the committed `COMPLETED` row, and changes 0 rows.
     */
    @Modifying
    @Query(
        """
        UPDATE user_quests
        SET state = 'COMPLETED'
        WHERE user_id = :userId
          AND quest_id = :questId
          AND state = 'IN_PROGRESS'
        """,
    )
    fun claim(userId: Long, questId: Long): Mono<Long>
}
