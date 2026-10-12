package com.wordonline.matching.local.repository

import com.wordonline.matching.local.domain.StaticDataTables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * The row filters of the offline pack live in the SQL this builds. The Postgres integration test runs
 * them against data; this one runs on every build.
 */
class StaticDataQueriesTest {

    private fun spec(name: String) = StaticDataTables.SPECS.single { it.name == name }

    @Test
    fun `실제 사용자 행이 나갈 수 있는 표는 봇 행만 고른다`() {
        assertThat(spec("users").where).isEqualTo("id < 0")
        assertThat(spec("decks").where).isEqualTo("user_id < 0")
        assertThat(spec("deck_cards").where).isEqualTo("deck_id IN (SELECT id FROM decks WHERE user_id < 0)")
        assertThat(spec("users").columns)
            .containsExactly("id", "selected_deck_id", "mmr", "status", "total_wins", "novice_progress")
    }

    @Test
    fun `행 query 는 조건을 붙이고 모든 열로 정렬하며 jsonb 와 enum 은 text 로 읽는다`() {
        val sql = StaticDataQueries.select(
            spec("decks"),
            listOf(ColumnInfo("id", "bigint"), ColumnInfo("data", "jsonb"), ColumnInfo("tier", "USER-DEFINED"), ColumnInfo("ids", "ARRAY")),
        )

        assertThat(sql).isEqualTo(
            "SELECT \"id\", \"data\"::text, \"tier\"::text, to_jsonb(\"ids\")::text FROM \"decks\" WHERE user_id < 0 ORDER BY 1, 2, 3, 4",
        )
    }

    @Test
    fun `timestamp 는 ISO-8601 문자열이 된다`() {
        assertThat(StaticDataQueries.toJsonValue(LocalDateTime.parse("2026-10-12T03:00:00"))).isEqualTo("2026-10-12T03:00:00")
        assertThat(StaticDataQueries.toJsonValue(OffsetDateTime.parse("2026-10-12T12:00:00+09:00"))).isEqualTo("2026-10-12T03:00:00Z")
        assertThat(StaticDataQueries.toJsonValue(7L)).isEqualTo(7L)
        assertThat(StaticDataQueries.toJsonValue(null)).isNull()
    }
}
