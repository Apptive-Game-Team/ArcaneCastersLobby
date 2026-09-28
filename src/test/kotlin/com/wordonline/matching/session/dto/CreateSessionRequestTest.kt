package com.wordonline.matching.session.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.dto.SessionDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CreateSessionRequestTest {
    @Test
    fun `game server request contract is flat`() {
        val objectMapper = ObjectMapper()
        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                CreateSessionRequest("attempt-1", SessionDto.PVP("session-1", 1L, 2L)),
            ),
        )

        assertThat(json["attemptId"].asText()).isEqualTo("attempt-1")
        assertThat(json["sessionId"].asText()).isEqualTo("session-1")
        assertThat(json["uid1"].asLong()).isEqualTo(1L)
        assertThat(json["uid2"].asLong()).isEqualTo(2L)
        assertThat(json["sessionType"].asText()).isEqualTo("PVP")
        assertThat(json["scenarioId"].isNull).isTrue()
        assertThat(json.has("session")).isFalse()
    }

    @Test
    fun `random deck card ids ride along as arrays`() {
        val objectMapper = ObjectMapper()
        val leftDeck = (1L..15L).toList()
        val rightDeck = (16L..30L).toList()

        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(
                CreateSessionRequest(
                    "attempt-1",
                    SessionDto.PVP("session-1", 1L, 2L, leftDeck, rightDeck),
                ),
            ),
        )

        assertThat(json["leftDeckCardIds"].map { it.asLong() }).isEqualTo(leftDeck)
        assertThat(json["rightDeckCardIds"].map { it.asLong() }).isEqualTo(rightDeck)
    }
}
