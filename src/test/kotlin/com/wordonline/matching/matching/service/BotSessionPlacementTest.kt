package com.wordonline.matching.matching.service

import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BotSessionPlacementTest {

    private fun server(id: Long) = Server(
        id = id,
        protocol = "http",
        domain = "game-$id",
        port = 7777,
        state = ServerState.ACTIVE,
        type = ServerType.GAME,
    )

    @Test
    fun `남은 봇 자리가 가장 많은 서버가 먼저다`() {
        val ordered = BotSessionPlacement.orderBySpareCapacity(
            listOf(server(1), server(2), server(3)),
            targets = mapOf(1L to 2, 2L to 3, 3L to 1),
            placedTickets = mapOf(1L to 2, 2L to 2),
        )

        // spare: server 1 -> 1, server 2 -> 2, server 3 -> 1
        assertThat(ordered.map { it.id }).containsExactly(2L, 1L, 3L)
    }

    @Test
    fun `행이 ACTIVE 가 아닌 서버는 목표가 0 이라 뒤로 간다`() {
        val ordered = BotSessionPlacement.orderBySpareCapacity(
            listOf(server(1), server(2)),
            targets = mapOf(2L to 1),
            placedTickets = emptyMap(),
        )

        assertThat(ordered.map { it.id }).containsExactly(2L, 1L)
    }

    @Test
    fun `자리가 같으면 발견 순서를 지킨다`() {
        val ordered = BotSessionPlacement.orderBySpareCapacity(
            listOf(server(3), server(1), server(2)),
            targets = mapOf(1L to 1, 2L to 1, 3L to 1),
            placedTickets = emptyMap(),
        )

        assertThat(ordered.map { it.id }).containsExactly(3L, 1L, 2L)
    }
}
