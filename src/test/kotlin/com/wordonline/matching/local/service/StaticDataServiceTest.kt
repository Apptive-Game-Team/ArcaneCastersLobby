package com.wordonline.matching.local.service

import com.wordonline.matching.local.domain.StaticTableSpec
import com.wordonline.matching.local.repository.ColumnInfo
import com.wordonline.matching.local.repository.StaticDataRepository
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class StaticDataServiceTest {

    private val repository: StaticDataRepository = mock()
    private val service = StaticDataService(repository)

    private val magicColumns = listOf(ColumnInfo("id", "bigint"), ColumnInfo("name", "character varying"))
    private val userColumns = listOf(
        ColumnInfo("id", "bigint"),
        ColumnInfo("selected_deck_id", "bigint"),
        ColumnInfo("mmr", "smallint"),
        ColumnInfo("created_at", "timestamp without time zone"),
        ColumnInfo("status", "character varying"),
        ColumnInfo("total_wins", "integer"),
        ColumnInfo("novice_progress", "real"),
        ColumnInfo("appearance", "character varying"),
    )

    private suspend fun givenTables(magicRows: List<List<Any?>>) {
        whenever(repository.findColumns(any())).thenReturn(mapOf("magics" to magicColumns, "users" to userColumns))
        whenever(repository.findRows(argThat { name == "magics" }, any())).thenReturn(magicRows)
        whenever(repository.findRows(argThat { name == "users" }, any())).thenReturn(listOf(listOf(-54L, -1L, 1000, "Online", 0, 1.0f)))
    }

    @Test
    fun `같은 데이터를 두 번 만들면 staticVersion 이 같고 행 하나가 바뀌면 달라진다`() = runBlocking<Unit> {
        givenTables(listOf(listOf(1L, "fire_shot"), listOf(2L, "ice_shot")))
        val first = service.build()
        val second = service.build()

        givenTables(listOf(listOf(1L, "fire_shot"), listOf(2L, "ice_wall")))
        val changed = service.build()

        assertThat(first.staticVersion).matches("[0-9a-f]{64}").isEqualTo(second.staticVersion)
        assertThat(changed.staticVersion).isNotEqualTo(first.staticVersion)
    }

    @Test
    fun `users 는 허용한 열만 그 순서로 읽고 없는 표는 건너뛴다`() = runBlocking<Unit> {
        givenTables(listOf(listOf(1L, "fire_shot")))

        val data = service.build()

        assertThat(data.tables.keys).containsExactly("magics", "users")
        assertThat(data.tables.getValue("users").columns)
            .containsExactly("id", "selected_deck_id", "mmr", "status", "total_wins", "novice_progress")
        verifyBlocking(repository) {
            findRows(argThat<StaticTableSpec> { name == "users" }, argThat { map(ColumnInfo::name) == listOf("id", "selected_deck_id", "mmr", "status", "total_wins", "novice_progress") })
        }
        verifyBlocking(repository, never()) { findRows(argThat<StaticTableSpec> { name == "decks" }, anyOrNull()) }
    }
}
