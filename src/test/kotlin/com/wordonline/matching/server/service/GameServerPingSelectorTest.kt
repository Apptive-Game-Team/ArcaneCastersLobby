package com.wordonline.matching.server.service

import com.wordonline.matching.server.entity.Server
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameServerPingSelectorTest {

    private fun server(id: Long) = Server(id = id, protocol = "http", domain = "s$id", port = 80)

    private val servers = listOf(server(1), server(2), server(3))

    @Test
    fun `참가자들의 최대 핑이 가장 낮은 서버가 먼저 온다`() {
        // 서버1: max(20, 200)=200, 서버2: max(90, 100)=100, 서버3: max(10, 150)=150
        val ordered = GameServerPingSelector.order(
            servers,
            listOf(mapOf(1L to 20L, 2L to 90L, 3L to 10L), mapOf(1L to 200L, 2L to 100L, 3L to 150L)),
        )

        assertThat(ordered.map { it.id }).containsExactly(2L, 3L, 1L)
    }

    @Test
    fun `핑을 보고하지 않은 참가자가 있는 서버는 측정된 서버 뒤로 간다`() {
        val ordered = GameServerPingSelector.order(
            servers,
            listOf(mapOf(1L to 500L, 2L to 50L, 3L to 50L), mapOf(1L to 500L, 2L to 50L)),
        )

        assertThat(ordered.map { it.id }).containsExactly(2L, 1L, 3L)
    }

    @Test
    fun `핑 정보가 없으면 발견 순서를 그대로 유지한다`() {
        assertThat(GameServerPingSelector.order(servers, emptyList())).isEqualTo(servers)
        assertThat(GameServerPingSelector.order(servers, listOf(emptyMap(), emptyMap()))).isEqualTo(servers)
    }

    @Test
    fun `동률이면 발견 순서가 타이브레이커다`() {
        val ordered = GameServerPingSelector.order(servers, listOf(mapOf(1L to 50L, 2L to 50L, 3L to 50L)))

        assertThat(ordered.map { it.id }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `음수나 비정상적으로 큰 핑은 버린다`() {
        val sanitized = GameServerPingSelector.sanitize(mapOf(1L to -1L, 2L to 50L, 3L to 999_999L))

        assertThat(sanitized).containsExactlyEntriesOf(mapOf(2L to 50L))
        assertThat(GameServerPingSelector.sanitize(mapOf(1L to -1L))).isNull()
    }

    @Test
    fun `pings 쿼리 문자열을 파싱하고 깨진 항목은 건너뛴다`() {
        assertThat(GameServerPingSelector.parse("1:42, 2:80,x:1,3,4:abc,5:-3"))
            .containsExactlyInAnyOrderEntriesOf(mapOf(1L to 42L, 2L to 80L))
        assertThat(GameServerPingSelector.parse(null)).isNull()
        assertThat(GameServerPingSelector.parse("")).isNull()
    }
}
