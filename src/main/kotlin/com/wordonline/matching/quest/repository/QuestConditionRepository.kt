package com.wordonline.matching.quest.repository

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** Queries the built-in quest conditions use to measure progress. */
@Repository
class QuestConditionRepository(
    private val databaseClient: DatabaseClient,
) {
    /** Number of stages this user has finished, across all adventures. */
    suspend fun countFinishedStages(userId: Long): Int =
        countFinishedStages(userId, extraCondition = null, parameterName = null, parameterValue = null)

    /** Number of stages of the adventure [adventureId] this user has finished. */
    suspend fun countFinishedStagesOfAdventure(userId: Long, adventureId: Long): Int =
        countFinishedStages(userId, "st.adventure_id = :adventureId", "adventureId", adventureId)

    /** Whether this user has finished the stage [stageId]. */
    suspend fun isStageFinished(userId: Long, stageId: Long): Boolean =
        countFinishedStages(userId, "st.id = :stageId", "stageId", stageId) > 0

    /** `users.total_wins` for this user, 0 when the row or the value is missing. */
    suspend fun findTotalWins(userId: Long): Int =
        databaseClient.sql("SELECT total_wins FROM users WHERE id = :userId")
            .bind("userId", userId)
            .map { row, _ -> row.get("total_wins", Number::class.java)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull() ?: 0

    /**
     * A stage counts as finished when every scenario of it that the user has a `user_scenarios`
     * row for is `FINISHED`. This is the query the Java `UserScenarioRepository.countFinishedStageByUserId`
     * ran, moved here unchanged, with an optional extra filter on the stage.
     */
    private suspend fun countFinishedStages(
        userId: Long,
        extraCondition: String?,
        parameterName: String?,
        parameterValue: Long?,
    ): Int {
        val filter = extraCondition?.let { "AND $it" } ?: ""
        var spec = databaseClient.sql(
            """
            SELECT COUNT(*) AS count
            FROM (
                SELECT st.id
                FROM stages st
                JOIN scenarios sc ON st.id = sc.stage_id
                JOIN user_scenarios us ON sc.id = us.scenario_id
                WHERE us.user_id = :userId
                  $filter
                GROUP BY st.id
                HAVING COUNT(sc.id) = COUNT(CASE WHEN us.state = 'FINISHED' THEN 1 END)
            ) AS finished_stages
            """.trimIndent(),
        ).bind("userId", userId)
        if (parameterName != null && parameterValue != null) {
            spec = spec.bind(parameterName, parameterValue)
        }
        return spec
            .map { row, _ -> row.get("count", Number::class.java)?.toInt() ?: 0 }
            .one()
            .awaitSingleOrNull() ?: 0
    }
}
