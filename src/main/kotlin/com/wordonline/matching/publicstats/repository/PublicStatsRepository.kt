package com.wordonline.matching.publicstats.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

/** A ranking candidate before the account server supplies the name. */
data class RankingCandidate(
    val userId: Long,
    val mmr: Int,
    val wins: Long,
    val losses: Long,
    val draws: Long,
    val botName: String? = null,
)

/** Per-magic deck figures from `statistic_game_decks`. */
data class MagicDeckFigures(
    val magicId: Long,
    val name: String,
    val prefab: String?,
    val decks: Long,
    val winOutcomeDecks: Long,
    val winOutcomeDecksWon: Long,
)

/**
 * 공개 랭킹은 일반 사용자와 활성화된 봇을 포함하고, 마법 통계는 일반 사용자만 집계한다.
 * 완료된 `PVP` 경기의 `WIN`, `DRAW` 결과만 집계한다.
 *
 * As in [com.wordonline.matching.auth.repository.UserStatisticsRepository], participants come from
 * `statistic_game_sessions` joined to `statistic_games`, because `win_user_id`/`loss_user_id` are
 * null for a `DRAW`.
 *
 * `statistic_games.created_at` is `timestamp without time zone`; it is compared as UTC.
 */
@Repository
class PublicStatsRepository(
    private val databaseClient: DatabaseClient,
) {
    /** Top [limit] players by mmr with at least [minGames] games, counted for all of them in one query. */
    suspend fun findRankingCandidates(minGames: Int, limit: Int): List<RankingCandidate> =
        databaseClient.sql(
            """
            WITH participants AS (
                SELECT sgs.left_user_id AS user_id, sg.outcome, sg.win_user_id
                FROM statistic_game_sessions sgs
                JOIN statistic_games sg ON sg.id = sgs.statistic_game_id
                WHERE sgs.game_type = 'PVP' AND sg.outcome IN ('WIN', 'DRAW')
                UNION ALL
                SELECT sgs.right_user_id, sg.outcome, sg.win_user_id
                FROM statistic_game_sessions sgs
                JOIN statistic_games sg ON sg.id = sgs.statistic_game_id
                WHERE sgs.game_type = 'PVP' AND sg.outcome IN ('WIN', 'DRAW')
            )
            SELECT u.id AS user_id, u.mmr AS mmr, bp.name AS bot_name,
                COUNT(*) FILTER (WHERE p.outcome = 'WIN' AND p.win_user_id = u.id) AS wins,
                COUNT(*) FILTER (WHERE p.outcome = 'WIN' AND p.win_user_id <> u.id) AS losses,
                COUNT(*) FILTER (WHERE p.outcome = 'DRAW') AS draws
            FROM participants p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN bot_personas bp ON bp.user_id = u.id AND u.id < 0
            WHERE u.id > 0 OR bp.enabled = TRUE
            GROUP BY u.id, u.mmr, bp.name
            HAVING COUNT(*) >= :minGames
            ORDER BY u.mmr DESC, wins DESC, u.id ASC
            LIMIT :limit
            """.trimIndent(),
        )
            .bind("minGames", minGames.toLong())
            .bind("limit", limit.toLong())
            .map { row, _ ->
                RankingCandidate(
                    userId = row.get("user_id", Number::class.java)!!.toLong(),
                    mmr = row.get("mmr", Number::class.java)!!.toInt(),
                    wins = row.get("wins", Number::class.java)!!.toLong(),
                    losses = row.get("losses", Number::class.java)!!.toLong(),
                    draws = row.get("draws", Number::class.java)!!.toLong(),
                    botName = row.get("bot_name", String::class.java),
                )
            }
            .all()
            .asFlow()
            .toList()

    /** Finished PVP games (WIN or DRAW) created in `[from, to)`. */
    suspend fun countGames(from: Instant, to: Instant): Long =
        databaseClient.sql(
            """
            SELECT COUNT(*) AS total FROM statistic_games sg
            WHERE $WINDOW_GAMES_WHERE
            """.trimIndent(),
        )
            .bindWindow(from, to)
            .map { row, _ -> row.get("total", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    /** Distinct (game, real user) deck entries in the window: the denominator of every pick rate. */
    suspend fun countDeckEntries(from: Instant, to: Instant): Long =
        databaseClient.sql(
            """
            SELECT COUNT(*) AS total FROM (
                SELECT DISTINCT d.statistic_game_id, d.user_id
                FROM statistic_game_decks d
                JOIN statistic_games sg ON sg.id = d.statistic_game_id
                WHERE d.user_id > 0 AND $WINDOW_GAMES_WHERE
            ) entries
            """.trimIndent(),
        )
            .bindWindow(from, to)
            .map { row, _ -> row.get("total", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    suspend fun findMagicDeckFigures(from: Instant, to: Instant): List<MagicDeckFigures> =
        databaseClient.sql(
            """
            SELECT m.id AS magic_id, m.name AS name, m.prefab AS prefab,
                COUNT(*) AS decks,
                COUNT(*) FILTER (WHERE e.outcome = 'WIN') AS win_outcome_decks,
                COUNT(*) FILTER (WHERE e.outcome = 'WIN' AND e.win_user_id = e.user_id) AS win_outcome_decks_won
            FROM (
                SELECT DISTINCT d.statistic_game_id, d.user_id, d.magic_id, sg.outcome, sg.win_user_id
                FROM statistic_game_decks d
                JOIN statistic_games sg ON sg.id = d.statistic_game_id
                WHERE d.user_id > 0 AND $WINDOW_GAMES_WHERE
            ) e
            JOIN magics m ON m.id = e.magic_id
            GROUP BY m.id, m.name, m.prefab
            ORDER BY decks DESC, m.id ASC
            """.trimIndent(),
        )
            .bindWindow(from, to)
            .map { row, _ ->
                MagicDeckFigures(
                    magicId = row.get("magic_id", Number::class.java)!!.toLong(),
                    name = row.get("name", String::class.java)!!,
                    prefab = row.get("prefab", String::class.java),
                    decks = row.get("decks", Number::class.java)!!.toLong(),
                    winOutcomeDecks = row.get("win_outcome_decks", Number::class.java)!!.toLong(),
                    winOutcomeDecksWon = row.get("win_outcome_decks_won", Number::class.java)!!.toLong(),
                )
            }
            .all()
            .asFlow()
            .toList()

    /** Sum of `statistic_game_magics.count` per magic id, real users, games in the window. */
    suspend fun sumCastsByMagic(from: Instant, to: Instant): Map<Long, Long> =
        databaseClient.sql(
            """
            SELECT c.magic_id AS magic_id, COALESCE(SUM(c.count), 0) AS casts
            FROM statistic_game_magics c
            JOIN statistic_games sg ON sg.id = c.statistic_game_id
            WHERE c.user_id > 0 AND $WINDOW_GAMES_WHERE
            GROUP BY c.magic_id
            """.trimIndent(),
        )
            .bindWindow(from, to)
            .map { row, _ ->
                row.get("magic_id", Number::class.java)!!.toLong() to row.get("casts", Number::class.java)!!.toLong()
            }
            .all()
            .asFlow()
            .toList()
            .toMap()

    private fun DatabaseClient.GenericExecuteSpec.bindWindow(from: Instant, to: Instant) =
        bind("from", from.atOffset(ZoneOffset.UTC).toLocalDateTime())
            .bind("to", to.atOffset(ZoneOffset.UTC).toLocalDateTime())

    private companion object {
        const val WINDOW_GAMES_WHERE = """
            sg.game_type = 'PVP' AND sg.outcome IN ('WIN', 'DRAW')
            AND sg.created_at >= :from AND sg.created_at < :to
        """
    }
}
