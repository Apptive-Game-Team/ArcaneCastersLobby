package com.wordonline.matching.chest.repository

import com.wordonline.matching.chest.entity.ChestReward
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

/** One unopened `user_chests` row with its chest's key. */
data class UnopenedChestRow(
    val userChestId: Long,
    val chestId: Long,
    val chestKey: String,
    val acquiredAt: Instant,
)

/** Reads `chests` and `chest_rewards`, and writes `user_chests`. */
@Repository
class ChestRepository(
    private val databaseClient: DatabaseClient,
) {
    /** `chests.key` of the id, or null when no such chest exists. */
    suspend fun findKeyById(chestId: Long): String? =
        databaseClient.sql("SELECT key FROM chests WHERE id = :chestId")
            .bind("chestId", chestId)
            .map { row, _ -> row.get("key", String::class.java) }
            .one()
            .awaitSingleOrNull()

    /**
     * Gives the user [amount] unopened chests of [chestId], one `user_chests` row each. Returns the
     * number of rows inserted. A [chestId] with no `chests` row fails on the foreign key.
     */
    suspend fun insertUnopened(userId: Long, chestId: Long, amount: Int): Long =
        databaseClient.sql(
            """
            INSERT INTO user_chests (user_id, chest_id)
            SELECT :userId, :chestId
            FROM generate_series(1, :amount)
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("chestId", chestId)
            .bind("amount", amount)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** This user's unopened chests, oldest first (by `acquired_at`, then id). */
    suspend fun findUnopened(userId: Long): List<UnopenedChestRow> =
        databaseClient.sql(
            """
            SELECT uc.id, uc.chest_id, c.key, uc.acquired_at
            FROM user_chests uc
            JOIN chests c ON c.id = uc.chest_id
            WHERE uc.user_id = :userId
              AND uc.opened_at IS NULL
            ORDER BY uc.acquired_at, uc.id
            """.trimIndent(),
        )
            .bind("userId", userId)
            .map { row, _ ->
                UnopenedChestRow(
                    userChestId = row.get("id", Number::class.java)!!.toLong(),
                    chestId = row.get("chest_id", Number::class.java)!!.toLong(),
                    chestKey = row.get("key", String::class.java)!!,
                    acquiredAt = row.get("acquired_at", OffsetDateTime::class.java)!!.toInstant(),
                )
            }
            .all()
            .asFlow()
            .toList()

    /** Contents of the given chests in one query, ordered by chest id and then reward id. */
    suspend fun findRewardsOfChests(chestIds: Collection<Long>): List<ChestReward> {
        if (chestIds.isEmpty()) {
            return emptyList()
        }
        return databaseClient.sql(
            """
            SELECT cr.id, cr.chest_id, cr.reward_type, cr.target_id, cr.amount
            FROM chest_rewards cr
            WHERE cr.chest_id IN (:chestIds)
            ORDER BY cr.chest_id, cr.id
            """.trimIndent(),
        )
            .bind("chestIds", chestIds.distinct())
            .map { row, _ -> toChestReward(row) }
            .all()
            .asFlow()
            .toList()
    }

    /** Every `chest_rewards` row, for the startup check. */
    suspend fun findAllRewards(): List<ChestReward> =
        databaseClient.sql(
            """
            SELECT cr.id, cr.chest_id, cr.reward_type, cr.target_id, cr.amount
            FROM chest_rewards cr
            ORDER BY cr.chest_id, cr.id
            """.trimIndent(),
        )
            .map { row, _ -> toChestReward(row) }
            .all()
            .asFlow()
            .toList()

    /**
     * Claims an unopened chest of this user: sets `opened_at` and returns its `chest_id`, or null
     * when no row changed (the id does not exist, belongs to another user, or is already opened).
     *
     * The conditional `UPDATE` takes the row lock. A second transaction opening the same row waits
     * for the first to end, re-reads the row, finds `opened_at` set and changes nothing; if the first
     * rolled back, the second sees the row still unopened and claims it.
     */
    suspend fun claimUnopened(userChestId: Long, userId: Long): Long? =
        databaseClient.sql(
            """
            UPDATE user_chests
            SET opened_at = now()
            WHERE id = :userChestId
              AND user_id = :userId
              AND opened_at IS NULL
            RETURNING chest_id
            """.trimIndent(),
        )
            .bind("userChestId", userChestId)
            .bind("userId", userId)
            .map { row, _ -> row.get("chest_id", Number::class.java)!!.toLong() }
            .one()
            .awaitSingleOrNull()

    /** Null when this user has no `user_chests` row with this id; otherwise whether it is opened. */
    suspend fun findOpened(userChestId: Long, userId: Long): Boolean? =
        databaseClient.sql(
            "SELECT opened_at IS NOT NULL AS opened FROM user_chests WHERE id = :userChestId AND user_id = :userId",
        )
            .bind("userChestId", userChestId)
            .bind("userId", userId)
            .map { row, _ -> row.get("opened", Boolean::class.javaObjectType)!! }
            .one()
            .awaitSingleOrNull()

    private fun toChestReward(row: Row): ChestReward =
        ChestReward(
            id = row.get("id", Number::class.java)!!.toLong(),
            chestId = row.get("chest_id", Number::class.java)!!.toLong(),
            rewardType = row.get("reward_type", String::class.java)!!,
            targetId = row.get("target_id", Number::class.java)?.toLong(),
            amount = row.get("amount", Number::class.java)!!.toInt(),
        )
}
