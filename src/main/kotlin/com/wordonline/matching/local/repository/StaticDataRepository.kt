package com.wordonline.matching.local.repository

import com.wordonline.matching.local.domain.StaticTableSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A column as `information_schema.columns` describes it. */
data class ColumnInfo(
    val name: String,
    val dataType: String,
)

/** Reads the static tables of the offline pack. Every query here is read-only. */
@Repository
class StaticDataRepository(
    private val databaseClient: DatabaseClient,
) {
    /** Columns of each of [tableNames] that exists in the `public` schema, in table order. */
    suspend fun findColumns(tableNames: Collection<String>): Map<String, List<ColumnInfo>> =
        databaseClient.sql(
            """
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name IN (:tableNames)
            ORDER BY table_name, ordinal_position
            """.trimIndent(),
        )
            .bind("tableNames", tableNames)
            .map { row, _ ->
                row.get("table_name", String::class.java)!! to
                    ColumnInfo(row.get("column_name", String::class.java)!!, row.get("data_type", String::class.java)!!)
            }
            .all()
            .asFlow()
            .toList()
            .groupBy({ it.first }, { it.second })

    /** The rows of [spec] over [columns], each value already in a JSON-safe form (see [StaticDataQueries]). */
    suspend fun findRows(spec: StaticTableSpec, columns: List<ColumnInfo>): List<List<Any?>> =
        databaseClient.sql(StaticDataQueries.select(spec, columns))
            .map { row, _ -> columns.indices.map { index -> StaticDataQueries.toJsonValue(row.get(index)) } }
            .all()
            .asFlow()
            .toList()
}

/**
 * Builds the row query of one static table and converts what the driver returns into the value
 * formats of the contract: number, string, boolean or null. `json`, `jsonb`, Postgres enums and any
 * other type without a plain JSON form are cast to text in SQL; arrays become a JSON string.
 */
object StaticDataQueries {

    private val SCALAR_TYPES = setOf(
        "smallint", "integer", "bigint", "real", "double precision", "numeric",
        "boolean", "character varying", "character", "text",
        "timestamp without time zone", "timestamp with time zone", "date",
    )

    fun select(spec: StaticTableSpec, columns: List<ColumnInfo>): String {
        require(columns.isNotEmpty()) { "table ${spec.name} has no columns to read" }
        val selected = columns.joinToString(", ") { expression(it) }
        val where = spec.where?.let { " WHERE $it" }.orEmpty()
        // Every column takes part in the order, not only the first one: tables keyed by two columns
        // would otherwise come back in an order that can differ between two reads of the same data,
        // and the static version is a hash of that order.
        val orderBy = columns.indices.joinToString(", ") { (it + 1).toString() }
        return "SELECT $selected FROM ${quote(spec.name)}$where ORDER BY $orderBy"
    }

    private fun expression(column: ColumnInfo): String {
        val name = quote(column.name)
        return when {
            column.dataType in SCALAR_TYPES -> name
            column.dataType == "ARRAY" -> "to_jsonb($name)::text"
            else -> "$name::text"
        }
    }

    fun toJsonValue(value: Any?): Any? = when (value) {
        null, is Number, is Boolean, is String -> value
        is LocalDateTime -> value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is OffsetDateTime -> value.toInstant().toString()
        is ZonedDateTime -> value.toInstant().toString()
        is LocalDate -> value.toString()
        is LocalTime -> value.toString()
        else -> value.toString()
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
