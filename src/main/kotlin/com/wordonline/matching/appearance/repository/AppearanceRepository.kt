package com.wordonline.matching.appearance.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** One `appearances` row with this user's ownership and selection. */
data class AppearanceOwnershipRow(
    val key: String,
    val sortOrder: Int,
    val owned: Boolean,
    val selected: Boolean,
)

/** Whether an appearance key exists and whether this user owns it. */
data class AppearanceKeyOwnership(val owned: Boolean)

/**
 * Reads `appearances` and `user_appearances` and writes `users.appearance`.
 *
 * The appearance [DEFAULT_KEY] counts as owned by every user without a `user_appearances` row
 * (WordOnlineDatabase V020); every query here applies that rule in SQL.
 */
@Repository
class AppearanceRepository(
    private val databaseClient: DatabaseClient,
) {
    /** `appearances.key` of the id, or null when no such appearance exists. */
    suspend fun findKeyById(appearanceId: Long): String? =
        databaseClient.sql("SELECT key FROM appearances WHERE id = :appearanceId")
            .bind("appearanceId", appearanceId)
            .map { row, _ -> row.get("key", String::class.java) }
            .one()
            .awaitSingleOrNull()

    /**
     * Gives the user the appearance. Returns the number of rows inserted: 1, or 0 when the user
     * already owns it, which is not an error.
     */
    suspend fun insertOwnership(userId: Long, appearanceId: Long): Long =
        databaseClient.sql(
            """
            INSERT INTO user_appearances (user_id, appearance_id)
            VALUES (:userId, :appearanceId)
            ON CONFLICT (user_id, appearance_id) DO NOTHING
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("appearanceId", appearanceId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** The whole catalog ordered by `sort_order`, then id, marked with what this user owns and has selected. */
    suspend fun findCatalogForUser(userId: Long): List<AppearanceOwnershipRow> =
        databaseClient.sql(
            """
            SELECT a.key,
                   a.sort_order,
                   (a.key = '$DEFAULT_KEY' OR ua.id IS NOT NULL) AS owned,
                   COALESCE(a.key = u.appearance, FALSE) AS selected
            FROM appearances a
            LEFT JOIN user_appearances ua ON ua.appearance_id = a.id AND ua.user_id = :userId
            LEFT JOIN users u ON u.id = :userId
            ORDER BY a.sort_order, a.id
            """.trimIndent(),
        )
            .bind("userId", userId)
            .map { row, _ ->
                AppearanceOwnershipRow(
                    key = row.get("key", String::class.java)!!,
                    sortOrder = row.get("sort_order", Number::class.java)!!.toInt(),
                    owned = row.get("owned", Boolean::class.javaObjectType)!!,
                    selected = row.get("selected", Boolean::class.javaObjectType)!!,
                )
            }
            .all()
            .asFlow()
            .toList()

    /**
     * Sets `users.appearance` to [key] only when the user owns it, in one statement, so the
     * ownership check and the write cannot be separated by a concurrent change. Returns the number
     * of rows changed: 1 when selected, 0 when the key is unknown, not owned, or the user has no
     * `users` row.
     */
    suspend fun selectIfOwned(userId: Long, key: String): Long =
        databaseClient.sql(
            """
            UPDATE users u
            SET appearance = a.key
            FROM appearances a
            WHERE u.id = :userId
              AND a.key = :key
              AND (a.key = '$DEFAULT_KEY'
                   OR EXISTS (SELECT 1 FROM user_appearances ua
                              WHERE ua.user_id = u.id AND ua.appearance_id = a.id))
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("key", key)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** Null when [key] is not in `appearances`; otherwise whether this user owns it. */
    suspend fun findKeyOwnership(userId: Long, key: String): AppearanceKeyOwnership? =
        databaseClient.sql(
            """
            SELECT (a.key = '$DEFAULT_KEY'
                    OR EXISTS (SELECT 1 FROM user_appearances ua
                               WHERE ua.user_id = :userId AND ua.appearance_id = a.id)) AS owned
            FROM appearances a
            WHERE a.key = :key
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("key", key)
            .map { row, _ -> AppearanceKeyOwnership(row.get("owned", Boolean::class.javaObjectType)!!) }
            .one()
            .awaitSingleOrNull()

    companion object {
        /** The appearance every user owns without a `user_appearances` row. */
        const val DEFAULT_KEY = "default"
    }
}
