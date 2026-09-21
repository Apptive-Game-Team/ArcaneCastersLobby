package com.wordonline.matching.auth.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

/** One finished game this user took part in, as read from the `statistic_game_sessions`/`statistic_games` join. */
data class UserGameSessionRecord(
    val opponentId: Long,
    val result: String,
    val gameType: String,
    val playedAt: Instant,
)

/** [win], [lose], and [draw] counts behind `GET /api/users/mine/statistics/overview`. */
data class UserStatisticsOverviewCounts(
    val win: Long,
    val lose: Long,
    val draw: Long,
)

/**
 * `statistic_games` alone cannot tell who played a `DRAW` or `ABANDONED` game: its
 * `win_user_id`/`loss_user_id` are both null for any outcome other than `WIN`
 * (`statistic_games_win_outcome_check`). Participants live on `statistic_game_sessions` instead,
 * as `left_user_id`/`right_user_id`, with `statistic_game_id` filled in once the game ends. So
 * every query here starts from `statistic_game_sessions` and joins `statistic_games` for the
 * outcome, which also means a session still `IN_PROGRESS` (`statistic_game_id IS NULL`) is
 * excluded by the join itself.
 *
 * [MATCHING_GAMES_WHERE] filters on outcome only. Every `game_type` counts: `SessionType.Practice`
 * is not a warm-up mode but every bot match, both the player-vs-bot one and `BotGameScheduler`'s
 * automatic games, and `PVE` is an adventure scenario. Excluding `Practice` was tried first and hid
 * every game a player had actually played, since bot matches are most of them. A bot-vs-bot
 * automatic game still never reaches a player's list: neither participant is the requesting user.
 *
 * `ABANDONED` stays out. It has no winner to report, and a session the watchdog reaped is not a
 * result. `gameType` rides along on each row so the screen can label or filter what this does not.
 */
@Repository
class UserStatisticsRepository(
    private val databaseClient: DatabaseClient,
) {
    suspend fun countOverview(userId: Long): UserStatisticsOverviewCounts =
        databaseClient.sql(
            """
            SELECT
                COUNT(*) FILTER (WHERE sg.outcome = 'WIN' AND sg.win_user_id = :userId) AS win_count,
                COUNT(*) FILTER (WHERE sg.outcome = 'WIN' AND sg.win_user_id <> :userId) AS lose_count,
                COUNT(*) FILTER (WHERE sg.outcome = 'DRAW') AS draw_count
            FROM statistic_game_sessions sgs
            JOIN statistic_games sg ON sg.id = sgs.statistic_game_id
            WHERE $MATCHING_GAMES_WHERE
            """.trimIndent(),
        )
            .bind("userId", userId)
            .map { row, _ ->
                UserStatisticsOverviewCounts(
                    win = row.get("win_count", Number::class.java)!!.toLong(),
                    lose = row.get("lose_count", Number::class.java)!!.toLong(),
                    draw = row.get("draw_count", Number::class.java)!!.toLong(),
                )
            }
            .one()
            .awaitSingle()

    suspend fun countGames(userId: Long): Long =
        databaseClient.sql(
            """
            SELECT COUNT(*) AS total
            FROM statistic_game_sessions sgs
            JOIN statistic_games sg ON sg.id = sgs.statistic_game_id
            WHERE $MATCHING_GAMES_WHERE
            """.trimIndent(),
        )
            .bind("userId", userId)
            .map { row, _ -> row.get("total", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    suspend fun findGames(userId: Long, limit: Int, offset: Long): List<UserGameSessionRecord> =
        databaseClient.sql(
            """
            SELECT
                CASE WHEN sgs.left_user_id = :userId THEN sgs.right_user_id ELSE sgs.left_user_id END AS opponent_id,
                CASE
                    WHEN sg.outcome = 'DRAW' THEN 'draw'
                    WHEN sg.win_user_id = :userId THEN 'win'
                    ELSE 'lose'
                END AS result,
                sgs.game_type::text AS game_type,
                sgs.started_at AS played_at
            FROM statistic_game_sessions sgs
            JOIN statistic_games sg ON sg.id = sgs.statistic_game_id
            WHERE $MATCHING_GAMES_WHERE
            ORDER BY sgs.started_at DESC, sgs.id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ ->
                UserGameSessionRecord(
                    opponentId = row.get("opponent_id", Long::class.java)!!,
                    result = row.get("result", String::class.java)!!,
                    gameType = row.get("game_type", String::class.java)!!,
                    playedAt = row.get("played_at", OffsetDateTime::class.java)!!.toInstant(),
                )
            }
            .all()
            .asFlow()
            .toList()

    private companion object {
        const val MATCHING_GAMES_WHERE = """
            (sgs.left_user_id = :userId OR sgs.right_user_id = :userId)
            AND sg.outcome <> 'ABANDONED'
        """
    }
}
