package com.wordonline.matching.local.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.math.BigDecimal

/** One `statistic_game_decks` or `statistic_game_magics` row before it gets its game id. */
data class StatisticCount(
    val userId: Long,
    val magicId: Long,
    val count: Int,
)

/**
 * Everything one accepted local result writes, decided by the service. Written in one transaction
 * by [LocalSessionRepository.recordAccepted].
 *
 * @property finishScenarioId scenario to move from `ACTIVE` to `FINISHED` for [userId]
 * @property incrementWins whether [userId]'s `total_wins` goes up by one
 * @property noviceProgressStep how far [userId]'s `novice_progress` moves, or `null` to leave it
 */
data class AcceptedLocalResult(
    val userId: Long,
    val gameType: String,
    val outcome: String,
    val winUserId: Long?,
    val lossUserId: Long?,
    val durationSeconds: Long,
    val serverVersion: String,
    val decks: List<StatisticCount>,
    val magics: List<StatisticCount>,
    val finishScenarioId: Long?,
    val incrementWins: Boolean,
    val noviceProgressStep: Double?,
)

/** Database reads and writes of local sessions. */
@Repository
class LocalSessionRepository(
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
) {
    /** `user_scenarios.state` of this user and scenario, or `null` without a row. */
    suspend fun findScenarioState(userId: Long, scenarioId: Long): String? =
        databaseClient.sql("SELECT state FROM user_scenarios WHERE user_id = :userId AND scenario_id = :scenarioId")
            .bind("userId", userId)
            .bind("scenarioId", scenarioId)
            .map { row, _ -> row.get("state", String::class.java) }
            .one()
            .awaitSingleOrNull()

    /**
     * The user's selected deck as one magic id per card, ordered by magic id. Read the way the game
     * server reads a selected deck, joined to `magics`, so the list is what a shared-server session
     * would deal from. Empty without a selected deck.
     */
    suspend fun findSelectedDeckCardIds(userId: Long): List<Long> =
        databaseClient.sql(
            """
            SELECT dc.magic_id, dc.count
            FROM users u
            JOIN deck_cards dc ON dc.deck_id = u.selected_deck_id
            JOIN magics m ON m.id = dc.magic_id
            WHERE u.id = :userId
            ORDER BY dc.magic_id, dc.id
            """.trimIndent(),
        )
            .bind("userId", userId)
            .map { row, _ -> row.get("magic_id", Number::class.java)!!.toLong() to row.get("count", Number::class.java)!!.toInt() }
            .all()
            .asFlow()
            .toList()
            .flatMap { (magicId, count) -> List(count.coerceAtLeast(0)) { magicId } }

    /** Magic ids the user owns at least one of, ordered. */
    suspend fun findOwnedMagicIds(userId: Long): List<Long> =
        databaseClient.sql("SELECT magic_id FROM user_magics WHERE user_id = :userId AND count > 0 ORDER BY magic_id")
            .bind("userId", userId)
            .map { row, _ -> row.get("magic_id", Number::class.java)!!.toLong() }
            .all()
            .asFlow()
            .toList()

    /**
     * `users.novice_progress`, or `null` without a row. Read through `numeric` because the column is
     * `real`: widening the float itself turns 0.65 into 0.6499999761581421.
     */
    suspend fun findNoviceProgress(userId: Long): Double? =
        databaseClient.sql("SELECT novice_progress::numeric AS novice_progress FROM users WHERE id = :userId")
            .bind("userId", userId)
            .map { row, _ -> row.get("novice_progress", BigDecimal::class.java)?.toDouble() }
            .one()
            .awaitSingleOrNull()

    suspend fun insertRejection(userId: Long, sessionId: String, reason: String, payload: String) {
        databaseClient.sql(
            """
            INSERT INTO local_session_rejections (user_id, session_id, reason, payload)
            VALUES (:userId, :sessionId, :reason, CAST(:payload AS jsonb))
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("sessionId", sessionId.take(SESSION_ID_LENGTH))
            .bind("reason", reason)
            .bind("payload", payload)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Writes [result] in one transaction and returns the new `statistic_games` id. */
    suspend fun recordAccepted(result: AcceptedLocalResult): Long = transactionalOperator.executeAndAwait {
        val gameId = insertGame(result)
        result.decks.forEach { insertCount("statistic_game_decks", gameId, it) }
        result.magics.forEach { insertCount("statistic_game_magics", gameId, it) }
        result.finishScenarioId?.let { finishScenario(result.userId, it) }
        if (result.incrementWins) incrementTotalWins(result.userId)
        result.noviceProgressStep?.let { advanceNoviceProgress(result.userId, it) }
        gameId
    }

    // Same columns and event schema version as the game server's StatisticRepository, plus
    // hosted_locally. duration is whole seconds there too.
    private suspend fun insertGame(result: AcceptedLocalResult): Long {
        val spec = databaseClient.sql(
            """
            INSERT INTO statistic_games (
                outcome, win_user_id, loss_user_id, duration, game_type,
                server_version, event_schema_version, hosted_locally
            )
            VALUES (
                :outcome, :winUserId, :lossUserId, :duration, CAST(:gameType AS game_type),
                :serverVersion, :eventSchemaVersion, TRUE
            )
            RETURNING id
            """.trimIndent(),
        )
            .bind("outcome", result.outcome)
            .bind("duration", result.durationSeconds)
            .bind("gameType", result.gameType)
            .bind("serverVersion", result.serverVersion.take(SERVER_VERSION_LENGTH))
            .bind("eventSchemaVersion", EVENT_SCHEMA_VERSION)
        return spec
            .let { if (result.winUserId == null) it.bindNull("winUserId", java.lang.Long::class.java) else it.bind("winUserId", result.winUserId) }
            .let { if (result.lossUserId == null) it.bindNull("lossUserId", java.lang.Long::class.java) else it.bind("lossUserId", result.lossUserId) }
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()
    }

    // A magic id the database does not know is dropped rather than failing the whole result on the
    // foreign key: the client would resend the same body forever.
    private suspend fun insertCount(table: String, gameId: Long, count: StatisticCount) {
        databaseClient.sql(
            """
            INSERT INTO $table (user_id, statistic_game_id, magic_id, count)
            SELECT :userId, :gameId, :magicId, :count
            WHERE EXISTS (SELECT 1 FROM magics WHERE id = :magicId)
            """.trimIndent(),
        )
            .bind("userId", count.userId)
            .bind("gameId", gameId)
            .bind("magicId", count.magicId)
            .bind("count", count.count)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // Only an ACTIVE row moves; a FINISHED one stays as it is.
    private suspend fun finishScenario(userId: Long, scenarioId: Long) {
        databaseClient.sql(
            """
            UPDATE user_scenarios SET state = 'FINISHED'
            WHERE user_id = :userId AND scenario_id = :scenarioId AND state = 'ACTIVE'
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("scenarioId", scenarioId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // Same statement as the game server's UserRepository.incrementTotalWins.
    private suspend fun incrementTotalWins(userId: Long) {
        databaseClient.sql("UPDATE users SET total_wins = COALESCE(total_wins, 0) + 1 WHERE id = :userId")
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // Same statement as the game server's UserRepository.advanceNoviceProgress: never past 1.0 and
    // never for a player already there.
    private suspend fun advanceNoviceProgress(userId: Long, step: Double) {
        databaseClient.sql(
            """
            UPDATE users SET novice_progress = LEAST(1.0, novice_progress + :step)
            WHERE id = :userId AND novice_progress < 1.0
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("step", step)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    companion object {
        /** `statistic_games.event_schema_version` the game server writes. */
        const val EVENT_SCHEMA_VERSION = 1

        private const val SERVER_VERSION_LENGTH = 64
        private const val SESSION_ID_LENGTH = 64
    }
}
