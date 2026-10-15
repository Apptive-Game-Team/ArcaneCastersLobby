package com.wordonline.matching.matching.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** A matchable bot persona with its real `users.mmr`. */
data class MatchableBot(
    val userId: Long,
    val mmr: Long,
)

/** Bot games one `ACTIVE` game server should host. */
data class GameServerBotTarget(
    val serverId: Long,
    val targetBotSessions: Int,
)

/** Reads behind [com.wordonline.matching.matching.service.BotQueueFeeder]. */
@Repository
class BotQueueRepository(
    private val databaseClient: DatabaseClient,
) {
    /**
     * Every `ACTIVE` game server row with its bot game target, falling back to
     * [defaultTargetPerServer] where `target_bot_sessions` is `NULL`.
     */
    suspend fun findActiveGameServerTargets(defaultTargetPerServer: Int): List<GameServerBotTarget> =
        databaseClient.sql(
            """
            SELECT id, COALESCE(target_bot_sessions, :defaultTarget) AS target_bot_sessions
            FROM servers
            WHERE type = 'GAME' AND state = 'ACTIVE'
            ORDER BY id
            """.trimIndent(),
        )
            .bind("defaultTarget", defaultTargetPerServer)
            .map { row, _ ->
                GameServerBotTarget(
                    serverId = row.get("id", Number::class.java)!!.toLong(),
                    targetBotSessions = row.get("target_bot_sessions", Number::class.java)!!.toInt(),
                )
            }
            .all()
            .asFlow()
            .toList()

    /** Every bot user id, matchable or not, so tickets of a bot disabled mid-game are still counted. */
    suspend fun findAllBotUserIds(): List<Long> =
        databaseClient.sql("SELECT user_id FROM bot_personas ORDER BY user_id")
            .map { row, _ -> row.get("user_id", Number::class.java)!!.toLong() }
            .all()
            .asFlow()
            .toList()

    /** Matchable bot personas ([MATCHABLE_BOT_PERSONA_CONDITION]) in random order. */
    suspend fun findMatchableBots(): List<MatchableBot> =
        databaseClient.sql(
            """
            SELECT bp.user_id, u.mmr
            FROM bot_personas bp
            JOIN users u ON u.id = bp.user_id
            WHERE $MATCHABLE_BOT_PERSONA_CONDITION
            ORDER BY RANDOM()
            """.trimIndent(),
        )
            .map { row, _ ->
                MatchableBot(
                    userId = row.get("user_id", Number::class.java)!!.toLong(),
                    mmr = row.get("mmr", Number::class.java)!!.toLong(),
                )
            }
            .all()
            .asFlow()
            .toList()
}
