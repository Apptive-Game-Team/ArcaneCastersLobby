package com.wordonline.matching.matching.repository

import com.wordonline.matching.matching.domain.PracticeDifficulty
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

data class PracticeHistory(val id: String, val wins: Int, val losses: Int, val draws: Int)

@Repository
class PracticeHistoryRepository(private val databaseClient: DatabaseClient) {
    // 상대는 서버가 정한 ID만 사용한다. 같은 등급에서 만난 여러 봇의 경기를 합친 뒤 최근 5전을 센다.
    private val roster = PracticeDifficulty.entries.flatMap { tier ->
        tier.botIds.map { "($it, '${tier.name}')" }
    }.joinToString()

    suspend fun recent(userId: Long): List<PracticeHistory> = databaseClient.sql(
        """
        WITH roster(bot_id, tier) AS (VALUES $roster),
        ranked AS (
            SELECT roster.tier, g.outcome, g.win_user_id,
                   ROW_NUMBER() OVER (PARTITION BY roster.tier ORDER BY s.ended_at DESC, s.id DESC) AS n
            FROM statistic_game_sessions s
            JOIN statistic_games g ON g.id = s.statistic_game_id
            JOIN roster ON roster.bot_id =
                CASE WHEN s.left_user_id = :userId THEN s.right_user_id ELSE s.left_user_id END
            WHERE (s.left_user_id = :userId OR s.right_user_id = :userId)
              AND s.game_type = 'Practice' AND g.outcome IN ('WIN', 'DRAW')
        )
        SELECT tier,
               COUNT(*) FILTER (WHERE outcome = 'WIN' AND win_user_id = :userId) AS wins,
               COUNT(*) FILTER (WHERE outcome = 'WIN' AND win_user_id <> :userId) AS losses,
               COUNT(*) FILTER (WHERE outcome = 'DRAW') AS draws
        FROM ranked WHERE n <= 5 GROUP BY tier
        """.trimIndent(),
    ).bind("userId", userId).map { row, _ ->
        PracticeHistory(
            row.get("tier", String::class.java)!!,
            row.get("wins", Number::class.java)!!.toInt(),
            row.get("losses", Number::class.java)!!.toInt(),
            row.get("draws", Number::class.java)!!.toInt(),
        )
    }.all().asFlow().toList()
}
