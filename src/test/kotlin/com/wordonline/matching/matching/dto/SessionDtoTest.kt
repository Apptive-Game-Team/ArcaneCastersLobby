package com.wordonline.matching.matching.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionDtoTest {

    @Test
    fun `사람끼리는 PVP 이고 덱 스냅샷을 싣는다`() {
        val dto = SessionDto.from("session-1", 1L, 2L, listOf(10L), listOf(20L))

        assertThat(dto.sessionType).isEqualTo(SessionType.PVP)
        assertThat(dto.leftDeckCardIds).containsExactly(10L)
    }

    @Test
    fun `어느 한쪽이라도 봇이면 Practice 다`() {
        assertThat(SessionDto.from("session-1", 1L, -2L).sessionType).isEqualTo(SessionType.Practice)
        assertThat(SessionDto.from("session-1", -1L, 2L).sessionType).isEqualTo(SessionType.Practice)
    }

    @Test
    fun `봇끼리도 Practice 이고 덱은 game 서버가 봇의 선택 덱을 읽는다`() {
        val dto = SessionDto.from("session-1", -1L, -2L, listOf(10L), listOf(20L))

        assertThat(dto.sessionType).isEqualTo(SessionType.Practice)
        assertThat(dto.uid1).isEqualTo(-1L)
        assertThat(dto.uid2).isEqualTo(-2L)
        assertThat(dto.leftDeckCardIds).isNull()
    }
}
